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
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopKnnCollector;
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
 * SPANN's <em>head index</em>: we graph-search each vector's nearest centroids <em>under the configured
 * similarity</em> (so candidates are the centroids a query would actually route to), then select secondaries
 * with a residual rule gated by <b>SRAIR</b>. The residual rule follows the scoring metric: <b>AIR</b>
 * (Amplified Inverse Residual, RAIRS) for Euclidean/cosine — prefers a secondary residual pointing
 * <em>opposite</em> the primary residual (the far side of the vector, where a boundary query routes); and
 * <b>SOAR</b> (orthogonal residual, ScaNN) for {@code MAXIMUM_INNER_PRODUCT} — prefers a residual orthogonal
 * to the kept ones. Both gate on the same {@code (1+lambda)*||r_primary||^2} (re-using the primary), so
 * deep-in-cluster vectors stay single-assigned. Residuals are always Euclidean ({@code r = c - x}; primary
 * assignment and quantization stay L2); only routing and the residual penalty track the configured metric.
 * Candidates are considered nearest-first and every one passing the gate is kept (up to {@code replicaCount});
 * the primary (nearest) centroid is always kept first. Membership trimming of overfull postings is applied
 * later by the writer using the returned per-replica distances.
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
     * @param primary    the primary (nearest) centroid per vector; always kept as replica 0
     * @param lambda     residual amplification (RAIRS/ScaNN use ~0.5)
     * @param similarity the configured query similarity; routes candidate search and selects AIR (Euclidean/
     *                   cosine) vs SOAR ({@code MAXIMUM_INNER_PRODUCT}). Residuals stay Euclidean regardless.
     */
    static Replicas assign(
        FloatVectorValues vectors,
        float[][] centroids,
        int[] primary,
        int replicaCount,
        int internalResultNum,
        float lambda,
        int m,
        int beamWidth,
        VectorSimilarityFunction similarity
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
        // The candidate head-index is built and searched under the CONFIGURED similarity, so the candidates are
        // the centroids a query would route to. SOAR (orthogonal residual) is the inner-product rule; AIR
        // (inverse residual) is the Euclidean/cosine rule. Residuals themselves are always Euclidean.
        final boolean useSoar = similarity == VectorSimilarityFunction.MAXIMUM_INNER_PRODUCT;
        // TODO don't build the dang graph again...
        final OnHeapHnswGraph graph = HnswGraphBuilder.create(
            new CentroidScorerSupplier(centroids, similarity),
            m,
            beamWidth,
            42L,
            centroids.length
        ).build(centroids.length);
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
            // head-index search: the centroids this vector (≈ a query near it) routes to under the similarity
            final RandomVectorScorer scorer = queryScorer(vector, centroids, similarity);
            final TopKnnCollector collector = new TopKnnCollector(internalResultNum, Integer.MAX_VALUE);
            HnswGraphSearcher.search(scorer, collector, graph, null);
            final ScoreDoc[] candidates = collector.topDocs().scoreDocs;
            // SRAIR gate: a secondary is only worthwhile if its loss beats re-using the primary. Evaluated at
            // c'=c_primary both AIR and SOAR give (1+lambda)*||r_primary||^2, so the threshold is shared.
            final float srairThreshold = (1f + lambda) * primarySq;
            for (int ci = 0; ci < candidates.length && count < replicaCount; ci++) {
                final int candidate = candidates[ci].doc;
                if (candidate == prim) {
                    continue;
                }
                // Euclidean residual magnitude ||r'||^2, computed directly (the graph score is now the configured
                // similarity, not a recoverable L2 distance).
                final float candidateSq = ESVectorUtil.squareDistance(vector, centroids[candidate]);
                // max-aggregate the residual penalty against the most-aligned already-kept residual.
                // r_k . r' = (||r_k||^2 + ||r'||^2 - ||c_k - c'||^2) / 2
                float maxPenalty = -Float.MAX_VALUE;
                for (int k = 0; k < count; k++) {
                    final float interSq = ESVectorUtil.squareDistance(centroids[kept[k]], centroids[candidate]);
                    final float dot = 0.5f * (keptDist[k] + candidateSq - interSq);
                    // AIR penalises same-direction (large dot); SOAR penalises non-orthogonality ((dot/||r_k||)^2)
                    final float penalty = useSoar ? (keptDist[k] > 1e-12f ? (dot * dot) / keptDist[k] : 0f) : dot;
                    if (penalty > maxPenalty) {
                        maxPenalty = penalty;
                    }
                }
                final float loss = candidateSq + lambda * maxPenalty;
                if (loss < srairThreshold) {
                    kept[count] = candidate;
                    keptDist[count] = candidateSq;
                    count++;
                }
            }
            replicas[v] = Arrays.copyOf(kept, count);
            dists[v] = Arrays.copyOf(keptDist, count);
        }
        // TODO note, we only trim the replicas.
        return new Replicas(replicas, dists);
    }

    /** Scorer for a fixed query vector against the centroids under the configured similarity (higher == nearer). */
    private static RandomVectorScorer queryScorer(float[] query, float[][] centroids, VectorSimilarityFunction similarity) {
        return new RandomVectorScorer() {
            @Override
            public float score(int node) {
                return similarity.compare(query, centroids[node]);
            }

            @Override
            public int maxOrd() {
                return centroids.length;
            }
        };
    }

    /** Centroid-vs-centroid scorer supplier used to build the head-index graph under the configured similarity. */
    private record CentroidScorerSupplier(float[][] centroids, VectorSimilarityFunction similarity) implements RandomVectorScorerSupplier {
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
                    return similarity.compare(centroids[ordinal], centroids[node]);
                }

                @Override
                public int maxOrd() {
                    return centroids.length;
                }
            };
        }

        @Override
        public RandomVectorScorerSupplier copy() {
            return new CentroidScorerSupplier(centroids, similarity);
        }
    }
}
