/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.codec.vectors.diskbbq.next;

import org.apache.lucene.codecs.DocValuesConsumer;
import org.apache.lucene.codecs.DocValuesProducer;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.hnsw.FlatVectorsWriter;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.KnnVectorValues;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.OrdinalMap;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TaskExecutor;
import org.apache.lucene.store.ByteBuffersDataOutput;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.hnsw.IntToIntFunction;
import org.apache.lucene.util.packed.DirectWriter;
import org.apache.lucene.util.packed.PackedInts;
import org.apache.lucene.util.packed.PackedLongValues;
import org.elasticsearch.core.SuppressForbidden;
import org.elasticsearch.index.codec.vectors.OptimizedScalarQuantizer;
import org.elasticsearch.index.codec.vectors.cluster.CentroidOps;
import org.elasticsearch.index.codec.vectors.cluster.ClusteringFloatVectorValues;
import org.elasticsearch.index.codec.vectors.cluster.ClusteringFloatVectorValuesSlice;
import org.elasticsearch.index.codec.vectors.cluster.HierarchicalKMeans;
import org.elasticsearch.index.codec.vectors.cluster.KMeansFloatVectorValues;
import org.elasticsearch.index.codec.vectors.cluster.KMeansResult;
import org.elasticsearch.index.codec.vectors.cluster.NeighborHood;
import org.elasticsearch.index.codec.vectors.diskbbq.CentroidAssignments;
import org.elasticsearch.index.codec.vectors.diskbbq.CentroidSlices;
import org.elasticsearch.index.codec.vectors.diskbbq.CentroidSupplier;
import org.elasticsearch.index.codec.vectors.diskbbq.DiskBBQBulkWriter;
import org.elasticsearch.index.codec.vectors.diskbbq.DocIdsWriter;
import org.elasticsearch.index.codec.vectors.diskbbq.IVFVectorsWriter;
import org.elasticsearch.index.codec.vectors.diskbbq.IntSorter;
import org.elasticsearch.index.codec.vectors.diskbbq.IntToBooleanFunction;
import org.elasticsearch.index.codec.vectors.diskbbq.PostingMetadata;
import org.elasticsearch.index.codec.vectors.diskbbq.Preconditioner;
import org.elasticsearch.index.codec.vectors.diskbbq.QuantizedVectorValues;
import org.elasticsearch.index.codec.vectors.diskbbq.VectorPreconditioner;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.simdvec.ES940OSQVectorsScorer;
import org.elasticsearch.simdvec.ESVectorUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;

import static org.elasticsearch.index.codec.vectors.cluster.HierarchicalKMeans.NO_SOAR_ASSIGNMENT;
import static org.elasticsearch.simdvec.ES940OSQVectorsScorer.BULK_SIZE;

/**
 * Default implementation of {@link IVFVectorsWriter}. It uses {@link HierarchicalKMeans} algorithm to
 * partition the vector space, and then stores the centroids and posting list in a sequential
 * fashion.
 */
public class ESNextDiskBBQVectorsWriter extends IVFVectorsWriter {
    private static final Logger logger = LogManager.getLogger(ESNextDiskBBQVectorsWriter.class);

    private final int vectorPerCluster;
    private final int centroidsPerParentCluster;
    private final ESNextDiskBBQVectorsFormat.QuantEncoding quantEncoding;
    private final TaskExecutor mergeExec;
    private final int numMergeWorkers;
    private final int blockDimension;
    private final boolean doPrecondition;
    // field for slicing, null for no slicing
    private final String sliceField;
    private final IvfFlushConfigSource flushConfigSource;
    private final IvfMergeConfigResolver mergeConfigResolver;
    // Experiment-only: HNSW graph over the centroids (see ESNextDiskBBQVectorsFormat#indexCentroidsInGraph).
    private final boolean indexCentroidsInGraph;
    private final int centroidHnswM;
    private final int centroidHnswBeamWidth;
    private final int centroidGraphEfSearch;
    private final SpannOverspill.Params spannParams;
    // Per-field graph layout offsets (relative to the field's centroid region), captured during
    // writeCentroids and consumed by doWriteMeta. Negative when no graph was written for the field.
    private long graphFlatCentroidsOffset = -1;
    private long graphPostingTableOffset = -1;
    private long graphSerializedOffset = -1;
    private long graphSerializedLength = -1;
    // Number of nearest centroids to gather per centroid when bootstrapping the centroid graph from
    // neighbourhoods; pruned down to centroidHnswM diverse links. Only used as a fallback when the
    // clustering did not already compute neighbourhoods (small centroid counts).
    private static final int CENTROID_GRAPH_NEIGHBOR_CANDIDATES = 64;
    // Fixed seed for the centroid graph's HNSW level assignment, so builds are reproducible.
    private static final long CENTROID_GRAPH_SEED = 42L;
    // Neighbourhoods computed during the most recent calculateCentroids, reused to bootstrap the centroid
    // graph without recomputing them. Set in calculateCentroids, consumed in writeCentroidsWithGraph.
    private NeighborHood[] currentCentroidNeighborhoods = null;
    // SPANN multi-replica overspill assignments for the current field (centroid ords + sq distances per
    // vector), set during calculateCentroids and consumed by buildAndWritePostingsLists. Null unless enabled.
    private int[][] currentReplicas = null;
    private float[][] currentReplicaDists = null;

    public ESNextDiskBBQVectorsWriter(
        SegmentWriteState state,
        String rawVectorFormatName,
        boolean useDirectIOReads,
        FlatVectorsWriter rawVectorDelegate,
        ESNextDiskBBQVectorsFormat.QuantEncoding encoding,
        int vectorPerCluster,
        int centroidsPerParentCluster,
        TaskExecutor mergeExec,
        int numMergeWorkers,
        int blockDimension,
        boolean doPrecondition,
        int flatVectorThreshold,
        String sliceField,
        IvfFlushConfigSource flushConfigSource,
        IvfMergeConfigResolver mergeConfigResolver,
        boolean indexCentroidsInGraph,
        int centroidHnswM,
        int centroidHnswBeamWidth,
        int centroidGraphEfSearch,
        SpannOverspill.Params spannParams
    ) throws IOException {
        super(
            state,
            rawVectorFormatName,
            useDirectIOReads,
            rawVectorDelegate,
            ESNextDiskBBQVectorsFormat.VERSION_CURRENT,
            ESNextDiskBBQVectorsFormat.NAME,
            ESNextDiskBBQVectorsFormat.IVF_META_EXTENSION,
            ESNextDiskBBQVectorsFormat.CENTROID_EXTENSION,
            ESNextDiskBBQVectorsFormat.CLUSTER_EXTENSION,
            true,
            flatVectorThreshold
        );
        this.vectorPerCluster = vectorPerCluster;
        this.centroidsPerParentCluster = centroidsPerParentCluster;
        this.quantEncoding = encoding;
        this.mergeExec = mergeExec;
        this.numMergeWorkers = numMergeWorkers;
        this.blockDimension = blockDimension;
        this.doPrecondition = doPrecondition;
        this.sliceField = sliceField;
        this.flushConfigSource = flushConfigSource != null ? flushConfigSource : IvfFlushConfigSource.empty();
        this.mergeConfigResolver = mergeConfigResolver != null ? mergeConfigResolver : IvfMergeConfigResolver.useCodecDefault();
        this.indexCentroidsInGraph = indexCentroidsInGraph;
        this.centroidHnswM = centroidHnswM;
        this.centroidHnswBeamWidth = centroidHnswBeamWidth;
        this.centroidGraphEfSearch = centroidGraphEfSearch;
        this.spannParams = spannParams == null ? SpannOverspill.Params.DISABLED : spannParams;
        if (sliceField != null) {
            Sort sort = state.segmentInfo.getIndexSort();
            if (sort == null || sort.getSort().length == 0) {
                throw new IllegalStateException("sliceField requires index sort");
            }
            SortField primary = sort.getSort()[0];
            if (sliceField.equals(primary.getField()) == false) {
                throw new IllegalStateException("sliceField must be primary index sort");
            }
            if (primary.getType() != SortField.Type.STRING) {
                throw new IllegalStateException("sliceField requires primary index sort");
            }
        }
    }

    @Override
    protected IvfSegmentConfig beginIvfFieldFlush(FieldInfo fieldInfo) throws IOException {
        IvfSegmentConfig codec = IvfSegmentConfig.fromCodecDefaults(quantEncoding, doPrecondition);
        return flushConfigSource.load(segmentWriteState, fieldInfo).orElse(codec);
    }

    @Override
    protected IvfSegmentConfig beginIvfFieldMerge(FieldInfo fieldInfo, MergeState mergeState) throws IOException {
        IvfSegmentConfig codec = IvfSegmentConfig.fromCodecDefaults(quantEncoding, doPrecondition);
        return mergeConfigResolver.resolve(fieldInfo, mergeState, codec);
    }

    private static IvfSegmentConfig requireSegmentConfig(IvfSegmentConfig cfg) {
        return Objects.requireNonNull(cfg, "ivf segment config must not be null");
    }

    @Override
    protected Preconditioner inheritPreconditioner(FieldInfo fieldInfo, MergeState mergeState, IvfSegmentConfig fieldWritingContext)
        throws IOException {
        if (requireSegmentConfig(fieldWritingContext).usePrecondition()) {
            for (KnnVectorsReader reader : mergeState.knnVectorsReaders) {
                if (reader instanceof VectorPreconditioner) {
                    Preconditioner preconditioner = ((VectorPreconditioner) reader).getPreconditioner(fieldInfo);
                    if (preconditioner != null) {
                        return preconditioner;
                    }
                }
            }
            // else
            return createPreconditioner(fieldInfo.getVectorDimension(), fieldWritingContext);
        }
        return null;
    }

    @Override
    protected Preconditioner createPreconditioner(int dimension, IvfSegmentConfig ivfSegmentConfig) {
        if (requireSegmentConfig(ivfSegmentConfig).usePrecondition()) {
            return Preconditioner.createPreconditioner(dimension, blockDimension);
        } else {
            return null;
        }
    }

    @Override
    protected void writePreconditioner(Preconditioner preconditioner, IndexOutput out) throws IOException {
        if (preconditioner != null) {
            preconditioner.write(out);
        }
    }

    @Override
    protected Consumer<List<float[]>> preconditionVectors(Preconditioner preconditioner, IvfSegmentConfig fieldWritingContext) {
        return (vectors) -> {
            if (requireSegmentConfig(fieldWritingContext).usePrecondition() == false || vectors.isEmpty()) {
                return;
            }
            if (preconditioner == null) {
                throw new IllegalStateException("preconditioner was not created but should be first");
            }
            float[] out = new float[vectors.getFirst().length];
            for (int i = 0; i < vectors.size(); i++) {
                float[] vector = vectors.get(i);
                preconditioner.applyTransform(vector, out);
                System.arraycopy(out, 0, vector, 0, vector.length);
            }
        };
    }

    @Override
    protected FloatVectorValues preconditionVectors(
        Preconditioner preconditioner,
        FloatVectorValues vectors,
        IvfSegmentConfig fieldWritingContext
    ) {
        if (requireSegmentConfig(fieldWritingContext).usePrecondition() == false) {
            return vectors;
        }
        if (preconditioner == null) {
            throw new IllegalStateException("preconditioner was not created but should be first");
        }

        // TODO: batch apply preconditioner for better performance and keep a batch on heap at a time
        return new FloatVectorValues() {
            final float[] preconditionedVectorValue = new float[vectors.dimension()];
            int cachedOrd = -1;

            @Override
            public int getVectorByteLength() {
                return vectors.getVectorByteLength();
            }

            @Override
            public float[] vectorValue(int ord) throws IOException {
                assert ord != -1;
                if (ord != cachedOrd) {
                    float[] vectorValue = vectors.vectorValue(ord);
                    preconditioner.applyTransform(vectorValue, this.preconditionedVectorValue);
                    cachedOrd = ord;
                }
                return this.preconditionedVectorValue;
            }

            @Override
            public FloatVectorValues copy() throws IOException {
                return vectors.copy();
            }

            @Override
            public int dimension() {
                return vectors.dimension();
            }

            @Override
            public int size() {
                return vectors.size();
            }

            @Override
            public DocIndexIterator iterator() {
                return vectors.iterator();
            }
        };
    }

    @Override
    public CentroidOffsetAndLength buildAndWritePostingsLists(
        FieldInfo fieldInfo,
        CentroidSupplier centroidSupplier,
        FloatVectorValues floatVectorValues,
        IndexOutput postingsOutput,
        long fileOffset,
        int[] assignments,
        int[] overspillAssignments,
        IvfSegmentConfig fieldWritingContext
    ) throws IOException {
        final IvfSegmentConfig segmentConfig = requireSegmentConfig(fieldWritingContext);
        final ESNextDiskBBQVectorsFormat.QuantEncoding effectiveQuantEncoding = segmentConfig.quantEncoding();
        final int[][] assignmentsByCluster = currentReplicas != null
            ? buildReplicaMembership(centroidSupplier.size())
            : buildSoarMembership(centroidSupplier.size(), assignments, overspillAssignments);
        return writePostingsOnHeap(
            fieldInfo,
            centroidSupplier,
            floatVectorValues,
            postingsOutput,
            fileOffset,
            assignmentsByCluster,
            effectiveQuantEncoding
        );
    }

    /** Builds per-cluster membership from primary + single-SOAR overspill assignments. */
    private static int[][] buildSoarMembership(int numCentroids, int[] assignments, int[] overspillAssignments) {
        final int[] centroidVectorCount = new int[numCentroids];
        for (int i = 0; i < assignments.length; i++) {
            centroidVectorCount[assignments[i]]++;
            if (overspillAssignments.length > i && overspillAssignments[i] != NO_SOAR_ASSIGNMENT) {
                centroidVectorCount[overspillAssignments[i]]++;
            }
        }
        final int[][] assignmentsByCluster = new int[numCentroids][];
        for (int c = 0; c < numCentroids; c++) {
            assignmentsByCluster[c] = new int[centroidVectorCount[c]];
        }
        Arrays.fill(centroidVectorCount, 0);
        for (int i = 0; i < assignments.length; i++) {
            int c = assignments[i];
            assignmentsByCluster[c][centroidVectorCount[c]++] = i;
            if (overspillAssignments.length > i) {
                int s = overspillAssignments[i];
                if (s != NO_SOAR_ASSIGNMENT) {
                    assignmentsByCluster[s][centroidVectorCount[s]++] = i;
                }
            }
        }
        return assignmentsByCluster;
    }

    /**
     * Writes posting lists, quantizing each vector against its cluster centroid on the fly. Used for the
     * flush path and (in SPANN/graph mode) the merge path, where the merged vectors are randomly accessible.
     */
    private CentroidOffsetAndLength writePostingsOnHeap(
        FieldInfo fieldInfo,
        CentroidSupplier centroidSupplier,
        FloatVectorValues floatVectorValues,
        IndexOutput postingsOutput,
        long fileOffset,
        int[][] assignmentsByCluster,
        ESNextDiskBBQVectorsFormat.QuantEncoding effectiveQuantEncoding
    ) throws IOException {
        final KMeansResult<float[]> centroidClusters = centroidSupplier.secondLevelClusters();
        int maxPostingListSize = 0;
        for (int[] cluster : assignmentsByCluster) {
            maxPostingListSize = Math.max(maxPostingListSize, cluster.length);
        }
        final PackedLongValues.Builder offsets = PackedLongValues.monotonicBuilder(PackedInts.COMPACT);
        final PackedLongValues.Builder lengths = PackedLongValues.monotonicBuilder(PackedInts.COMPACT);
        DiskBBQBulkWriter bulkWriter = DiskBBQBulkWriter.fromBitSize(effectiveQuantEncoding.bits(), BULK_SIZE, postingsOutput, true, true);
        OnHeapQuantizedVectors onHeapQuantizedVectors = new OnHeapQuantizedVectors(
            floatVectorValues,
            fieldInfo.getVectorSimilarityFunction(),
            effectiveQuantEncoding,
            fieldInfo.getVectorDimension(),
            new OptimizedScalarQuantizer(fieldInfo.getVectorSimilarityFunction())
        );
        final int[] docIds = new int[maxPostingListSize];
        final int[] docDeltas = new int[maxPostingListSize];
        final int[] clusterOrds = new int[maxPostingListSize];
        DocIdsWriter idsWriter = new DocIdsWriter();
        for (int c = 0; c < centroidSupplier.size(); c++) {
            float[] centroid = centroidSupplier.centroid(c);
            int[] cluster = assignmentsByCluster[c];
            long offset = postingsOutput.alignFilePointer(Float.BYTES) - fileOffset;
            offsets.add(offset);
            postingsOutput.writeInt(Float.floatToIntBits(ESVectorUtil.squareDistance(centroid, centroidClusters.getCentroid(c))));
            int size = cluster.length;
            // write docIds
            postingsOutput.writeVInt(size);
            for (int j = 0; j < size; j++) {
                docIds[j] = floatVectorValues.ordToDoc(cluster[j]);
                clusterOrds[j] = j;
            }
            // sort cluster.buffer by docIds values, this way cluster ordinals are sorted by docIds
            new IntSorter(clusterOrds, i -> docIds[i]).sort(0, size);
            // encode doc deltas
            for (int j = 0; j < size; j++) {
                docDeltas[j] = j == 0 ? docIds[clusterOrds[j]] : docIds[clusterOrds[j]] - docIds[clusterOrds[j - 1]];
            }
            onHeapQuantizedVectors.reset(centroid, centroidClusters.getCentroid(c), size, ord -> cluster[clusterOrds[ord]]);
            byte encoding = idsWriter.calculateBlockEncoding(i -> docDeltas[i], size, BULK_SIZE);
            postingsOutput.writeByte(encoding);
            if (sliceField != null) {
                // We are not writing the docIds as we know they are writing in vector ord order.
                // we will ise the delegated FloatVectorValue instance on read to do the translation for us.
                assert centroidSupplier.size() == 1;
                bulkWriter.writeVectors(onHeapQuantizedVectors, null);
            } else {
                bulkWriter.writeVectors(onHeapQuantizedVectors, i -> {
                    // for vector i we write `bulk` size docs or the remaining docs
                    idsWriter.writeDocIds(d -> docDeltas[i + d], Math.min(BULK_SIZE, size - i), encoding, postingsOutput);
                });
            }
            lengths.add(postingsOutput.getFilePointer() - fileOffset - offset);
        }

        if (logger.isDebugEnabled()) {
            printClusterQualityStatistics(assignmentsByCluster);
        }

        return new CentroidOffsetAndLength(offsets.build(), lengths.build());
    }

    @Override
    @SuppressForbidden(reason = "require usage of Lucene's IOUtils#deleteFilesIgnoringExceptions(...)")
    public CentroidOffsetAndLength buildAndWritePostingsLists(
        FieldInfo fieldInfo,
        CentroidSupplier centroidSupplier,
        FloatVectorValues floatVectorValues,
        IndexOutput postingsOutput,
        long fileOffset,
        MergeState mergeState,
        int[] assignments,
        int[] overspillAssignments,
        IvfSegmentConfig fieldWritingContext
    ) throws IOException {
        final IvfSegmentConfig segmentConfig = requireSegmentConfig(fieldWritingContext);
        final ESNextDiskBBQVectorsFormat.QuantEncoding effectiveQuantEncoding = segmentConfig.quantEncoding();
        if (currentReplicas != null) {
            // SPANN overspill: vectors are randomly accessible via the merged temp file, so quantize on the
            // fly (per cluster) like the flush path instead of the 2-slot pre-quantized layout.
            final int[][] assignmentsByCluster = buildReplicaMembership(centroidSupplier.size());
            return writePostingsOnHeap(
                fieldInfo,
                centroidSupplier,
                floatVectorValues,
                postingsOutput,
                fileOffset,
                assignmentsByCluster,
                effectiveQuantEncoding
            );
        }
        // first, quantize all the vectors into a temporary file
        var vectorSimilarityFunction = fieldInfo.getVectorSimilarityFunction();
        KMeansResult<float[]> centroidClusters = centroidSupplier.secondLevelClusters();
        String quantizedVectorsTempName = null;
        try (
            IndexOutput quantizedVectorsTemp = mergeState.segmentInfo.dir.createTempOutput(
                mergeState.segmentInfo.name,
                "qvec_",
                IOContext.DEFAULT
            )
        ) {
            quantizedVectorsTempName = quantizedVectorsTemp.getName();
            OptimizedScalarQuantizer quantizer = new OptimizedScalarQuantizer(vectorSimilarityFunction);
            int[] quantized = new int[effectiveQuantEncoding.discretizedDimensions(fieldInfo.getVectorDimension())];
            byte[] binary = new byte[effectiveQuantEncoding.getDocPackedLength(fieldInfo.getVectorDimension())];
            float[] scratch = new float[fieldInfo.getVectorDimension()];
            for (int i = 0; i < assignments.length; i++) {
                int c = assignments[i];
                float[] centroid = centroidSupplier.centroid(c);
                float[] parentCentroid = centroidClusters.getCentroid(c);
                float[] vector = floatVectorValues.vectorValue(i);
                boolean overspill = overspillAssignments.length > i && overspillAssignments[i] != NO_SOAR_ASSIGNMENT;
                OptimizedScalarQuantizer.QuantizationResult result = quantizer.scalarQuantize(
                    vector,
                    scratch,
                    quantized,
                    effectiveQuantEncoding.bits(),
                    centroid
                );
                if (parentCentroid != null) {
                    float additionalCorrection = vectorSimilarityFunction == VectorSimilarityFunction.EUCLIDEAN
                        ? ESVectorUtil.squareDistance(vector, parentCentroid)
                        : ESVectorUtil.dotProduct(scratch, parentCentroid);
                    result = new OptimizedScalarQuantizer.QuantizationResult(
                        result.lowerInterval(),
                        result.upperInterval(),
                        additionalCorrection,
                        result.quantizedComponentSum()
                    );
                }
                effectiveQuantEncoding.pack(quantized, binary);
                writeQuantizedValue(quantizedVectorsTemp, binary, result);
                if (overspill) {
                    int s = overspillAssignments[i];
                    float[] overspillCentroid = centroidSupplier.centroid(s);
                    float[] overspillParentCentroid = centroidClusters.getCentroid(s);
                    // write the overspill vector as well
                    result = quantizer.scalarQuantize(vector, scratch, quantized, effectiveQuantEncoding.bits(), overspillCentroid);
                    if (overspillParentCentroid != null) {
                        float additionalCorrection = vectorSimilarityFunction == VectorSimilarityFunction.EUCLIDEAN
                            ? ESVectorUtil.squareDistance(vector, overspillParentCentroid)
                            : ESVectorUtil.dotProduct(scratch, overspillParentCentroid);
                        result = new OptimizedScalarQuantizer.QuantizationResult(
                            result.lowerInterval(),
                            result.upperInterval(),
                            additionalCorrection,
                            result.quantizedComponentSum()
                        );
                    }
                    effectiveQuantEncoding.pack(quantized, binary);
                    writeQuantizedValue(quantizedVectorsTemp, binary, result);
                } else {
                    // write a zero vector for the overspill
                    Arrays.fill(binary, (byte) 0);
                    OptimizedScalarQuantizer.QuantizationResult zeroResult = new OptimizedScalarQuantizer.QuantizationResult(0f, 0f, 0f, 0);
                    writeQuantizedValue(quantizedVectorsTemp, binary, zeroResult);
                }
            }
        } catch (Throwable t) {
            if (quantizedVectorsTempName != null) {
                org.apache.lucene.util.IOUtils.deleteFilesIgnoringExceptions(mergeState.segmentInfo.dir, quantizedVectorsTempName);
            }
            throw t;
        }
        int[] centroidVectorCount = new int[centroidSupplier.size()];
        for (int i = 0; i < assignments.length; i++) {
            centroidVectorCount[assignments[i]]++;
            // if soar assignments are present, count them as well
            if (overspillAssignments.length > i && overspillAssignments[i] != NO_SOAR_ASSIGNMENT) {
                centroidVectorCount[overspillAssignments[i]]++;
            }
        }

        int maxPostingListSize = 0;
        int[][] assignmentsByCluster = new int[centroidSupplier.size()][];
        boolean[][] isOverspillByCluster = new boolean[centroidSupplier.size()][];
        for (int c = 0; c < centroidSupplier.size(); c++) {
            int size = centroidVectorCount[c];
            maxPostingListSize = Math.max(maxPostingListSize, size);
            assignmentsByCluster[c] = new int[size];
            isOverspillByCluster[c] = new boolean[size];
        }
        Arrays.fill(centroidVectorCount, 0);

        for (int i = 0; i < assignments.length; i++) {
            int c = assignments[i];
            assignmentsByCluster[c][centroidVectorCount[c]++] = i;
            // if soar assignments are present, add them to the cluster as well
            if (overspillAssignments.length > i) {
                int s = overspillAssignments[i];
                if (s != NO_SOAR_ASSIGNMENT) {
                    assignmentsByCluster[s][centroidVectorCount[s]] = i;
                    isOverspillByCluster[s][centroidVectorCount[s]++] = true;
                }
            }
        }
        // now we can read the quantized vectors from the temporary file
        try (IndexInput quantizedVectorsInput = mergeState.segmentInfo.dir.openInput(quantizedVectorsTempName, IOContext.DEFAULT)) {
            final PackedLongValues.Builder offsets = PackedLongValues.monotonicBuilder(PackedInts.COMPACT);
            final PackedLongValues.Builder lengths = PackedLongValues.monotonicBuilder(PackedInts.COMPACT);
            OffHeapQuantizedVectors offHeapQuantizedVectors = new OffHeapQuantizedVectors(
                quantizedVectorsInput,
                effectiveQuantEncoding,
                fieldInfo.getVectorDimension()
            );
            DiskBBQBulkWriter bulkWriter = DiskBBQBulkWriter.fromBitSize(
                effectiveQuantEncoding.bits(),
                BULK_SIZE,
                postingsOutput,
                true,
                true
            );
            // write the posting lists
            final int[] docIds = new int[maxPostingListSize];
            final int[] docDeltas = new int[maxPostingListSize];
            final int[] clusterOrds = new int[maxPostingListSize];
            DocIdsWriter idsWriter = new DocIdsWriter();
            for (int c = 0; c < centroidSupplier.size(); c++) {
                float[] centroid = centroidSupplier.centroid(c);
                int[] cluster = assignmentsByCluster[c];
                boolean[] isOverspill = isOverspillByCluster[c];
                long offset = postingsOutput.alignFilePointer(Float.BYTES) - fileOffset;
                offsets.add(offset);
                postingsOutput.writeInt(Float.floatToIntBits(ESVectorUtil.squareDistance(centroid, centroidClusters.getCentroid(c))));
                // write docIds
                int size = cluster.length;
                postingsOutput.writeVInt(size);
                for (int j = 0; j < size; j++) {
                    docIds[j] = floatVectorValues.ordToDoc(cluster[j]);
                    clusterOrds[j] = j;
                }
                // sort cluster.buffer by docIds values, this way cluster ordinals are sorted by docIds
                new IntSorter(clusterOrds, i -> docIds[i]).sort(0, size);
                // encode doc deltas
                for (int j = 0; j < size; j++) {
                    docDeltas[j] = j == 0 ? docIds[clusterOrds[j]] : docIds[clusterOrds[j]] - docIds[clusterOrds[j - 1]];
                }
                byte encoding = idsWriter.calculateBlockEncoding(i -> docDeltas[i], size, BULK_SIZE);
                postingsOutput.writeByte(encoding);
                offHeapQuantizedVectors.reset(size, ord -> isOverspill[clusterOrds[ord]], ord -> cluster[clusterOrds[ord]]);
                // write vectors
                bulkWriter.writeVectors(offHeapQuantizedVectors, i -> {
                    // for vector i we write `bulk` size docs or the remaining docs
                    idsWriter.writeDocIds(d -> docDeltas[d + i], Math.min(BULK_SIZE, size - i), encoding, postingsOutput);
                });
                lengths.add(postingsOutput.getFilePointer() - fileOffset - offset);
            }

            if (logger.isDebugEnabled()) {
                printClusterQualityStatistics(assignmentsByCluster);
            }
            return new CentroidOffsetAndLength(offsets.build(), lengths.build());
        } finally {
            org.apache.lucene.util.IOUtils.deleteFilesIgnoringExceptions(mergeState.segmentInfo.dir, quantizedVectorsTempName);
        }
    }

    private static void printClusterQualityStatistics(int[][] clusters) {
        float min = Float.MAX_VALUE;
        float max = Float.MIN_VALUE;
        float mean = 0;
        float m2 = 0;
        // iteratively compute the variance & mean
        int count = 0;
        for (int[] cluster : clusters) {
            count += 1;
            if (cluster == null) {
                continue;
            }
            float delta = cluster.length - mean;
            mean += delta / count;
            m2 += delta * (cluster.length - mean);
            min = Math.min(min, cluster.length);
            max = Math.max(max, cluster.length);
        }
        float variance = m2 / (clusters.length - 1);
        logger.debug(
            "Centroid count: {} min: {} max: {} mean: {} stdDev: {} variance: {}",
            clusters.length,
            min,
            max,
            mean,
            Math.sqrt(variance),
            variance
        );
    }

    @Override
    public CentroidSupplier createCentroidSupplier(
        IndexInput centroidsInput,
        CentroidSlices centroidSlices,
        int numCentroids,
        FieldInfo fieldInfo,
        float[] globalCentroid
    ) throws IOException {
        CentroidSupplier centroidSupplier = new OffHeapCentroidSupplier(
            centroidsInput,
            numCentroids,
            fieldInfo,
            KMeansResult.singleCluster(globalCentroid, numCentroids),
            centroidSlices
        );
        if (centroidSupplier.size() > centroidsPerParentCluster * centroidsPerParentCluster) {
            ClusteringFloatVectorValues floatVectorValues = centroidSupplier.asKmeansFloatVectorValues();
            if (centroidSlices == null) {
                KMeansResult<float[]> centroidClusters = buildSecondLevelClusters(fieldInfo, floatVectorValues, true);
                return new OffHeapCentroidSupplier(centroidsInput, numCentroids, fieldInfo, centroidClusters, null);
            } else {
                List<KMeansResult<float[]>> centroidClusters = new ArrayList<>(centroidSlices.sliceOffsets().length);
                int start = 0;
                for (int i = 0; i < centroidSlices.sliceOffsets().length; i++) {
                    final int offset = start;
                    start = centroidSlices.sliceOffsets()[i];
                    int count = start - offset;
                    ClusteringFloatVectorValues slice = new ClusteringFloatVectorValuesSlice(floatVectorValues, j -> offset + j, count);
                    KMeansResult<float[]> result = buildSecondLevelClusters(fieldInfo, slice, true);
                    centroidClusters.add(result);
                    if (i == 0) {
                        centroidSlices.sliceOffsets()[i] = result.centroids().length;
                    } else {
                        centroidSlices.sliceOffsets()[i] = centroidSlices.sliceOffsets()[i - 1] + result.centroids().length;
                    }
                }
                KMeansResult<float[]> result = KMeansResult.merge(centroidClusters, CentroidOps.FLOAT);
                assert CentroidSlices.assertSliceOffsets(centroidSlices.sliceOffsets(), result.centroids().length);
                return new OffHeapCentroidSupplier(centroidsInput, numCentroids, fieldInfo, result, centroidSlices);
            }
        }
        return centroidSupplier;
    }

    @Override
    public CentroidSupplier createCentroidSupplier(FieldInfo info, float[][] centroids, float[] globalCentroid) throws IOException {
        CentroidSupplier centroidSupplier = CentroidSupplier.fromArray(
            centroids,
            KMeansResult.singleCluster(globalCentroid, centroids.length),
            info.getVectorDimension()
        );
        if (centroidSupplier.size() > centroidsPerParentCluster * centroidsPerParentCluster) {
            KMeansResult<float[]> centroidClusters = buildSecondLevelClusters(info, centroidSupplier.asKmeansFloatVectorValues(), false);
            return CentroidSupplier.fromArray(centroids, centroidClusters, info.getVectorDimension());
        }
        return centroidSupplier;
    }

    @Override
    protected void doWriteMeta(
        IndexOutput metaOutput,
        FieldInfo field,
        int numCentroids,
        long preconditionerOffset,
        long preconditionerLength,
        int numberOfSlices,
        int maxSliceSize,
        IvfSegmentConfig ivfSegmentConfig
    ) throws IOException {
        final IvfSegmentConfig segmentConfig = requireSegmentConfig(ivfSegmentConfig);
        metaOutput.writeInt(ES940OSQVectorsScorer.BULK_SIZE);
        metaOutput.writeInt(segmentConfig.quantEncoding().id());
        metaOutput.writeLong(preconditionerLength);
        if (preconditionerLength > 0) {
            metaOutput.writeLong(preconditionerOffset);
        }
        if (sliceField == null) {
            assert numberOfSlices == 0;
            metaOutput.writeInt(-1);
        } else {
            metaOutput.writeInt(numberOfSlices);
            if (numberOfSlices > 0) {
                metaOutput.writeVInt(maxSliceSize);
            }
        }
        metaOutput.writeInt(Float.floatToIntBits(segmentConfig.rescoreOversample()));
        // Experiment-only centroid HNSW graph metadata. Always written (the on-disk version was bumped
        // to VERSION_CENTROID_GRAPH); a leading byte indicates whether a graph is present for the field.
        if (graphSerializedOffset >= 0) {
            metaOutput.writeByte((byte) 1);
            metaOutput.writeVLong(graphFlatCentroidsOffset);
            metaOutput.writeVLong(graphPostingTableOffset);
            metaOutput.writeVLong(graphSerializedOffset);
            metaOutput.writeVLong(graphSerializedLength);
            metaOutput.writeInt(centroidGraphEfSearch);
            // SPANN overspill replicates vectors into several postings. When enabled, signal the reader to
            // (a) budget the search by postings/heads visited rather than vectors, and (b) dedup docids across
            // postings, so replication adds coverage instead of inflating the per-visit-ratio vector budget.
            metaOutput.writeByte(spannParams.enabled() ? (byte) 1 : (byte) 0);
        } else {
            metaOutput.writeByte((byte) 0);
        }
        // reset so the next field (which may be non-float and skip writeCentroids) starts clean
        graphFlatCentroidsOffset = -1;
        graphPostingTableOffset = -1;
        graphSerializedOffset = -1;
        graphSerializedLength = -1;
    }

    @Override
    public void writeCentroids(
        FieldInfo fieldInfo,
        CentroidSupplier centroidSupplier,
        int[] centroidAssignments,
        float[] globalCentroid,
        CentroidOffsetAndLength centroidOffsetAndLength,
        IndexOutput centroidOutput
    ) throws IOException {
        doWriteCentroids(fieldInfo, centroidSupplier, centroidAssignments, globalCentroid, centroidOffsetAndLength, centroidOutput);
    }

    @Override
    public void writeCentroids(
        FieldInfo fieldInfo,
        CentroidSupplier centroidSupplier,
        int[] centroidAssignments,
        float[] globalCentroid,
        CentroidOffsetAndLength centroidOffsetAndLength,
        IndexOutput centroidOutput,
        MergeState mergeState
    ) throws IOException {
        doWriteCentroids(fieldInfo, centroidSupplier, centroidAssignments, globalCentroid, centroidOffsetAndLength, centroidOutput);
    }

    private record CentroidGroups(float[][] centroids, int[][] vectors, int maxVectorsPerCentroidLength) {}

    private void doWriteCentroids(
        FieldInfo fieldInfo,
        CentroidSupplier centroidSupplier,
        int[] centroidAssignments,
        float[] globalCentroid,
        CentroidOffsetAndLength centroidOffsetAndLength,
        IndexOutput centroidOutput
    ) throws IOException {
        // reset per-field graph layout offsets; they remain negative (no graph) unless we write one below
        graphFlatCentroidsOffset = -1;
        graphPostingTableOffset = -1;
        graphSerializedOffset = -1;
        graphSerializedLength = -1;
        final long fieldCentroidStart = centroidOutput.getFilePointer();
        CentroidSlices centroidSlices = centroidSupplier.slices();
        if (centroidSlices != null) {
            int numSlices = centroidSlices.sliceNumVectors().length;
            int maxSlice = centroidSlices.maxSliceSize();
            int bits = DirectWriter.bitsRequired(maxSlice);
            DirectWriter writer = DirectWriter.getInstance(centroidOutput, numSlices, bits);
            for (int i = 0; i < centroidSlices.sliceNumVectors().length; i++) {
                writer.add(centroidSlices.sliceNumVectors()[i]);
            }
            writer.finish();
        }
        if (indexCentroidsInGraph && centroidSupplier.size() > 0) {
            // Experiment-only: a single flat HNSW graph over all centroids, bypassing the parent/child
            // hierarchy. Query quantization at posting-visit time uses the global centroid (numParents == 0).
            writeCentroidLookup(centroidOutput, centroidAssignments, IntUnaryOperator.identity(), centroidSupplier.size());
            writeCentroidsWithGraph(
                fieldInfo,
                centroidSupplier,
                globalCentroid,
                centroidOffsetAndLength,
                centroidOutput,
                fieldCentroidStart
            );
            return;
        }
        if (centroidSupplier.secondLevelClusters().centroidsSupplier().size() > 1) {
            final CentroidGroups centroidGroups = buildCentroidGroups(centroidSupplier.secondLevelClusters());
            {
                // write vector ord -> centroid lookup table. We need to remap current centroid ordinals
                // to the ordinals on the parent / child structure.
                final int[] centroidOrdinalMap = new int[centroidSupplier.size()];
                int idx = 0;
                for (int[] centroidVectors : centroidGroups.vectors()) {
                    for (int assignment : centroidVectors) {
                        centroidOrdinalMap[assignment] = idx++;
                    }
                }
                assert idx == centroidSupplier.size() : "Expected [" + centroidSupplier.size() + "], got [" + idx + "]";
                writeCentroidLookup(centroidOutput, centroidAssignments, i -> centroidOrdinalMap[i], centroidSupplier.size());
            }
            writeCentroidsWithParents(fieldInfo, centroidSupplier, globalCentroid, centroidOffsetAndLength, centroidOutput, centroidGroups);
        } else {
            writeCentroidLookup(centroidOutput, centroidAssignments, IntUnaryOperator.identity(), centroidSupplier.size());
            writeCentroidsWithoutParents(fieldInfo, centroidSupplier, globalCentroid, centroidOffsetAndLength, centroidOutput);
        }
    }

    private void writeCentroidLookup(IndexOutput out, int[] centroidAssignments, IntUnaryOperator OrdinalMap, int numberCentroids)
        throws IOException {
        final int bitsRequired = DirectWriter.bitsRequired(numberCentroids);
        final long bytesRequired = DirectWriter.bytesRequired(centroidAssignments.length, bitsRequired);
        final ByteBuffersDataOutput memory = new ByteBuffersDataOutput(bytesRequired);
        final DirectWriter writer = DirectWriter.getInstance(memory, centroidAssignments.length, bitsRequired);
        for (int centroidAssignment : centroidAssignments) {
            writer.add(OrdinalMap.applyAsInt(centroidAssignment));
        }
        writer.finish();
        out.copyBytes(memory.toDataInput(), memory.size());
    }

    private void writeSlicesOffsets(IndexOutput out, CentroidSlices centroidSlices) throws IOException {
        if (centroidSlices == null) {
            return;
        }
        // TODO: should we compress slice offsets?
        for (int offset : centroidSlices.sliceOffsets()) {
            out.writeInt(offset);
        }
    }

    private void writeCentroidsWithParents(
        FieldInfo fieldInfo,
        CentroidSupplier centroidSupplier,
        float[] globalCentroid,
        CentroidOffsetAndLength centroidOffsetAndLength,
        IndexOutput centroidOutput,
        CentroidGroups centroidGroups
    ) throws IOException {
        DiskBBQBulkWriter bulkWriter = DiskBBQBulkWriter.fromBitSize(7, BULK_SIZE, centroidOutput, true, true);
        final OptimizedScalarQuantizer osq = new OptimizedScalarQuantizer(fieldInfo.getVectorSimilarityFunction());
        centroidOutput.writeVInt(centroidGroups.centroids().length);
        writeSlicesOffsets(centroidOutput, centroidSupplier.slices());
        centroidOutput.writeVInt(centroidGroups.maxVectorsPerCentroidLength());
        // let's also write the raw parent centroids
        final ByteBuffer buffer = ByteBuffer.allocate(fieldInfo.getVectorDimension() * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < centroidGroups.centroids().length; i++) {
            float[] centroid = centroidGroups.centroids()[i];
            buffer.asFloatBuffer().put(centroid);
            centroidOutput.writeBytes(buffer.array(), buffer.array().length);
        }
        QuantizedCentroids parentQuantizeCentroid = new QuantizedCentroids(
            CentroidSupplier.fromArray(centroidGroups.centroids, KMeansResult.emptyFloat(), fieldInfo.getVectorDimension()),
            fieldInfo.getVectorDimension(),
            osq,
            globalCentroid
        );
        bulkWriter.writeVectors(parentQuantizeCentroid, null);
        int offset = 0;
        for (int[] centroidVectors : centroidGroups.vectors()) {
            centroidOutput.writeInt(offset);
            centroidOutput.writeInt(centroidVectors.length);
            offset += centroidVectors.length;
        }

        QuantizedCentroids childrenQuantizeCentroid = new QuantizedCentroids(
            centroidSupplier,
            fieldInfo.getVectorDimension(),
            osq,
            globalCentroid
        );
        for (int[] centroidVectors : centroidGroups.vectors()) {
            childrenQuantizeCentroid.reset(idx -> centroidVectors[idx], centroidVectors.length);
            bulkWriter.writeVectors(childrenQuantizeCentroid, null);
        }
        // write the centroid offsets at the end of the file
        int parentOrd = 0;
        for (int[] centroidVectors : centroidGroups.vectors()) {
            for (int assignment : centroidVectors) {
                centroidOutput.writeLong(centroidOffsetAndLength.offsets().get(assignment));
                centroidOutput.writeLong(centroidOffsetAndLength.lengths().get(assignment));
                centroidOutput.writeInt(parentOrd);
            }
            parentOrd++;
        }
    }

    private void writeCentroidsWithoutParents(
        FieldInfo fieldInfo,
        CentroidSupplier centroidSupplier,
        float[] globalCentroid,
        CentroidOffsetAndLength centroidOffsetAndLength,
        IndexOutput centroidOutput
    ) throws IOException {
        centroidOutput.writeVInt(0);
        writeSlicesOffsets(centroidOutput, centroidSupplier.slices());
        DiskBBQBulkWriter bulkWriter = DiskBBQBulkWriter.fromBitSize(7, BULK_SIZE, centroidOutput, true, true);
        final OptimizedScalarQuantizer osq = new OptimizedScalarQuantizer(fieldInfo.getVectorSimilarityFunction());
        QuantizedCentroids quantizedCentroids = new QuantizedCentroids(
            centroidSupplier,
            fieldInfo.getVectorDimension(),
            osq,
            globalCentroid
        );
        bulkWriter.writeVectors(quantizedCentroids, null);
        // write the centroid offsets at the end of the file
        for (int i = 0; i < centroidSupplier.size(); i++) {
            centroidOutput.writeLong(centroidOffsetAndLength.offsets().get(i));
            centroidOutput.writeLong(centroidOffsetAndLength.lengths().get(i));
        }
    }

    /**
     * Experiment-only: writes the centroids as flat 7-bit quantized records, builds an HNSW graph over
     * them and serializes it. The byte offsets of each sub-region (relative to the field's centroid
     * region start) are captured for {@link #doWriteMeta}.
     */
    private void writeCentroidsWithGraph(
        FieldInfo fieldInfo,
        CentroidSupplier centroidSupplier,
        float[] globalCentroid,
        CentroidOffsetAndLength centroidOffsetAndLength,
        IndexOutput centroidOutput,
        long fieldCentroidStart
    ) throws IOException {
        final int dimension = fieldInfo.getVectorDimension();
        final int numCentroids = centroidSupplier.size();
        final VectorSimilarityFunction similarityFunction = fieldInfo.getVectorSimilarityFunction();

        // The posting lists are quantized relative to the second-level (parent) centroids whenever those
        // exist (see buildAndWritePostingsLists). To keep the query quantization consistent with the
        // postings, write those parent centroids + each fine centroid's parent ordinal so the posting
        // visitor quantizes the query against the matching parent (rather than the global centroid).
        final KMeansResult<float[]> secondLevel = centroidSupplier.secondLevelClusters();
        final boolean hasParents = secondLevel.centroidsSupplier().size() > 1;
        final int[] parentOf;
        final float[][] parentCentroids;
        final int numParents;
        final int maxVectorsPerParent;
        if (hasParents) {
            final CentroidGroups groups = buildCentroidGroups(secondLevel);
            parentCentroids = groups.centroids();
            numParents = parentCentroids.length;
            maxVectorsPerParent = groups.maxVectorsPerCentroidLength();
            parentOf = new int[numCentroids];
            final int[][] perParent = groups.vectors();
            for (int p = 0; p < perParent.length; p++) {
                for (int fineOrd : perParent[p]) {
                    parentOf[fineOrd] = p;
                }
            }
        } else {
            parentOf = null;
            parentCentroids = null;
            numParents = 0;
            maxVectorsPerParent = 0;
        }
        centroidOutput.writeVInt(numParents);
        writeSlicesOffsets(centroidOutput, centroidSupplier.slices());
        if (numParents > 0) {
            // layout expected by the posting visitor's parent path: [VInt longestPostingList][raw parents]
            centroidOutput.writeVInt(maxVectorsPerParent);
            final ByteBuffer parentBuffer = ByteBuffer.allocate(dimension * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            for (float[] parent : parentCentroids) {
                parentBuffer.asFloatBuffer().put(parent);
                centroidOutput.writeBytes(parentBuffer.array(), parentBuffer.array().length);
            }
        }

        // Quantize all centroids to 7-bit OSQ against the global centroid into flat records (used for
        // search-time scoring), and keep the raw centroids to bootstrap the graph from neighbourhoods.
        final OptimizedScalarQuantizer osq = new OptimizedScalarQuantizer(similarityFunction);
        final float[][] rawCentroids = new float[numCentroids][];
        final ByteBuffersDataOutput flatBuffer = new ByteBuffersDataOutput();
        final int[] quantizeScratch = new int[dimension];
        final float[] residualScratch = new float[dimension];
        final byte[] recordCorrections = new byte[3 * Float.BYTES + Integer.BYTES];
        final ByteBuffer correctionsBuffer = ByteBuffer.wrap(recordCorrections).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < numCentroids; i++) {
            float[] centroid = centroidSupplier.centroid(i);
            rawCentroids[i] = centroid.clone();
            OptimizedScalarQuantizer.QuantizationResult result = osq.scalarQuantize(
                centroid,
                residualScratch,
                quantizeScratch,
                (byte) 7,
                globalCentroid
            );
            for (int d = 0; d < dimension; d++) {
                flatBuffer.writeByte((byte) quantizeScratch[d]);
            }
            correctionsBuffer.clear();
            correctionsBuffer.putFloat(result.lowerInterval());
            correctionsBuffer.putFloat(result.upperInterval());
            correctionsBuffer.putFloat(result.additionalCorrection());
            correctionsBuffer.putInt(result.quantizedComponentSum());
            flatBuffer.writeBytes(recordCorrections, 0, recordCorrections.length);
        }

        // persist: flat centroid records, then the posting offset/length table, then the graph
        graphFlatCentroidsOffset = centroidOutput.getFilePointer() - fieldCentroidStart;
        centroidOutput.copyBytes(flatBuffer.toDataInput(), flatBuffer.size());
        graphPostingTableOffset = centroidOutput.getFilePointer() - fieldCentroidStart;
        for (int i = 0; i < numCentroids; i++) {
            centroidOutput.writeLong(centroidOffsetAndLength.offsets().get(i));
            centroidOutput.writeLong(centroidOffsetAndLength.lengths().get(i));
            // parent ordinal for query quantization at posting-visit time (NO_ORDINAL => global centroid)
            centroidOutput.writeInt(numParents > 0 ? parentOf[i] : PostingMetadata.NO_ORDINAL);
        }
        graphSerializedOffset = centroidOutput.getFilePointer() - fieldCentroidStart;
        // Build a multi-level navigable graph over the centroids by diversity-pruning their nearest
        // neighbours (no per-node graph search). We reuse the neighbourhoods the clustering already computed
        // (now correctly remapped through empty-cluster removal) for level 0, and only recompute them in the
        // fallback case where clustering did not compute them (small centroid counts). Upper levels give the
        // logarithmic navigation that a flat graph lacks once there are many centroids.
        final NeighborHood[] threaded = currentCentroidNeighborhoods;
        currentCentroidNeighborhoods = null;
        final NeighborHood[] neighborhoods;
        if (numCentroids <= 1) {
            neighborhoods = null; // buildMultiLevelFromNeighborhoods handles the trivial graph
        } else if (threaded != null && threaded.length >= numCentroids) {
            neighborhoods = threaded; // reuse clustering's neighbourhoods, no recomputation
        } else {
            final int neighborCandidates = Math.min(numCentroids - 1, CENTROID_GRAPH_NEIGHBOR_CANDIDATES);
            neighborhoods = mergeExec != null
                ? NeighborHood.computeNeighborhoods(mergeExec, numMergeWorkers, rawCentroids, neighborCandidates)
                : NeighborHood.computeNeighborhoods(rawCentroids, neighborCandidates);
        }
        final CentroidGraphIO.MultiLevelAdjacency graph = CentroidGraphIO.buildMultiLevelFromNeighborhoods(
            neighborhoods,
            rawCentroids,
            centroidHnswM,
            CENTROID_GRAPH_SEED
        );
        CentroidGraphIO.writeMultiLevelGraph(centroidOutput, graph);
        graphSerializedLength = centroidOutput.getFilePointer() - fieldCentroidStart - graphSerializedOffset;
    }

    private KMeansResult<float[]> buildSecondLevelClusters(
        FieldInfo fieldInfo,
        ClusteringFloatVectorValues floatVectorValues,
        boolean isMerge
    ) throws IOException {
        // we use the HierarchicalKMeans to partition the space of all vectors across merging segments
        // this are small numbers so we run it wih all the centroids.
        HierarchicalKMeans<float[]> hierarchicalKMeans;
        if (isMerge && mergeExec != null) {
            hierarchicalKMeans = HierarchicalKMeans.ofConcurrent(
                CentroidOps.FLOAT,
                fieldInfo.getVectorDimension(),
                mergeExec,
                numMergeWorkers,
                HierarchicalKMeans.MAX_ITERATIONS_DEFAULT,
                HierarchicalKMeans.SAMPLES_PER_CLUSTER_DEFAULT,
                HierarchicalKMeans.MAXK,
                -1 // disable SOAR assignments
            );
        } else {
            hierarchicalKMeans = HierarchicalKMeans.ofSerial(
                CentroidOps.FLOAT,
                fieldInfo.getVectorDimension(),
                HierarchicalKMeans.MAX_ITERATIONS_DEFAULT,
                HierarchicalKMeans.SAMPLES_PER_CLUSTER_DEFAULT,
                HierarchicalKMeans.MAXK,
                -1 // disable SOAR assignments
            );
        }
        return hierarchicalKMeans.cluster(floatVectorValues, centroidsPerParentCluster);
    }

    private CentroidGroups buildCentroidGroups(KMeansResult<float[]> kMeansResult) {
        final int[] centroidVectorCount = new int[kMeansResult.centroids().length];
        for (int i = 0; i < kMeansResult.assignments().length; i++) {
            centroidVectorCount[kMeansResult.assignments()[i]]++;
        }
        final int[][] vectorsPerCentroid = new int[kMeansResult.centroids().length][];
        int maxVectorsPerCentroidLength = 0;
        for (int i = 0; i < kMeansResult.centroidsSupplier().size(); i++) {
            vectorsPerCentroid[i] = new int[centroidVectorCount[i]];
            maxVectorsPerCentroidLength = Math.max(maxVectorsPerCentroidLength, centroidVectorCount[i]);
        }
        Arrays.fill(centroidVectorCount, 0);
        for (int i = 0; i < kMeansResult.assignments().length; i++) {
            final int c = kMeansResult.assignments()[i];
            vectorsPerCentroid[c][centroidVectorCount[c]++] = i;
        }
        return new CentroidGroups(kMeansResult.centroids(), vectorsPerCentroid, maxVectorsPerCentroidLength);
    }

    @Override
    public CentroidAssignments calculateCentroids(FieldInfo fieldInfo, KMeansFloatVectorValues floatVectorValues, MergeState mergeState)
        throws IOException {
        // TODO: consider hinting / bootstrapping hierarchical kmeans with the prior segments centroids
        // TODO: for flush we are doing this over the vectors and here centroids which seems duplicative
        // preliminary tests suggest recall is good using only centroids but need to do further evaluation
        currentCentroidNeighborhoods = null;
        currentReplicas = null;
        currentReplicaDists = null;
        HierarchicalKMeans<float[]> hierarchicalKMeans;
        if (mergeExec != null) {
            hierarchicalKMeans = HierarchicalKMeans.ofConcurrent(
                CentroidOps.FLOAT,
                floatVectorValues.dimension(),
                mergeExec,
                numMergeWorkers
            );
        } else {
            hierarchicalKMeans = HierarchicalKMeans.ofSerial(CentroidOps.FLOAT, floatVectorValues.dimension());
        }
        if (sliceField == null) { // no slice
            KMeansResult<float[]> kMeansResult = calculateCentroids(hierarchicalKMeans, floatVectorValues);
            currentCentroidNeighborhoods = kMeansResult.neighborhoods();
            computeReplicas(kMeansResult, floatVectorValues);
            if (logger.isDebugEnabled()) {
                logger.debug("final centroid count: {}", kMeansResult.centroids().length);
            }
            return new CentroidAssignments(
                fieldInfo.getVectorDimension(),
                kMeansResult.centroids(),
                kMeansResult.assignments(),
                kMeansResult.soarAssignments()
            );
        } else {
            final FieldInfo slicedFieldInfo = mergeState.mergeFieldInfos.fieldInfo(sliceField);
            assert slicedFieldInfo != null;
            assert slicedFieldInfo.getDocValuesType() == DocValuesType.SORTED : "sliceField must be SortedDocValues";
            final SortedDocValues values = DocValueConsumerHelper.INSTANCE.getMergeSortedField(slicedFieldInfo, mergeState);
            final int numSlices = values.getValueCount();
            final KnnVectorValues.DocIndexIterator iterator = floatVectorValues.iterator();
            iterator.advance(0);
            values.nextDoc();
            // slice field must be dense populated, but we might have documents without a vector.
            final int[] sliceOffsets = new int[numSlices];
            final int[] sliceLengths = new int[numSlices];
            List<KMeansResult<float[]>> kmeansResults = new ArrayList<>();
            for (int i = 0; i < numSlices; i++) {
                if (iterator.docID() == DocIdSetIterator.NO_MORE_DOCS) {
                    // no more vectors, we are done
                    sliceLengths[i] = 0;
                    sliceOffsets[i] = i == 0 ? 0 : sliceOffsets[i - 1];
                    continue;
                }
                // get start and end of an slice
                int sliceDocStart = values.docID();
                while (values.docID() != DocIdSetIterator.NO_MORE_DOCS && values.ordValue() == i) {
                    values.nextDoc();
                }
                final int sliceDocEnd = values.docID();
                // get the vector ordinals for the slice
                int vectorDocStart = iterator.docID();
                if (vectorDocStart < sliceDocStart) {
                    // advance iterator to the beginning of the slice
                    vectorDocStart = iterator.advance(sliceDocStart);
                }
                if (vectorDocStart > sliceDocEnd) {
                    // no vectors in this slice
                    sliceLengths[i] = 0;
                    sliceOffsets[i] = i == 0 ? 0 : sliceOffsets[i - 1];
                    continue;
                }
                final int vectorOrdStart = iterator.index();
                final int docEnd = vectorDocStart == sliceDocEnd ? sliceDocEnd : iterator.advance(sliceDocEnd);
                final int vectorOrdEnd = docEnd == KnnVectorValues.DocIndexIterator.NO_MORE_DOCS
                    ? floatVectorValues.size()
                    : iterator.index();
                final int sliceNumVectors = vectorOrdEnd - vectorOrdStart;
                final ClusteringFloatVectorValuesSlice slice = new ClusteringFloatVectorValuesSlice(
                    floatVectorValues,
                    j -> vectorOrdStart + j,
                    sliceNumVectors
                );
                final KMeansResult<float[]> kMeansResult = calculateCentroids(hierarchicalKMeans, slice);
                kmeansResults.add(kMeansResult);
                sliceLengths[i] = sliceNumVectors;
                sliceOffsets[i] = i == 0 ? kMeansResult.centroids().length : sliceOffsets[i - 1] + kMeansResult.centroids().length;
            }
            final KMeansResult<float[]> merged = KMeansResult.merge(kmeansResults, CentroidOps.FLOAT);
            if (logger.isDebugEnabled()) {
                logger.debug("final centroid count: {}", merged.centroids().length);
            }
            final CentroidSlices centroidSlices = new CentroidSlices(sliceOffsets, sliceLengths);
            return new CentroidAssignments(
                floatVectorValues.dimension(),
                merged.centroids(),
                merged.assignments(),
                merged.soarAssignments(),
                centroidSlices
            );
        }
    }

    // This class helps to access the merged view of a slice.
    private static class DocValueConsumerHelper extends DocValuesConsumer {

        static final DocValueConsumerHelper INSTANCE = new DocValueConsumerHelper();

        public SortedDocValues getMergeSortedField(FieldInfo fieldInfo, final MergeState mergeState) throws IOException {
            // This is the magic to get a merged view from the segments.
            final OrdinalMap map = createOrdinalMapForSortedDV(fieldInfo, mergeState);
            return getMergedSortedSetDocValues(fieldInfo, mergeState, map);
        }

        @Override
        public void addNumericField(FieldInfo field, DocValuesProducer valuesProducer) {
            throw new AssertionError("Method should not be called");
        }

        @Override
        public void addBinaryField(FieldInfo field, DocValuesProducer valuesProducer) {
            throw new AssertionError("Method should not be called");
        }

        @Override
        public void addSortedField(FieldInfo field, DocValuesProducer valuesProducer) {
            throw new AssertionError("Method should not be called");
        }

        @Override
        public void addSortedNumericField(FieldInfo field, DocValuesProducer valuesProducer) {
            throw new AssertionError("Method should not be called");
        }

        @Override
        public void addSortedSetField(FieldInfo field, DocValuesProducer valuesProducer) {
            throw new AssertionError("Method should not be called");
        }

        @Override
        public void close() {
            throw new AssertionError("Method should not be called");
        }
    }

    /**
     * Calculate the centroids for the given field.
     * We use the {@link HierarchicalKMeans} algorithm to partition the space of all vectors across merging segments
     *
     * @param fieldInfo merging field info
     * @param floatVectorValues the float vector values to merge
     * @return the vector assignments, soar assignments, and if asked the centroids themselves that were computed
     * @throws IOException if an I/O error occurs
     */
    @Override
    public CentroidAssignments calculateCentroids(FieldInfo fieldInfo, KMeansFloatVectorValues floatVectorValues) throws IOException {
        currentCentroidNeighborhoods = null;
        currentReplicas = null;
        currentReplicaDists = null;
        if (sliceField != null) {
            // for sliced indexed, we don't cluster the data during flush so we can search our vectors by docId range
            return buildFlatCentroidAssignments(fieldInfo, floatVectorValues);
        }
        HierarchicalKMeans<float[]> hierarchicalKMeans = HierarchicalKMeans.ofSerial(CentroidOps.FLOAT, floatVectorValues.dimension());
        KMeansResult<float[]> kMeansResult = calculateCentroids(hierarchicalKMeans, floatVectorValues);
        currentCentroidNeighborhoods = kMeansResult.neighborhoods();
        computeReplicas(kMeansResult, floatVectorValues);
        if (logger.isDebugEnabled()) {
            logger.debug("final centroid count: {}", kMeansResult.centroids().length);
        }
        return new CentroidAssignments(
            fieldInfo.getVectorDimension(),
            kMeansResult.centroids(),
            kMeansResult.assignments(),
            kMeansResult.soarAssignments()
        );
    }

    private KMeansResult<float[]> calculateCentroids(
        HierarchicalKMeans<float[]> hierarchicalKMeans,
        ClusteringFloatVectorValues floatVectorValues
    ) throws IOException {
        return hierarchicalKMeans.cluster(floatVectorValues, vectorPerCluster);
    }

    /**
     * Experiment-only: when the centroid graph and SPANN overspill are enabled, assign each vector to up to
     * {@code replicaCount} centroids via centroid-graph search + RNG diversity, stashing the result for
     * {@link #buildAndWritePostingsLists} to write (and trim). Otherwise leaves the replicas unset so the
     * default single-SOAR overspill is used.
     */
    private void computeReplicas(KMeansResult<float[]> kMeansResult, FloatVectorValues vectors) throws IOException {
        if (indexCentroidsInGraph == false || spannParams.enabled() == false || kMeansResult.centroids().length <= 1) {
            return;
        }
        final SpannOverspill.Replicas replicas = SpannOverspill.assign(
            vectors,
            kMeansResult.centroids(),
            kMeansResult.assignments(),
            spannParams.replicaCount(),
            spannParams.internalResultNum(),
            spannParams.rngFactor(),
            spannParams.closureEpsilon(),
            centroidHnswM,
            centroidHnswBeamWidth
        );
        currentReplicas = replicas.centroidsPerVector();
        currentReplicaDists = replicas.sqDistPerVector();
    }

    /**
     * Builds per-cluster membership from the SPANN replica assignments, trimming postings that exceed
     * {@code maxPostingFactor * vectorPerCluster} by keeping the nearest members (SPANN posting cut).
     * Returns the vector ords per cluster; the on-the-fly quantizer re-quantizes each vector against its
     * cluster centroid, so no per-replica slot bookkeeping is needed. Clears the stashed replicas.
     */
    private int[][] buildReplicaMembership(int numCentroids) {
        final int[][] replicas = currentReplicas;
        final float[][] dists = currentReplicaDists;
        currentReplicas = null;
        currentReplicaDists = null;
        final int[] counts = new int[numCentroids];
        for (int[] perVector : replicas) {
            for (int centroid : perVector) {
                counts[centroid]++;
            }
        }
        final int[][] memberOrds = new int[numCentroids][];
        final float[][] memberDists = new float[numCentroids][];
        for (int c = 0; c < numCentroids; c++) {
            memberOrds[c] = new int[counts[c]];
            memberDists[c] = new float[counts[c]];
        }
        final int[] fill = new int[numCentroids];
        for (int v = 0; v < replicas.length; v++) {
            final int[] perVector = replicas[v];
            for (int r = 0; r < perVector.length; r++) {
                final int c = perVector[r];
                memberOrds[c][fill[c]] = v;
                memberDists[c][fill[c]] = dists[v][r];
                fill[c]++;
            }
        }
        final int trimCap = spannParams.maxPostingFactor() > 0
            ? Math.max(1, (int) (spannParams.maxPostingFactor() * vectorPerCluster))
            : Integer.MAX_VALUE;
        final int[][] membership = new int[numCentroids][];
        for (int c = 0; c < numCentroids; c++) {
            if (memberOrds[c].length <= trimCap) {
                membership[c] = memberOrds[c];
            } else {
                // keep the trimCap nearest members (primaries, being nearest, survive)
                final Integer[] order = new Integer[memberOrds[c].length];
                for (int i = 0; i < order.length; i++) {
                    order[i] = i;
                }
                final float[] d = memberDists[c];
                Arrays.sort(order, (a, b) -> Float.compare(d[a], d[b]));
                final int[] kept = new int[trimCap];
                for (int i = 0; i < trimCap; i++) {
                    kept[i] = memberOrds[c][order[i]];
                }
                membership[c] = kept;
            }
        }
        return membership;
    }

    static void writeQuantizedValue(IndexOutput indexOutput, byte[] binaryValue, OptimizedScalarQuantizer.QuantizationResult corrections)
        throws IOException {
        indexOutput.writeBytes(binaryValue, binaryValue.length);
        indexOutput.writeInt(Float.floatToIntBits(corrections.lowerInterval()));
        indexOutput.writeInt(Float.floatToIntBits(corrections.upperInterval()));
        indexOutput.writeInt(Float.floatToIntBits(corrections.additionalCorrection()));
        indexOutput.writeInt(corrections.quantizedComponentSum());
    }

    static class OffHeapCentroidSupplier implements CentroidSupplier {
        private final IndexInput centroidsInput;
        private final int numCentroids;
        private final int dimension;
        private final float[] scratch;
        private final KMeansResult<float[]> clusters;
        private final CentroidSlices centroidSlices;
        private int currOrd = -1;

        OffHeapCentroidSupplier(
            IndexInput centroidsInput,
            int numCentroids,
            FieldInfo info,
            KMeansResult<float[]> clusters,
            CentroidSlices centroidSlices
        ) {
            this.centroidsInput = centroidsInput;
            this.numCentroids = numCentroids;
            this.dimension = info.getVectorDimension();
            this.scratch = new float[dimension];
            this.clusters = clusters;
            this.centroidSlices = centroidSlices;
        }

        @Override
        public int size() {
            return numCentroids;
        }

        @Override
        public float[] centroid(int centroidOrdinal) throws IOException {
            if (centroidOrdinal == currOrd) {
                return scratch;
            }
            centroidsInput.seek((long) centroidOrdinal * dimension * Float.BYTES);
            centroidsInput.readFloats(scratch, 0, dimension);
            this.currOrd = centroidOrdinal;
            return scratch;
        }

        @Override
        public KMeansResult<float[]> secondLevelClusters() {
            return clusters;
        }

        @Override
        public CentroidSlices slices() throws IOException {
            return centroidSlices;
        }

        @Override
        public KMeansFloatVectorValues asKmeansFloatVectorValues() throws IOException {
            return KMeansFloatVectorValues.build(centroidsInput, null, numCentroids, dimension);
        }
    }

    static class QuantizedCentroids implements QuantizedVectorValues {
        private final CentroidSupplier supplier;
        private final OptimizedScalarQuantizer quantizer;
        private final byte[] quantizedVector;
        private final int[] quantizedVectorScratch;
        private final float[] floatVectorScratch;
        private OptimizedScalarQuantizer.QuantizationResult corrections;
        private final float[] centroid;
        private int currOrd = -1;
        private IntToIntFunction ordTransformer = i -> i;
        int size;

        QuantizedCentroids(CentroidSupplier supplier, int dimension, OptimizedScalarQuantizer quantizer, float[] centroid) {
            this.supplier = supplier;
            this.quantizer = quantizer;
            this.quantizedVector = new byte[dimension];
            this.floatVectorScratch = new float[dimension];
            this.quantizedVectorScratch = new int[dimension];
            this.centroid = centroid;
            size = supplier.size();
        }

        @Override
        public int count() {
            return size;
        }

        void reset(IntToIntFunction ordTransformer, int size) {
            this.ordTransformer = ordTransformer;
            this.currOrd = -1;
            this.size = size;
            this.corrections = null;
        }

        @Override
        public byte[] next() throws IOException {
            if (currOrd >= count() - 1) {
                throw new IllegalStateException("No more vectors to read, current ord: " + currOrd + ", count: " + count());
            }
            currOrd++;
            float[] vector = supplier.centroid(ordTransformer.apply(currOrd));
            corrections = quantizer.scalarQuantize(vector, floatVectorScratch, quantizedVectorScratch, (byte) 7, centroid);
            for (int i = 0; i < quantizedVectorScratch.length; i++) {
                quantizedVector[i] = (byte) quantizedVectorScratch[i];
            }
            return quantizedVector;
        }

        @Override
        public OptimizedScalarQuantizer.QuantizationResult getCorrections() {
            return corrections;
        }
    }

    static class OnHeapQuantizedVectors implements QuantizedVectorValues {
        private final FloatVectorValues vectorValues;
        private final OptimizedScalarQuantizer quantizer;
        private final byte[] quantizedVector;
        private final int[] quantizedVectorScratch;
        private final float[] floatVectorScratch;
        private final ESNextDiskBBQVectorsFormat.QuantEncoding encoding;
        private OptimizedScalarQuantizer.QuantizationResult corrections;
        private final VectorSimilarityFunction similarityFunction;
        private float[] currentCentroid, currentParentCentroid;
        private IntToIntFunction ordTransformer = null;
        private int currOrd = -1;
        private int count;

        OnHeapQuantizedVectors(
            FloatVectorValues vectorValues,
            VectorSimilarityFunction similarityFunction,
            ESNextDiskBBQVectorsFormat.QuantEncoding encoding,
            int dimension,
            OptimizedScalarQuantizer quantizer
        ) {
            this.vectorValues = vectorValues;
            this.similarityFunction = similarityFunction;
            this.encoding = encoding;
            this.quantizer = quantizer;
            this.quantizedVector = new byte[encoding.getDocPackedLength(dimension)];
            this.floatVectorScratch = new float[dimension];
            this.quantizedVectorScratch = new int[encoding.discretizedDimensions(dimension)];
            this.corrections = null;
            this.currentParentCentroid = null;
        }

        private void reset(float[] centroid, float[] currentParentCentroid, int count, IntToIntFunction ordTransformer) {
            this.currentCentroid = centroid;
            this.ordTransformer = ordTransformer;
            this.currOrd = -1;
            this.count = count;
            this.currentParentCentroid = currentParentCentroid;
        }

        @Override
        public int count() {
            return count;
        }

        @Override
        public byte[] next() throws IOException {
            if (currOrd >= count() - 1) {
                throw new IllegalStateException("No more vectors to read, current ord: " + currOrd + ", count: " + count());
            }
            currOrd++;
            int ord = ordTransformer.apply(currOrd);
            float[] vector = vectorValues.vectorValue(ord);
            corrections = quantizer.scalarQuantize(vector, floatVectorScratch, quantizedVectorScratch, encoding.bits(), currentCentroid);
            // note, with a parent centroid, our correction needs to take it into account
            if (currentParentCentroid != null) {
                float additionalCorrection = similarityFunction == VectorSimilarityFunction.EUCLIDEAN
                    ? ESVectorUtil.squareDistance(vector, currentParentCentroid)
                    : ESVectorUtil.dotProduct(floatVectorScratch, currentParentCentroid);
                corrections = new OptimizedScalarQuantizer.QuantizationResult(
                    corrections.lowerInterval(),
                    corrections.upperInterval(),
                    additionalCorrection,
                    corrections.quantizedComponentSum()
                );
            }
            encoding.pack(quantizedVectorScratch, quantizedVector);
            return quantizedVector;
        }

        @Override
        public OptimizedScalarQuantizer.QuantizationResult getCorrections() {
            if (currOrd == -1) {
                throw new IllegalStateException("No vector read yet, call next first");
            }
            return corrections;
        }
    }

    static class OffHeapQuantizedVectors implements QuantizedVectorValues {
        private final IndexInput quantizedVectorsInput;
        private final byte[] binaryScratch;
        private final float[] corrections = new float[3];

        private final int vectorByteSize;
        private int bitSum;
        private int currOrd = -1;
        private int count;
        private IntToBooleanFunction isOverspill = null;
        private IntToIntFunction ordTransformer = null;

        OffHeapQuantizedVectors(IndexInput quantizedVectorsInput, ESNextDiskBBQVectorsFormat.QuantEncoding encoding, int dimension) {
            this.quantizedVectorsInput = quantizedVectorsInput;
            this.binaryScratch = new byte[encoding.getDocPackedLength(dimension)];
            this.vectorByteSize = (binaryScratch.length + 3 * Float.BYTES + Integer.BYTES);
        }

        private void reset(int count, IntToBooleanFunction isOverspill, IntToIntFunction ordTransformer) {
            this.count = count;
            this.isOverspill = isOverspill;
            this.ordTransformer = ordTransformer;
            this.currOrd = -1;
        }

        @Override
        public int count() {
            return count;
        }

        @Override
        public byte[] next() throws IOException {
            if (currOrd >= count - 1) {
                throw new IllegalStateException("No more vectors to read, current ord: " + currOrd + ", count: " + count);
            }
            currOrd++;
            int ord = ordTransformer.apply(currOrd);
            boolean isOverspill = this.isOverspill.apply(currOrd);
            return getVector(ord, isOverspill);
        }

        @Override
        public OptimizedScalarQuantizer.QuantizationResult getCorrections() {
            if (currOrd == -1) {
                throw new IllegalStateException("No vector read yet, call readQuantizedVector first");
            }
            return new OptimizedScalarQuantizer.QuantizationResult(corrections[0], corrections[1], corrections[2], bitSum);
        }

        byte[] getVector(int ord, boolean isOverspill) throws IOException {
            readQuantizedVector(ord, isOverspill);
            return binaryScratch;
        }

        public void readQuantizedVector(int ord, boolean isOverspill) throws IOException {
            long offset = (long) ord * (vectorByteSize * 2L) + (isOverspill ? vectorByteSize : 0);
            quantizedVectorsInput.seek(offset);
            quantizedVectorsInput.readBytes(binaryScratch, 0, binaryScratch.length);
            quantizedVectorsInput.readFloats(corrections, 0, 3);
            bitSum = quantizedVectorsInput.readInt();
        }
    }
}
