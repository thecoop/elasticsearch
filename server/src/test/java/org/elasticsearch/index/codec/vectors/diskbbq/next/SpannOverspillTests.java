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
import org.elasticsearch.simdvec.ESVectorUtil;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Unit tests for the experiment-only SPANN multi-replica overspill assigner. */
public class SpannOverspillTests extends ESTestCase {

    private static final int M = 16;
    private static final int BEAM_WIDTH = 100;

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

        SpannOverspill.Replicas replicas = SpannOverspill.assign(values, centroids, primary, 1, 64, 1.0f, -1f, M, BEAM_WIDTH);
        for (int v = 0; v < numVectors; v++) {
            assertEquals("vector " + v + " should have a single replica", 1, replicas.centroidsPerVector()[v].length);
            assertEquals(primary[v], replicas.centroidsPerVector()[v][0]);
            assertEquals(1, replicas.sqDistPerVector()[v].length);
        }
    }

    /** Primary is always replica 0, counts never exceed replicaCount, and replicas are distinct. */
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
            1.0f,
            -1f, // closure disabled
            M,
            BEAM_WIDTH
        );

        for (int v = 0; v < numVectors; v++) {
            int[] r = replicas.centroidsPerVector()[v];
            float[] d = replicas.sqDistPerVector()[v];
            assertTrue("at least the primary", r.length >= 1);
            assertTrue("bounded by replicaCount", r.length <= replicaCount);
            assertEquals("distances parallel to replicas", r.length, d.length);
            assertEquals("primary kept first", primary[v], r[0]);
            // distinct centroids
            for (int i = 0; i < r.length; i++) {
                for (int j = i + 1; j < r.length; j++) {
                    assertNotEquals("replica centroids must be distinct", r[i], r[j]);
                }
                // distances are the true squared distance to the assigned centroid
                assertEquals(ESVectorUtil.squareDistance(vectors.get(v), centroids[r[i]]), d[i], 1e-3f);
            }
        }
    }

    /** A tight closure bound must keep every secondary replica within (1+eps)^2 of the primary distance. */
    public void testClosureBoundRespected() throws IOException {
        int dim = 16;
        int numCentroids = 64;
        int numVectors = 512;
        float epsilon = 0.1f;
        float[][] centroids = randomCentroids(numCentroids, dim);
        List<float[]> vectors = randomVectors(numVectors, dim);
        FloatVectorValues values = FloatVectorValues.fromFloats(vectors, dim);
        int[] primary = primaryAssignment(vectors, centroids);

        SpannOverspill.Replicas replicas = SpannOverspill.assign(values, centroids, primary, 6, 64, 1.0f, epsilon, M, BEAM_WIDTH);

        float multiplier = (1f + epsilon) * (1f + epsilon);
        for (int v = 0; v < numVectors; v++) {
            float[] d = replicas.sqDistPerVector()[v];
            float primarySq = d[0];
            for (int i = 1; i < d.length; i++) {
                assertTrue(
                    "replica " + i + " of vector " + v + " (" + d[i] + ") exceeds closure bound " + (primarySq * multiplier),
                    d[i] <= primarySq * multiplier + 1e-3f
                );
            }
        }
    }

    /**
     * The RNG rule mirrors Vamana's robust-prune ({@code factor * d(cand, kept) < d(vector, cand)} → reject):
     * a larger factor relaxes pruning, so it never produces fewer replicas than a smaller one.
     */
    public void testRngFactorMonotonicity() throws IOException {
        int dim = 16;
        int numCentroids = 64;
        int numVectors = 512;
        float[][] centroids = randomCentroids(numCentroids, dim);
        List<float[]> vectors = randomVectors(numVectors, dim);
        FloatVectorValues values = FloatVectorValues.fromFloats(vectors, dim);
        int[] primary = primaryAssignment(vectors, centroids);

        SpannOverspill.Replicas strict = SpannOverspill.assign(values, centroids, primary, 6, 64, 1.0f, -1f, M, BEAM_WIDTH);
        SpannOverspill.Replicas loose = SpannOverspill.assign(values, centroids, primary, 6, 64, 10.0f, -1f, M, BEAM_WIDTH);

        long looseTotal = 0;
        long strictTotal = 0;
        for (int v = 0; v < numVectors; v++) {
            looseTotal += loose.centroidsPerVector()[v].length;
            strictTotal += strict.centroidsPerVector()[v].length;
            assertTrue(
                "looser RNG should not drop replicas for vector " + v,
                loose.centroidsPerVector()[v].length >= strict.centroidsPerVector()[v].length
            );
        }
        // overspill should actually be happening with the loose factor
        assertTrue("loose RNG should assign some secondary replicas", looseTotal > numVectors);
        assertTrue(strictTotal <= looseTotal);
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
