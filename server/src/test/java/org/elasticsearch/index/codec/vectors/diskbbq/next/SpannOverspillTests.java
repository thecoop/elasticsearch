/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.codec.vectors.diskbbq.next;

import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.elasticsearch.simdvec.ESVectorUtil;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Unit tests for the experiment-only SPANN multi-replica overspill assigner (AIR/SRAIR selection). */
public class SpannOverspillTests extends ESTestCase {

    private static final int M = 16;
    private static final int BEAM_WIDTH = 100;
    private static final float LAMBDA = 0.5f; // RAIRS default AIR amplification

    /** Builds the nearest-centroid (primary) assignment by brute force, matching what clustering produces. */
    private static int[] primaryAssignment(List<float[]> vectors, float[][] centroids) {
        int[] primary = new int[vectors.size()];
        for (int v = 0; v < vectors.size(); v++) {
            float best = Float.MAX_VALUE;
            int bestC = 0;
            for (int c = 0; c < centroids.length; c++) {
                float d = ESVectorUtil.squareDistance(vectors.get(v), centroids[c]);
                if (d < best) {
                    best = d;
                    bestC = c;
                }
            }
            primary[v] = bestC;
        }
        return primary;
    }

    /** With replicaCount == 1 overspill is disabled: every vector keeps exactly its primary. */
    public void testDisabledKeepsOnlyPrimary() throws IOException {
        int dim = 8;
        int numCentroids = 32;
        int numVectors = 256;
        float[][] centroids = randomCentroids(numCentroids, dim);
        List<float[]> vectors = randomVectors(numVectors, dim);
        FloatVectorValues values = FloatVectorValues.fromFloats(vectors, dim);
        int[] primary = primaryAssignment(vectors, centroids);

        SpannOverspill.Replicas replicas = SpannOverspill.assign(
            values,
            centroids,
            primary,
            1,
            64,
            LAMBDA,
            M,
            BEAM_WIDTH,
            VectorSimilarityFunction.EUCLIDEAN
        );
        for (int v = 0; v < numVectors; v++) {
            assertEquals("vector " + v + " should have a single replica", 1, replicas.centroidsPerVector()[v].length);
            assertEquals(primary[v], replicas.centroidsPerVector()[v][0]);
            assertEquals(1, replicas.sqDistPerVector()[v].length);
        }
    }

    /** Primary is always replica 0, counts never exceed replicaCount, replicas are distinct, distances are true. */
    public void testPrimaryFirstAndBounded() throws IOException {
        int dim = 16;
        int numCentroids = 64;
        int numVectors = 512;
        int replicaCount = randomIntBetween(2, 6);
        float[][] centroids = randomCentroids(numCentroids, dim);
        List<float[]> vectors = randomVectors(numVectors, dim);
        FloatVectorValues values = FloatVectorValues.fromFloats(vectors, dim);
        int[] primary = primaryAssignment(vectors, centroids);

        SpannOverspill.Replicas replicas = SpannOverspill.assign(
            values,
            centroids,
            primary,
            replicaCount,
            64,
            LAMBDA,
            M,
            BEAM_WIDTH,
            VectorSimilarityFunction.EUCLIDEAN
        );

        for (int v = 0; v < numVectors; v++) {
            int[] r = replicas.centroidsPerVector()[v];
            float[] d = replicas.sqDistPerVector()[v];
            assertTrue("at least the primary", r.length >= 1);
            assertTrue("bounded by replicaCount", r.length <= replicaCount);
            assertEquals("distances parallel to replicas", r.length, d.length);
            assertEquals("primary kept first", primary[v], r[0]);
            for (int i = 0; i < r.length; i++) {
                for (int j = i + 1; j < r.length; j++) {
                    assertNotEquals("replica centroids must be distinct", r[i], r[j]);
                }
                // distances are the (squared) distance to the assigned centroid
                assertEquals(ESVectorUtil.squareDistance(vectors.get(v), centroids[r[i]]), d[i], 1e-3f);
            }
        }
    }

    /**
     * Every kept secondary must satisfy the SRAIR gate: its AIR loss (with max-aggregation over the residuals
     * kept before it) is strictly below {@code (1+lambda) * ||r_primary||^2}. Recomputed from the returned
     * distances + inter-centroid geometry, exactly as {@link SpannOverspill#assign} does.
     */
    public void testKeptReplicasSatisfySrairGate() throws IOException {
        int dim = 16;
        int numCentroids = 64;
        int numVectors = 512;
        float[][] centroids = randomCentroids(numCentroids, dim);
        List<float[]> vectors = randomVectors(numVectors, dim);
        FloatVectorValues values = FloatVectorValues.fromFloats(vectors, dim);
        int[] primary = primaryAssignment(vectors, centroids);

        SpannOverspill.Replicas replicas = SpannOverspill.assign(
            values,
            centroids,
            primary,
            6,
            64,
            LAMBDA,
            M,
            BEAM_WIDTH,
            VectorSimilarityFunction.EUCLIDEAN
        );

        for (int v = 0; v < numVectors; v++) {
            int[] r = replicas.centroidsPerVector()[v];
            float[] d = replicas.sqDistPerVector()[v];
            float threshold = (1f + LAMBDA) * d[0]; // (1+lambda) * ||r_primary||^2
            for (int i = 1; i < r.length; i++) {
                float maxDot = -Float.MAX_VALUE;
                for (int k = 0; k < i; k++) {
                    float interSq = ESVectorUtil.squareDistance(centroids[r[k]], centroids[r[i]]);
                    float dot = 0.5f * (d[k] + d[i] - interSq); // r_k . r_i
                    maxDot = Math.max(maxDot, dot);
                }
                float airLoss = d[i] + LAMBDA * maxDot;
                assertTrue(
                    "kept replica " + i + " of vector " + v + " violates SRAIR gate: loss=" + airLoss + " threshold=" + threshold,
                    airLoss < threshold + 1e-3f
                );
            }
        }
    }

    /**
     * A vector sitting on a centroid (deep in its cell, every other centroid far away) gains nothing from a
     * secondary, so SRAIR keeps only the primary regardless of replicaCount.
     */
    public void testDeepVectorKeepsOnlyPrimary() throws IOException {
        int dim = 8;
        int numCentroids = 24;
        float spacing = 100f; // centroids far apart so non-primary candidates are far
        float[][] centroids = new float[numCentroids][dim];
        for (int c = 0; c < numCentroids; c++) {
            for (int d = 0; d < dim; d++) {
                centroids[c][d] = c * spacing + randomFloat();
            }
        }
        // vector sits exactly on centroid 7 -> primarySq ~ 0 -> SRAIR threshold ~ 0 -> no secondary can pass
        List<float[]> vectors = new ArrayList<>();
        vectors.add(centroids[7].clone());
        FloatVectorValues values = FloatVectorValues.fromFloats(vectors, dim);
        int[] primary = new int[] { 7 };

        SpannOverspill.Replicas replicas = SpannOverspill.assign(
            values,
            centroids,
            primary,
            6,
            64,
            LAMBDA,
            M,
            BEAM_WIDTH,
            VectorSimilarityFunction.EUCLIDEAN
        );
        assertEquals("deep vector must keep only its primary", 1, replicas.centroidsPerVector()[0].length);
        assertEquals(7, replicas.centroidsPerVector()[0][0]);
    }

    /**
     * A boundary vector lying between its primary and a neighbouring centroid is replicated into that neighbour,
     * and AIR selects the opposite-side neighbour (residual pointing opposite to the primary residual, i.e. a
     * negative dot product) — the centroid a boundary-crossing query would route to.
     */
    public void testBoundaryVectorGetsOppositeSideReplica() throws IOException {
        int dim = 8;
        int numCentroids = 20;
        float[][] centroids = new float[numCentroids][dim];
        // c0 and c1 straddle the vector along axis 0; all other centroids are pushed far away
        centroids[0][0] = 0f;
        centroids[1][0] = 10f;
        for (int c = 2; c < numCentroids; c++) {
            for (int d = 0; d < dim; d++) {
                centroids[c][d] = 500f + c * 50f + randomFloat();
            }
        }
        float[] x = new float[dim];
        x[0] = 4.5f; // nearest to c0 (dist 4.5), c1 is the opposite-side neighbour (dist 5.5)
        List<float[]> vectors = List.of(x);
        FloatVectorValues values = FloatVectorValues.fromFloats(vectors, dim);
        int[] primary = primaryAssignment(vectors, centroids);
        assertEquals("primary should be c0", 0, primary[0]);

        SpannOverspill.Replicas replicas = SpannOverspill.assign(
            values,
            centroids,
            primary,
            6,
            64,
            LAMBDA,
            M,
            BEAM_WIDTH,
            VectorSimilarityFunction.EUCLIDEAN
        );
        int[] r = replicas.centroidsPerVector()[0];
        assertTrue("boundary vector should get a secondary replica", r.length >= 2);
        assertEquals("primary first", 0, r[0]);
        assertEquals("secondary is the opposite-side neighbour c1", 1, r[1]);
        // r0 . r1 < 0 confirms AIR picked an inverse (opposite-direction) residual
        float interSq = ESVectorUtil.squareDistance(centroids[0], centroids[1]);
        float dot = 0.5f * (ESVectorUtil.squareDistance(centroids[0], x) + ESVectorUtil.squareDistance(centroids[1], x) - interSq);
        assertTrue("secondary residual should oppose the primary residual (dot < 0), got " + dot, dot < 0f);
    }

    /**
     * Under {@code MAXIMUM_INNER_PRODUCT} the assigner uses the SOAR (orthogonal-residual) rule instead of AIR.
     * Every kept secondary must still clear the shared SRAIR gate, recomputed with the SOAR penalty
     * {@code (r_k . r')^2 / ||r_k||^2}. Validates the MIP path runs and gates correctly.
     */
    public void testSoarGateUnderInnerProduct() throws IOException {
        int dim = 16;
        int numCentroids = 64;
        int numVectors = 512;
        float[][] centroids = randomCentroids(numCentroids, dim);
        List<float[]> vectors = randomVectors(numVectors, dim);
        FloatVectorValues values = FloatVectorValues.fromFloats(vectors, dim);
        int[] primary = primaryAssignment(vectors, centroids); // primary stays Euclidean-nearest

        SpannOverspill.Replicas replicas = SpannOverspill.assign(
            values,
            centroids,
            primary,
            6,
            64,
            LAMBDA,
            M,
            BEAM_WIDTH,
            VectorSimilarityFunction.MAXIMUM_INNER_PRODUCT
        );

        for (int v = 0; v < numVectors; v++) {
            int[] r = replicas.centroidsPerVector()[v];
            float[] d = replicas.sqDistPerVector()[v];
            float threshold = (1f + LAMBDA) * d[0];
            for (int i = 1; i < r.length; i++) {
                float maxPenalty = -Float.MAX_VALUE;
                for (int k = 0; k < i; k++) {
                    float interSq = ESVectorUtil.squareDistance(centroids[r[k]], centroids[r[i]]);
                    float dot = 0.5f * (d[k] + d[i] - interSq);
                    float penalty = d[k] > 1e-12f ? (dot * dot) / d[k] : 0f; // SOAR orthogonality penalty
                    maxPenalty = Math.max(maxPenalty, penalty);
                }
                float soarLoss = d[i] + LAMBDA * maxPenalty;
                assertTrue(
                    "SOAR replica " + i + " of vector " + v + " violates gate: loss=" + soarLoss + " threshold=" + threshold,
                    soarLoss < threshold + 1e-3f
                );
            }
        }
    }

    private static float[][] randomCentroids(int n, int dim) {
        float[][] centroids = new float[n][];
        for (int i = 0; i < n; i++) {
            centroids[i] = randomVector(dim);
        }
        return centroids;
    }

    private static List<float[]> randomVectors(int n, int dim) {
        List<float[]> vectors = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            vectors.add(randomVector(dim));
        }
        return vectors;
    }

    private static float[] randomVector(int dim) {
        float[] v = new float[dim];
        for (int d = 0; d < dim; d++) {
            v[d] = randomFloat() * 2 - 1;
        }
        return v;
    }
}
