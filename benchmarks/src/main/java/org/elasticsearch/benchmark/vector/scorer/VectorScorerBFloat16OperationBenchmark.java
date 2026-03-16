/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.benchmark.vector.scorer;

import org.apache.lucene.util.VectorUtil;
import org.elasticsearch.benchmark.Utils;
import org.elasticsearch.index.codec.vectors.BFloat16;
import org.elasticsearch.nativeaccess.NativeAccess;
import org.elasticsearch.nativeaccess.VectorSimilarityFunctions;
import org.elasticsearch.simdvec.VectorSimilarityType;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteOrder;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.benchmark.vector.scorer.BenchmarkUtils.rethrow;

@Fork(value = 3, jvmArgsPrepend = { "--add-modules=jdk.incubator.vector" })
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class VectorScorerBFloat16OperationBenchmark {

    static {
        Utils.configureBenchmarkLogging();
    }

    static final ValueLayout.OfShort LAYOUT_LE_BFLOAT16 = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    float[] floatsA;
    float[] floatsB;
    float[] scratch;
    MemorySegment heapSegA, heapSegB;
    MemorySegment nativeSegA, nativeSegB;

    Arena arena;

    @Param({ "1", "128", "207", "256", "300", "512", "702", "1024", "1536", "2048" })
    public int size;

    @Param({ "DOT_PRODUCT", "EUCLIDEAN" })
    public VectorSimilarityType function;

    @FunctionalInterface
    private interface LuceneFunction {
        float run(float[] vec1, float[] vec2);
    }

    private LuceneFunction luceneImpl;
    private MethodHandle nativeImpl;

    @Setup(Level.Iteration)
    public void init() {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        floatsA = new float[size];
        floatsB = new float[size];
        scratch = new float[size];
        for (int i = 0; i < size; ++i) {
            floatsA[i] = BFloat16.truncateToBFloat16(random.nextFloat());
            floatsB[i] = BFloat16.truncateToBFloat16(random.nextFloat());
        }
        heapSegA = MemorySegment.ofArray(floatsA);
        heapSegB = MemorySegment.ofArray(floatsB);

        arena = Arena.ofConfined();
        nativeSegA = arena.allocate((long) floatsA.length * BFloat16.BYTES);
        copyToBFloat16Segment(floatsA, nativeSegA, 0L);
        nativeSegB = arena.allocate((long) floatsB.length * BFloat16.BYTES);
        copyToBFloat16Segment(floatsB, nativeSegB, 0L);

        luceneImpl = switch (function) {
            case DOT_PRODUCT -> VectorUtil::dotProduct;
            case EUCLIDEAN -> VectorUtil::squareDistance;
            default -> throw new UnsupportedOperationException("Not used");
        };
        nativeImpl = vectorSimilarityFunctions.getHandle(switch (function) {
            case DOT_PRODUCT -> VectorSimilarityFunctions.Function.DOT_PRODUCT;
            case EUCLIDEAN -> VectorSimilarityFunctions.Function.SQUARE_DISTANCE;
            default -> throw new IllegalArgumentException(function.toString());
        }, VectorSimilarityFunctions.DataType.BFLOAT16, VectorSimilarityFunctions.Operation.SINGLE);
    }

    private static void copyToBFloat16Segment(float[] fa, MemorySegment segment, long offset) {
        for (int i = 0; i < fa.length; i++) {
            short bfloat16 = BFloat16.floatToBFloat16(fa[i]);
            segment.set(LAYOUT_LE_BFLOAT16, offset + (long) i * BFloat16.BYTES, bfloat16);
        }
    }

    @TearDown
    public void teardown() {
        arena.close();
    }

    @Benchmark
    public float lucene() {
        return luceneImpl.run(floatsA, floatsB);
    }

    @Benchmark
    public float luceneWithCopy() {
        // add a copy to better reflect what Lucene has to do to get the target vector on-heap
        for (int i = 0; i < size; i++) {
            scratch[i] = BFloat16.bFloat16ToFloat(nativeSegB.get(LAYOUT_LE_BFLOAT16, (long) i * BFloat16.BYTES));
        }
        return luceneImpl.run(floatsA, scratch);
    }

    @Benchmark
    public float nativeWithNativeSeg() {
        try {
            return (float) nativeImpl.invokeExact(nativeSegA, nativeSegB, size);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Benchmark
    public float nativeWithHeapSeg() {
        try {
            return (float) nativeImpl.invokeExact(heapSegA, heapSegB, size);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static final VectorSimilarityFunctions vectorSimilarityFunctions = NativeAccess.instance().getVectorSimilarityFunctions().orElseThrow();
}
