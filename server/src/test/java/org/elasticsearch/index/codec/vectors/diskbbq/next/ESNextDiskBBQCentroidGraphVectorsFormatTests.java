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
import org.apache.lucene.search.TopKnnCollector;
import org.apache.lucene.store.Directory;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper;
import org.junit.Before;

import java.io.IOException;

/**
 * Runs the full {@link ESNextDiskBBQVectorsFormatTests} suite against the experiment-only centroid
 * HNSW graph path (see {@link ESNextDiskBBQVectorsFormat}). {@code getCodec()} reads the {@code format}
 * field lazily at test time, so reassigning it after {@code super.setUp()} forces every inherited test
 * to exercise the graph build/search code.
 */
public class ESNextDiskBBQCentroidGraphVectorsFormatTests extends ESNextDiskBBQVectorsFormatTests {

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        ESNextDiskBBQVectorsFormat.QuantEncoding encoding = ESNextDiskBBQVectorsFormat.QuantEncoding.values()[random().nextInt(
            ESNextDiskBBQVectorsFormat.QuantEncoding.values().length
        )];
        // small clusters + flat disabled so several centroids (and a real graph) are produced
        format = new ESNextDiskBBQVectorsFormat(
            encoding,
            ESNextDiskBBQVectorsFormat.MIN_VECTORS_PER_CLUSTER,
            random().nextInt(
                ESNextDiskBBQVectorsFormat.MIN_CENTROIDS_PER_PARENT_CLUSTER,
                ESNextDiskBBQVectorsFormat.MAX_CENTROIDS_PER_PARENT_CLUSTER
            ),
            DenseVectorFieldMapper.ElementType.FLOAT,
            false,
            null,
            1,
            false,
            ESNextDiskBBQVectorsFormat.DEFAULT_PRECONDITIONING_BLOCK_DIMENSION,
            0, // disable the flat-vector threshold to force clustering
            null,
            null,
            null,
            true, // indexCentroidsInGraph
            random().nextInt(8, 32), // M
            random().nextInt(40, 120), // beamWidth
            random().nextBoolean() ? -1 : random().nextInt(8, 64), // efSearch
            random().nextBoolean() ? SpannOverspill.Params.DISABLED : new SpannOverspill.Params(random().nextInt(2, 5), 64, 1.0f, -1f, -1f) // SPANN
                                                                                                                                            // overspill
        );
    }

    /**
     * Directly checks that the graph built from neighbourhoods is fully connected (every centroid is
     * reachable from the medoid entry), isolating the build logic from the IVF search machinery.
     */
    public void testNeighborhoodGraphIsConnected() throws IOException {
        int dimensions = random().nextInt(8, 48);
        int numCentroids = random().nextInt(150, 400);
        float[][] centroids = new float[numCentroids][dimensions];
        for (int c = 0; c < numCentroids; c++) {
            for (int d = 0; d < dimensions; d++) {
                centroids[c][d] = c * 100f + random().nextFloat();
            }
        }
        // shuffle ordinals so spatial order != ordinal order, like real kmeans output
        for (int i = numCentroids - 1; i > 0; i--) {
            int j = random().nextInt(i + 1);
            float[] tmp = centroids[i];
            centroids[i] = centroids[j];
            centroids[j] = tmp;
        }
        int m = random().nextInt(8, 32);
        int candidates = Math.min(numCentroids - 1, 64);
        org.elasticsearch.index.codec.vectors.cluster.NeighborHood[] neighborhoods =
            org.elasticsearch.index.codec.vectors.cluster.NeighborHood.computeNeighborhoods(centroids, candidates);
        CentroidGraphIO.MultiLevelAdjacency built = CentroidGraphIO.buildMultiLevelFromNeighborhoods(
            neighborhoods,
            centroids,
            m,
            42L,
            VectorSimilarityFunction.EUCLIDEAN
        );
        assertTrue("expected more than one level for " + numCentroids + " centroids", built.numLevels() >= 1);
        // BFS the level-0 adjacency: it must connect every centroid
        assertEquals("in-memory level-0 adjacency not fully connected", numCentroids, reachable(built.neighborsByLevel()[0], 0));

        // round-trip through serialization and BFS the deserialized level-0 graph the way search reads it
        try (Directory dir = newDirectory()) {
            try (var out = dir.createOutput("graph", org.apache.lucene.store.IOContext.DEFAULT)) {
                CentroidGraphIO.writeMultiLevelGraph(out, built);
            }
            try (var in = dir.openInput("graph", org.apache.lucene.store.IOContext.DEFAULT)) {
                org.apache.lucene.util.hnsw.HnswGraph graph = CentroidGraphIO.readGraph(in);
                assertEquals(built.numLevels(), graph.numLevels());
                int[][] readAdjacency = new int[numCentroids][];
                for (int node = 0; node < numCentroids; node++) {
                    graph.seek(0, node);
                    java.util.List<Integer> nbrs = new java.util.ArrayList<>();
                    for (int nb = graph.nextNeighbor(); nb != org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS; nb = graph
                        .nextNeighbor()) {
                        nbrs.add(nb);
                    }
                    readAdjacency[node] = nbrs.stream().mapToInt(Integer::intValue).toArray();
                }
                assertEquals("deserialized level-0 graph not fully connected", numCentroids, reachable(readAdjacency, 0));
            }
        }
    }

    /**
     * Isolates the search path: builds flat int7 records + the neighbourhood graph, then uses
     * {@link CentroidGraphIO#searchScorer} + HnswGraphSearcher to query each centroid and asserts the
     * graph navigates to it (it should be its own nearest neighbour).
     */
    public void testNeighborhoodGraphSearchFindsSelf() throws IOException {
        int dimensions = random().nextInt(16, 48);
        int numCentroids = random().nextInt(150, 300);
        float[][] centroids = new float[numCentroids][dimensions];
        for (int c = 0; c < numCentroids; c++) {
            for (int d = 0; d < dimensions; d++) {
                centroids[c][d] = random().nextFloat() * 4f - 2f;
            }
        }
        float[] globalCentroid = new float[dimensions];
        for (float[] centroid : centroids) {
            for (int d = 0; d < dimensions; d++) {
                globalCentroid[d] += centroid[d] / numCentroids;
            }
        }
        var sim = VectorSimilarityFunction.EUCLIDEAN;
        var osq = new org.elasticsearch.index.codec.vectors.OptimizedScalarQuantizer(sim);
        float globalCentroidDp = org.elasticsearch.simdvec.ESVectorUtil.dotProduct(globalCentroid, globalCentroid);
        int recordSize = CentroidGraphIO.flatRecordSize(dimensions);
        // build flat int7 records
        byte[] flat = new byte[numCentroids * recordSize];
        var bb = java.nio.ByteBuffer.wrap(flat).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int[] qScratch = new int[dimensions];
        float[] rScratch = new float[dimensions];
        for (int c = 0; c < numCentroids; c++) {
            var r = osq.scalarQuantize(centroids[c].clone(), rScratch, qScratch, (byte) 7, globalCentroid);
            for (int d = 0; d < dimensions; d++) {
                bb.put((byte) qScratch[d]);
            }
            bb.putFloat(r.lowerInterval());
            bb.putFloat(r.upperInterval());
            bb.putFloat(r.additionalCorrection());
            bb.putInt(r.quantizedComponentSum());
        }
        int m = random().nextInt(8, 32);
        int candidates = Math.min(numCentroids - 1, 64);
        var neighborhoods = org.elasticsearch.index.codec.vectors.cluster.NeighborHood.computeNeighborhoods(centroids, candidates);
        CentroidGraphIO.MultiLevelAdjacency built = CentroidGraphIO.buildMultiLevelFromNeighborhoods(
            neighborhoods,
            centroids,
            m,
            42L,
            VectorSimilarityFunction.EUCLIDEAN
        );
        try (Directory dir = newDirectory()) {
            try (var out = dir.createOutput("g", org.apache.lucene.store.IOContext.DEFAULT)) {
                out.writeBytes(flat, 0, flat.length);
                CentroidGraphIO.writeMultiLevelGraph(out, built);
            }
            try (var in = dir.openInput("g", org.apache.lucene.store.IOContext.DEFAULT)) {
                var flatSlice = in.slice("flat", 0, (long) numCentroids * recordSize);
                var graphSlice = in.slice("graph", (long) numCentroids * recordSize, in.length() - (long) numCentroids * recordSize);
                var graph = CentroidGraphIO.readGraph(graphSlice);
                int found = 0;
                int queries = Math.min(numCentroids, 40);
                for (int qi = 0; qi < queries; qi++) {
                    int q = random().nextInt(numCentroids);
                    var qr = osq.scalarQuantize(centroids[q].clone(), rScratch, qScratch, (byte) 7, globalCentroid);
                    byte[] qBytes = new byte[dimensions];
                    for (int d = 0; d < dimensions; d++) {
                        qBytes[d] = (byte) qScratch[d];
                    }
                    var scorer = CentroidGraphIO.searchScorer(flatSlice, numCentroids, dimensions, sim, globalCentroidDp, qBytes, qr);
                    var collector = new TopKnnCollector(10, Integer.MAX_VALUE);
                    org.apache.lucene.util.hnsw.HnswGraphSearcher.search(scorer, collector, graph, null);
                    for (var sd : collector.topDocs().scoreDocs) {
                        if (sd.doc == q) {
                            found++;
                            break;
                        }
                    }
                }
                assertTrue("graph search found self in only " + found + "/" + queries + " queries", found >= queries * 0.9);
            }
        }
    }

    private static int reachable(int[][] adjacency, int entry) {
        boolean[] visited = new boolean[adjacency.length];
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        visited[entry] = true;
        queue.add(entry);
        int reached = 1;
        while (queue.isEmpty() == false) {
            int node = queue.poll();
            for (int neighbour : adjacency[node]) {
                if (visited[neighbour] == false) {
                    visited[neighbour] = true;
                    reached++;
                    queue.add(neighbour);
                }
            }
        }
        return reached;
    }
}
