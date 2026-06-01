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
 * Experiment-only SPANN-style multi-cluster assignment ("overspill"). Following SPANN, each vector is
 * assigned to up to {@code replicaCount} nearby centroids ("postings") instead of just one (the current
 * SOAR does a single secondary). The centroid HNSW graph is used as SPANN's <em>head index</em>: for each
 * vector we graph-search its nearest centroids and accept a diverse subset using the RNG rule
 * ({@code rngFactor * d(cand, kept) < d(vector, cand)} → reject), optionally bounded by a closure
 * condition ({@code d(vector, cand) <= (1+epsilon) * d(vector, primary)}). The primary (nearest) centroid
 * is always kept first. Membership trimming of overfull postings is applied later by the writer using the
 * returned per-replica distances.
 *
 * <p>Distances are squared L2 (matching the existing SOAR/neighbourhood code); for cosine/MIP the vectors
 * are unit-normalised so L2 ordering matches.
 */
public final class SpannOverspill {

    private SpannOverspill() {}

    /**
     * SPANN overspill knobs.
     *
     * @param replicaCount       max centroids a vector may be assigned to ({@code <=1} disables overspill)
     * @param internalResultNum  number of nearest centroids considered per vector (head-index search depth)
     * @param rngFactor          RNG diversity strength ({@code 1.0} = SPANN default)
     * @param closureEpsilon     closure bound; candidates with {@code d > (1+eps)*d(primary)} are dropped ({@code <0} disables)
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
     * Assigns up to {@code replicaCount} centroids per vector via centroid-graph search + RNG diversity.
     *
     * @param primary the primary (nearest) centroid per vector; always kept as replica 0
     */
    static Replicas assign(
        FloatVectorValues vectors,
        float[][] centroids,
        int[] primary,
        int replicaCount,
        int internalResultNum,
        float rngFactor,
        float closureEpsilon,
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
        // closureEpsilon < 0 disables the closure bound; otherwise (1+eps)^2 is applied to squared distances
        final boolean closureEnabled = closureEpsilon >= 0f;
        final float closureMultiplier = (1f + closureEpsilon) * (1f + closureEpsilon);
        final int[] kept = new int[replicaCount];
        final float[] keptDist = new float[replicaCount];
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
            final float closureThreshold = primarySq * closureMultiplier;
            for (int ci = 0; ci < candidates.length && count < replicaCount; ci++) {
                final int candidate = candidates[ci].doc;
                if (candidate == prim) {
                    continue;
                }
                final float candidateSq = ESVectorUtil.squareDistance(vector, centroids[candidate]);
                if (closureEnabled && candidateSq > closureThreshold) {
                    break; // candidates are nearest-first, so everything after is also outside the closure
                }
                boolean rngAccepted = true;
                for (int k = 0; k < count; k++) {
                    if (rngFactor * ESVectorUtil.squareDistance(centroids[candidate], centroids[kept[k]]) < candidateSq) {
                        rngAccepted = false; // too close to an already-kept centroid -> redundant direction
                        break;
                    }
                }
                if (rngAccepted) {
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
