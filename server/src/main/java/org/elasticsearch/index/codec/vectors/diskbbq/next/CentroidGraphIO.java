/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.codec.vectors.diskbbq.next;

import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.ByteBuffersDataOutput;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.hnsw.HnswGraph;
import org.apache.lucene.util.hnsw.RandomVectorScorer;
import org.elasticsearch.index.codec.vectors.OptimizedScalarQuantizer;
import org.elasticsearch.index.codec.vectors.cluster.NeighborHood;
import org.elasticsearch.simdvec.ES92Int7VectorsScorer;
import org.elasticsearch.simdvec.ESVectorUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Experiment-only helper that indexes the IVF centroids into an HNSW graph and serializes / reads it
 * back from the centroid ({@code .cenivf}) file.
 *
 * <p>Centroids are stored <em>flat</em> as 7-bit optimized-scalar-quantized vectors, each record
 * laid out contiguously as {@code [dim bytes][lowerInterval f32][upperInterval f32]
 * [additionalCorrection f32][quantizedComponentSum i32]} so a single centroid can be scored
 * individually via {@link ES92Int7VectorsScorer#score}. This is the same int7 quantization and
 * scoring the brute-force centroid path already uses, so build and search scoring are consistent and
 * fully portable (Panama-accelerated where available, no native dependency).
 *
 * <p>The serialized graph layout (within its own slice of {@code .cenivf}) mirrors Lucene's HNSW
 * on-disk format but keeps the per-node neighbour offset table inline as a plain {@code long[]} so
 * the whole structure is self-contained in a single write phase:
 *
 * <pre>
 * [VInt maxConn][VInt numLevels][VInt entryNode][VInt size]
 * for level in 1..numLevels-1: [VInt numNodes][delta-encoded sorted node ids ...]
 * [long offset table: totalNodes entries]   // start of each node's neighbour list, in iteration order
 * [neighbour data: for each node (level 0 ords 0..size-1, then each upper level sorted):
 *     [VInt arcCount][delta-encoded sorted neighbour ords ...] ]
 * </pre>
 */
// TODO this uses a metric ton of heap...can we make it cheaper?
final class CentroidGraphIO {

    private CentroidGraphIO() {}

    // Scratch size for the per-record scorers. ES92Int7VectorsScorer#score reads 3 corrective floats into
    // its bulk scratch arrays, so this must be at least 3; we only ever score one record at a time.
    private static final int SCORER_SCRATCH_SIZE = 16;
    // Per-node candidate cap when building an upper level's adjacency from a brute-force kNN over that
    // level's (small) node set; pruned down to maxConn diverse links.
    private static final int LEVEL_NEIGHBOR_CANDIDATES = 64;

    /**
     * A multi-level HNSW adjacency over the centroids, ready to serialize. Level 0 contains all nodes
     * (so {@code nodesByLevel[0]} is {@code null}, implicitly {@code 0..size-1}); upper levels list the
     * promoted node ids (sorted). {@code neighborsByLevel[level][i]} are the global-ordinal neighbours of
     * the i-th node on that level (for level 0, i is the centroid ordinal).
     */
    record MultiLevelAdjacency(int maxConn, int numLevels, int entryNode, int size, int[][] nodesByLevel, int[][][] neighborsByLevel) {}

    /** Number of bytes used to store a single 7-bit quantized centroid plus its corrective terms. */
    static int flatRecordSize(int dimension) {
        return dimension + 3 * Float.BYTES + Integer.BYTES;
    }

    /**
     * Builds a single-level navigable graph directly from the precomputed per-centroid nearest-neighbour
     * lists (see {@link NeighborHood}), avoiding any per-node graph search during construction.
     * Each node's neighbours come from diversity-pruning (RNG rule) its kNN list to {@code maxConn} links;
     * the result is then symmetrised and re-pruned so every node stays within {@code maxConn} and has
     * in-links. Distances use the raw float centroids.
     *
     * @param neighborhoods per-centroid nearest-neighbour lists (nearest first); entries may contain stale
     *                      ids from empty-cluster remapping, which are filtered out
     * @return adjacency lists per centroid ordinal
     */
    static int[][] buildAdjacencyFromNeighborhoods(
        NeighborHood[] neighborhoods,
        float[][] centroids,
        int maxConn,
        VectorSimilarityFunction similarity
    ) {
        final int n = centroids.length;
        @SuppressWarnings("unchecked")
        final List<Integer>[] adjacency = new List[n];
        for (int c = 0; c < n; c++) {
            adjacency[c] = new ArrayList<>(maxConn);
        }
        // directed diversity-pruned neighbours from each node's kNN list
        for (int c = 0; c < n; c++) {
            final NeighborHood neighborHood = neighborhoods[c];
            final int[] candidates = neighborHood == null ? new int[0] : neighborHood.neighbors();
            final int[] kept = diversePruneSorted(c, candidates, centroids, maxConn, similarity);
            for (int neighbour : kept) {
                addUnique(adjacency[c], neighbour);
                addUnique(adjacency[neighbour], c); // symmetrise so neighbours are reachable both ways
            }
        }
        // bound each node's degree, re-pruning by diversity when symmetrisation pushed it over maxConn
        final int[][] result = new int[n][];
        for (int c = 0; c < n; c++) {
            final List<Integer> list = adjacency[c];
            if (list.size() <= maxConn) {
                result[c] = toIntArray(list);
            } else {
                final int[] candidates = toIntArray(list);
                sortByDistanceTo(c, candidates, centroids, similarity);
                result[c] = diversePruneSorted(c, candidates, centroids, maxConn, similarity);
            }
        }
        return result;
    }

    /**
     * Builds a multi-level HNSW adjacency over the centroids. Level 0 reuses the (already computed)
     * neighbourhoods via {@link #buildAdjacencyFromNeighborhoods}; each centroid is then assigned an HNSW
     * level (geometric, {@code mL = 1/ln(maxConn)}), and each upper level's adjacency is built by a cheap
     * brute-force kNN + diversity prune over just that level's (small, geometrically shrinking) node set.
     * No per-node graph search is performed. Upper levels give the logarithmic "highway" hops that a flat
     * graph lacks, which matters once there are many centroids.
     *
     * @param neighborhoods level-0 nearest-neighbour lists (may be {@code null} only when {@code size <= 1})
     * @param seed seed for the (deterministic) level assignment
     */
    static MultiLevelAdjacency buildMultiLevelFromNeighborhoods(
        NeighborHood[] neighborhoods,
        float[][] centroids,
        int maxConn,
        long seed,
        VectorSimilarityFunction similarity
    ) throws IOException {
        final int n = centroids.length;
        if (n <= 1) {
            final int[][] level0 = new int[n][];
            for (int i = 0; i < n; i++) {
                level0[i] = new int[0];
            }
            return new MultiLevelAdjacency(maxConn, 1, 0, n, new int[1][], new int[][][] { level0 });
        }
        final int[][] level0 = buildAdjacencyFromNeighborhoods(neighborhoods, centroids, maxConn, similarity);
        // assign levels (standard HNSW geometric distribution)
        final double mL = 1.0 / Math.log(Math.max(maxConn, 2));
        final Random random = new Random(seed);
        final int[] levelOf = new int[n];
        int maxLevel = 0;
        for (int c = 0; c < n; c++) {
            final double u = Math.max(random.nextDouble(), 1e-30);
            final int level = (int) Math.floor(-Math.log(u) * mL);
            levelOf[c] = level;
            if (level > maxLevel) {
                maxLevel = level;
            }
        }
        final int numLevels = maxLevel + 1;
        final int[][] nodesByLevel = new int[numLevels][];
        final int[][][] neighborsByLevel = new int[numLevels][][];
        neighborsByLevel[0] = level0; // nodesByLevel[0] stays null (implicitly all nodes)
        for (int level = 1; level < numLevels; level++) {
            int count = 0;
            for (int c = 0; c < n; c++) {
                if (levelOf[c] >= level) {
                    count++;
                }
            }
            final int[] nodes = new int[count];
            int w = 0;
            for (int c = 0; c < n; c++) {
                if (levelOf[c] >= level) {
                    nodes[w++] = c; // ascending c => already sorted
                }
            }
            nodesByLevel[level] = nodes;
            neighborsByLevel[level] = buildLevelAdjacency(nodes, centroids, maxConn, similarity);
        }
        final int entryNode = numLevels > 1 ? nodesByLevel[numLevels - 1][0] : 0;
        return new MultiLevelAdjacency(maxConn, numLevels, entryNode, n, nodesByLevel, neighborsByLevel);
    }

    /**
     * Builds the adjacency among the given (small) set of upper-level nodes via a brute-force kNN over the
     * subset and the same diversity prune used for level 0. Returns neighbours as global centroid ordinals.
     */
    private static int[][] buildLevelAdjacency(int[] levelNodes, float[][] centroids, int maxConn, VectorSimilarityFunction similarity)
        throws IOException {
        final int m = levelNodes.length;
        if (m == 1) {
            return new int[][] { new int[0] };
        }
        final float[][] sub = new float[m][];
        for (int i = 0; i < m; i++) {
            sub[i] = centroids[levelNodes[i]];
        }
        // The candidate pool is a Euclidean kNN over this level's nodes; the diversity prune below selects the
        // edges under the configured similarity (identical to Euclidean for normalized cosine/euclidean).
        final int candidates = Math.min(m - 1, LEVEL_NEIGHBOR_CANDIDATES);
        final NeighborHood[] subNeighborhoods = NeighborHood.computeNeighborhoods(sub, candidates);
        final int[][] localAdjacency = buildAdjacencyFromNeighborhoods(subNeighborhoods, sub, maxConn, similarity);
        final int[][] globalAdjacency = new int[m][];
        for (int i = 0; i < m; i++) {
            final int[] local = localAdjacency[i];
            final int[] global = new int[local.length];
            for (int k = 0; k < local.length; k++) {
                global[k] = levelNodes[local[k]];
            }
            globalAdjacency[i] = global;
        }
        return globalAdjacency;
    }

    /**
     * RNG diversity prune: walk {@code candidates} (nearest-first w.r.t. {@code node}) and keep a candidate
     * only if it is closer to {@code node} than to every already-kept neighbour. Skips self, out-of-range
     * and duplicate ids (which can appear after empty-cluster remapping).
     */
    private static int[] diversePruneSorted(int node, int[] candidates, float[][] centroids, int maxConn, VectorSimilarityFunction sim) {
        final int[] kept = new int[maxConn];
        int keptCount = 0;
        for (int i = 0; i < candidates.length && keptCount < maxConn; i++) {
            final int candidate = candidates[i];
            if (candidate == node || candidate < 0 || candidate >= centroids.length) {
                continue;
            }
            boolean duplicate = false;
            for (int k = 0; k < keptCount; k++) {
                if (kept[k] == candidate) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) {
                continue;
            }
            final float distToNode = dist(sim, centroids[node], centroids[candidate]);
            boolean keep = true;
            for (int k = 0; k < keptCount; k++) {
                if (dist(sim, centroids[candidate], centroids[kept[k]]) < distToNode) {
                    keep = false;
                    break;
                }
            }
            if (keep) {
                kept[keptCount++] = candidate;
            }
        }
        return Arrays.copyOf(kept, keptCount);
    }

    private static void sortByDistanceTo(int node, int[] candidates, float[][] centroids, VectorSimilarityFunction sim) {
        final Integer[] boxed = new Integer[candidates.length];
        for (int i = 0; i < candidates.length; i++) {
            boxed[i] = candidates[i];
        }
        Arrays.sort(boxed, (a, b) -> Float.compare(dist(sim, centroids[node], centroids[a]), dist(sim, centroids[node], centroids[b])));
        for (int i = 0; i < candidates.length; i++) {
            candidates[i] = boxed[i];
        }
    }

    /**
     * A distance under the configured similarity (lower == closer), so the RNG diversity prune and sort work
     * for any metric. {@link VectorSimilarityFunction#compare} returns higher-is-closer, so we negate it. For
     * normalized cosine/euclidean this induces the same ordering as squared L2, so the resulting edges are
     * identical to the previous Euclidean build; for {@code MAXIMUM_INNER_PRODUCT} it makes edge selection
     * inner-product-aware.
     */
    private static float dist(VectorSimilarityFunction sim, float[] a, float[] b) {
        return -sim.compare(a, b);
    }

    private static void addUnique(List<Integer> list, int value) {
        if (list.contains(value) == false) {
            list.add(value);
        }
    }

    private static int[] toIntArray(List<Integer> list) {
        final int[] array = new int[list.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = list.get(i);
        }
        return array;
    }

    /** Serializes a {@link MultiLevelAdjacency} into {@code out} (see the class javadoc for the layout). */
    static void writeMultiLevelGraph(IndexOutput out, MultiLevelAdjacency graph) throws IOException {
        final int size = graph.size();
        final int numLevels = graph.numLevels();
        out.writeVInt(graph.maxConn());
        out.writeVInt(numLevels);
        out.writeVInt(graph.entryNode());
        out.writeVInt(size);
        int totalNodes = size;
        for (int level = 1; level < numLevels; level++) {
            final int[] nodes = graph.nodesByLevel()[level];
            totalNodes += nodes.length;
            out.writeVInt(nodes.length);
            for (int i = 0; i < nodes.length; i++) {
                out.writeVInt(i == 0 ? nodes[0] : nodes[i] - nodes[i - 1]); // delta-encoded, sorted
            }
        }
        // neighbour data, ordered: all level-0 nodes (0..size-1), then each upper level's sorted nodes
        final ByteBuffersDataOutput neighbours = new ByteBuffersDataOutput();
        final long[] offsets = new long[totalNodes];
        int offsetIdx = 0;
        for (int level = 0; level < numLevels; level++) {
            final int[][] levelAdjacency = graph.neighborsByLevel()[level];
            final int levelSize = level == 0 ? size : graph.nodesByLevel()[level].length;
            for (int i = 0; i < levelSize; i++) {
                offsets[offsetIdx++] = neighbours.size();
                final int[] nb = levelAdjacency[i];
                Arrays.sort(nb);
                neighbours.writeVInt(nb.length);
                for (int j = 0; j < nb.length; j++) {
                    neighbours.writeVInt(j == 0 ? nb[0] : nb[j] - nb[j - 1]);
                }
            }
        }
        for (long offset : offsets) {
            out.writeLong(offset);
        }
        out.copyBytes(neighbours.toDataInput(), neighbours.size());
    }

    /** Reads a graph previously written by {@link #writeMultiLevelGraph} from {@code graphSlice}. */
    static HnswGraph readGraph(IndexInput graphSlice) throws IOException {
        graphSlice.seek(0);
        final int maxConn = graphSlice.readVInt();
        final int numLevels = graphSlice.readVInt();
        final int entryNode = graphSlice.readVInt();
        final int size = graphSlice.readVInt();
        final int[][] nodesByLevel = new int[numLevels][];
        int totalNodes = size;
        for (int level = 1; level < numLevels; level++) {
            final int numNodes = graphSlice.readVInt();
            final int[] nodeIds = new int[numNodes];
            int previous = 0;
            for (int i = 0; i < numNodes; i++) {
                previous += graphSlice.readVInt();
                nodeIds[i] = previous;
            }
            nodesByLevel[level] = nodeIds;
            totalNodes += numNodes;
        }
        final long[] offsets = new long[totalNodes];
        for (int i = 0; i < totalNodes; i++) {
            offsets[i] = graphSlice.readLong();
        }
        final long neighbourDataStart = graphSlice.getFilePointer();
        final IndexInput neighbourData = graphSlice.slice(
            "centroid-graph-neighbours",
            neighbourDataStart,
            graphSlice.length() - neighbourDataStart
        );
        return new CentroidHnswGraph(neighbourData, nodesByLevel, offsets, size, numLevels, entryNode, maxConn);
    }

    /** Creates a query-vs-centroid scorer over the flat centroid records for graph search. */
    static RandomVectorScorer searchScorer(
        IndexInput flatCentroids,
        int numCentroids,
        int dimension,
        VectorSimilarityFunction similarityFunction,
        float globalCentroidDp,
        byte[] quantizedQuery,
        OptimizedScalarQuantizer.QuantizationResult queryCorrections
    ) throws IOException {
        final ES92Int7VectorsScorer scorer = ESVectorUtil.getES92Int7VectorsScorer(flatCentroids, dimension, SCORER_SCRATCH_SIZE);
        final int recordSize = flatRecordSize(dimension);
        return new RandomVectorScorer() {
            @Override
            public float score(int node) throws IOException {
                flatCentroids.seek((long) node * recordSize);
                return scorer.score(
                    quantizedQuery,
                    queryCorrections.lowerInterval(),
                    queryCorrections.upperInterval(),
                    queryCorrections.quantizedComponentSum(),
                    queryCorrections.additionalCorrection(),
                    similarityFunction,
                    globalCentroidDp
                );
            }

            @Override
            public int maxOrd() {
                return numCentroids;
            }
        };
    }

    /** Off-heap {@link HnswGraph} reading neighbour lists from a slice of the centroid file. */
    private static final class CentroidHnswGraph extends HnswGraph {
        private final IndexInput neighbourData;
        private final int[][] nodesByLevel;
        private final long[] offsets;
        private final long[] levelIndexOffsets;
        private final int size;
        private final int numLevels;
        private final int entryNode;
        private final int maxConn;
        private final int[] currentNeighbours;
        private int arcCount;
        private int arcUpTo;

        CentroidHnswGraph(
            IndexInput neighbourData,
            int[][] nodesByLevel,
            long[] offsets,
            int size,
            int numLevels,
            int entryNode,
            int maxConn
        ) {
            this.neighbourData = neighbourData;
            this.nodesByLevel = nodesByLevel;
            this.offsets = offsets;
            this.size = size;
            this.numLevels = numLevels;
            this.entryNode = entryNode;
            this.maxConn = maxConn;
            this.currentNeighbours = new int[maxConn * 2];
            this.levelIndexOffsets = new long[numLevels];
            for (int level = 1; level < numLevels; level++) {
                int lowerCount = nodesByLevel[level - 1] == null ? size : nodesByLevel[level - 1].length;
                levelIndexOffsets[level] = levelIndexOffsets[level - 1] + lowerCount;
            }
        }

        @Override
        public void seek(int level, int target) throws IOException {
            final int index = level == 0 ? target : Arrays.binarySearch(nodesByLevel[level], 0, nodesByLevel[level].length, target);
            assert index >= 0 : "seek level=" + level + " target=" + target + " not found";
            neighbourData.seek(offsets[index + (int) levelIndexOffsets[level]]);
            arcCount = neighbourData.readVInt();
            assert arcCount <= currentNeighbours.length : "too many neighbours: " + arcCount;
            int sum = 0;
            for (int i = 0; i < arcCount; i++) {
                sum += neighbourData.readVInt();
                currentNeighbours[i] = sum;
            }
            arcUpTo = 0;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public int nextNeighbor() {
            if (arcUpTo >= arcCount) {
                return DocIdSetIterator.NO_MORE_DOCS;
            }
            return currentNeighbours[arcUpTo++];
        }

        @Override
        public int numLevels() {
            return numLevels;
        }

        @Override
        public int maxConn() {
            return maxConn;
        }

        @Override
        public int entryNode() {
            return entryNode;
        }

        @Override
        public int neighborCount() {
            return arcCount;
        }

        @Override
        public NodesIterator getNodesOnLevel(int level) {
            if (level == 0) {
                return new DenseNodesIterator(size);
            }
            return new ArrayNodesIterator(nodesByLevel[level], nodesByLevel[level].length);
        }
    }
}
