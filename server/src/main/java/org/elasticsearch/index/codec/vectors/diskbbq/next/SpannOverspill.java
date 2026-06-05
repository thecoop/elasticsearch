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
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopKnnCollector;
import org.apache.lucene.util.VectorUtil;
import org.apache.lucene.util.hnsw.HnswGraphBuilder;
import org.apache.lucene.util.hnsw.HnswGraphSearcher;
import org.apache.lucene.util.hnsw.OnHeapHnswGraph;
import org.apache.lucene.util.hnsw.RandomVectorScorer;
import org.apache.lucene.util.hnsw.RandomVectorScorerSupplier;
import org.apache.lucene.util.hnsw.UpdateableRandomVectorScorer;
import org.elasticsearch.simdvec.ESVectorUtil;

import java.io.IOException;
import java.util.Arrays;

/**
 * Experiment-only SPANN-style multi-cluster assignment ("overspill"). Each vector is assigned to up to
 * {@code replicaCount} nearby centroids ("postings") instead of just one. The centroid HNSW graph is used as
 * SPANN's <em>head index</em>: we graph-search each vector's nearest centroids, then select secondaries with
 * the RAIRS <b>AIR</b> (Amplified Inverse Residual) metric gated by <b>SRAIR</b>. AIR is the Euclidean
 * counterpart to SOAR's orthogonal-residual rule (SOAR targets inner product): it prefers a secondary residual
 * pointing <em>opposite</em> to the primary residual — the far side of the vector — which is exactly where a
 * boundary query routes. SRAIR keeps a candidate only when its AIR loss beats re-using the primary, so
 * deep-in-cluster vectors stay single-assigned. Candidates are considered nearest-first and every one passing
 * the gate is kept (up to {@code replicaCount}); the primary (nearest) centroid is always kept first.
 * Membership trimming of overfull postings is applied later by the writer using the returned per-replica
 * distances.
 *
 * <p>Distances are squared L2 (matching the existing SOAR/neighbourhood code); for cosine/MIP the vectors
 * are unit-normalised so L2 ordering matches. See RAIRS (Yang &amp; Chen, SIGMOD 2026).
 */
public final class SpannOverspill {

    private SpannOverspill() {}

    /**
     * SPANN overspill knobs.
     *
     * @param replicaCount       max centroids a vector may be assigned to ({@code <=1} disables overspill)
     * @param internalResultNum  number of nearest centroids considered per vector (head-index search depth)
     * @param rngFactor          reused as the AIR amplification λ (RAIRS uses 0.5); larger favours opposite-side replicas
     * @param closureEpsilon     unused (AIR/SRAIR has no closure bound); retained for tester compatibility
     * @param maxPostingFactor   trim postings larger than {@code maxPostingFactor * vectorsPerCluster} ({@code <=0} disables)
     */
    public record Params(int replicaCount, int internalResultNum, float rngFactor, float closureEpsilon, float maxPostingFactor) {
        public static final Params DISABLED = new Params(1, 64, 1.0f, -1f, -1f);

        boolean enabled() {
            return replicaCount > 1;
        }
    }

    /** Per-vector replica assignments and the squared distance from the vector to each replica centroid. */
    record Replicas(int[][] centroidsPerVector, float[][] sqDistPerVector) {}

    /**
     * Assigns up to {@code replicaCount} centroids per vector via centroid-graph search + AIR/SRAIR selection
     * (Euclidean redundant assignment, RAIRS-style). For each near candidate {@code c'} (residual r' = c' - x)
     * the AIR loss {@code ||r'||^2 + lambda * max_i(r_i . r')} is computed against the already-kept residuals
     * r_i; the {@code +lambda r.r'} term favours a residual <em>opposite</em> to the kept ones (the far side of
     * x), which boundary queries route to. The SRAIR gate keeps a candidate only when its loss beats re-using
     * the primary ({@code (1+lambda)*||r_primary||^2}), so deep-in-cluster vectors stay single-assigned. The
     * dot {@code r_i . r'} is recovered from squared distances via
     * {@code r_i . r' = (||r_i||^2 + ||r'||^2 - ||c_i - c'||^2) / 2}, so no full-vector dot products are needed.
     *
     * @param primary the primary (nearest) centroid per vector; always kept as replica 0
     * @param lambda  AIR amplification (RAIRS uses 0.5); larger favours opposite-side replicas more strongly
     */
    static Replicas assign(
        FloatVectorValues vectors,
        float[][] centroids,
        int[] primary,
        int replicaCount,
        int internalResultNum,
        float lambda,
        int m,
        int beamWidth
    ) throws IOException {
        final int n = vectors.size();
        final int[][] replicas = new int[n][];
        final float[][] dists = new float[n][];
        if (replicaCount <= 1 || centroids.length <= 1) {
            // no overspill: every vector keeps just its primary
            for (int v = 0; v < n; v++) {
                final int prim = primary[v];
                replicas[v] = new int[] { prim };
                dists[v] = new float[] { ESVectorUtil.squareDistance(vectors.vectorValue(v), centroids[prim]) };
            }
            return new Replicas(replicas, dists);
        }
        final OnHeapHnswGraph graph = HnswGraphBuilder.create(new CentroidScorerSupplier(centroids), m, beamWidth, 42L, centroids.length)
            .build(centroids.length);
        final int[] kept = new int[replicaCount];
        final float[] keptDist = new float[replicaCount]; // ||r_i||^2 : squared distance from the vector to kept centroid i
        for (int v = 0; v < n; v++) {
            final float[] vector = vectors.vectorValue(v);
            final int prim = primary[v];
            final float primarySq = ESVectorUtil.squareDistance(vector, centroids[prim]);
            int count = 0;
            kept[count] = prim;
            keptDist[count] = primarySq;
            count++;
            // SPANN head-index search: nearest centroids to this vector, nearest first
            final RandomVectorScorer scorer = queryScorer(vector, centroids);
            final TopKnnCollector collector = new TopKnnCollector(internalResultNum, Integer.MAX_VALUE);
            HnswGraphSearcher.search(scorer, collector, graph, null);
            final ScoreDoc[] candidates = collector.topDocs().scoreDocs;
            // SRAIR gate: a secondary is only worthwhile if its AIR loss beats re-using the primary.
            final float srairThreshold = (1f + lambda) * primarySq;
            for (int ci = 0; ci < candidates.length && count < replicaCount; ci++) {
                final int candidate = candidates[ci].doc;
                if (candidate == prim) {
                    continue;
                }
                final float candidateSq = 1f / candidates[ci].score - 1f; // ||r'||^2, inverting the normalized score
                // AIR with max-aggregation: penalise alignment with the most-aligned already-kept residual.
                float maxDot = -Float.MAX_VALUE;
                for (int k = 0; k < count; k++) {
                    final float interSq = ESVectorUtil.squareDistance(centroids[kept[k]], centroids[candidate]);
                    final float dot = 0.5f * (keptDist[k] + candidateSq - interSq); // r_k . r'
                    if (dot > maxDot) {
                        maxDot = dot;
                    }
                }
                final float airLoss = candidateSq + lambda * maxDot;
                if (airLoss < srairThreshold) {
                    kept[count] = candidate;
                    keptDist[count] = candidateSq;
                    count++;
                }
            }
            replicas[v] = Arrays.copyOf(kept, count);
            dists[v] = Arrays.copyOf(keptDist, count);
        }
        return new Replicas(replicas, dists);
    }

    /** Scorer for a fixed query vector against the centroids (higher score == nearer). */
    private static RandomVectorScorer queryScorer(float[] query, float[][] centroids) {
        return new RandomVectorScorer() {
            @Override
            public float score(int node) {
                return VectorUtil.normalizeDistanceToUnitInterval(ESVectorUtil.squareDistance(query, centroids[node]));
            }

            @Override
            public int maxOrd() {
                return centroids.length;
            }
        };
    }

    /** Centroid-vs-centroid scorer supplier used to build the head-index graph over the centroids. */
    private record CentroidScorerSupplier(float[][] centroids) implements RandomVectorScorerSupplier {
        @Override
        public UpdateableRandomVectorScorer scorer() {
            return new UpdateableRandomVectorScorer() {
                private int ordinal;

                @Override
                public void setScoringOrdinal(int node) {
                    this.ordinal = node;
                }

                @Override
                public float score(int node) {
                    return VectorUtil.normalizeDistanceToUnitInterval(ESVectorUtil.squareDistance(centroids[ordinal], centroids[node]));
                }

                @Override
                public int maxOrd() {
                    return centroids.length;
                }
            };
        }

        @Override
        public RandomVectorScorerSupplier copy() {
            return new CentroidScorerSupplier(centroids);
        }
    }
}
