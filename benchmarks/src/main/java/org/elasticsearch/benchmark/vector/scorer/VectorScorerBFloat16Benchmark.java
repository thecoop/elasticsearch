/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.vector.scorer;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.VectorUtil;
import org.apache.lucene.util.hnsw.RandomVectorScorer;
import org.apache.lucene.util.hnsw.UpdateableRandomVectorScorer;
import org.elasticsearch.benchmark.Utils;
import org.elasticsearch.core.IOUtils;
import org.elasticsearch.index.codec.vectors.BFloat16;
import org.elasticsearch.simdvec.VectorScorerFactory;
import org.elasticsearch.simdvec.VectorSimilarityType;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.benchmark.vector.scorer.BenchmarkUtils.bfloat16VectorValues;
import static org.elasticsearch.benchmark.vector.scorer.BenchmarkUtils.getScorerFactoryOrDie;
import static org.elasticsearch.benchmark.vector.scorer.BenchmarkUtils.luceneScoreSupplier;
import static org.elasticsearch.benchmark.vector.scorer.BenchmarkUtils.luceneScorer;
import static org.elasticsearch.benchmark.vector.scorer.BenchmarkUtils.supportsHeapSegments;
import static org.elasticsearch.benchmark.vector.scorer.BenchmarkUtils.writeBFloat16VectorData;
import static org.elasticsearch.benchmark.vector.scorer.ScalarOperations.dotProduct;
import static org.elasticsearch.benchmark.vector.scorer.ScalarOperations.squareDistance;

@Fork(value = 1, jvmArgsPrepend = { "--add-modules=jdk.incubator.vector" })
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
public class VectorScorerBFloat16Benchmark {

    static {
        Utils.configureBenchmarkLogging();
    }

    @Param({ "96", "768", "1024" })
    public int dims;
    public static int numVectors = 2; // there are only two vectors to compare

    @Param
    public VectorImplementation implementation;

    @Param({ "DOT_PRODUCT", "EUCLIDEAN" })
    public VectorSimilarityType function;

    private Path path;
    private Directory dir;
    private IndexInput in;

    private static class ScalarDotProduct implements UpdateableRandomVectorScorer {
        private final float[] vec1;
        private final float[] vec2;

        private ScalarDotProduct(float[] vec1, float[] vec2) {
            this.vec1 = vec1;
            this.vec2 = vec2;
        }

        @Override
        public float score(int ordinal) {
            return VectorUtil.normalizeToUnitInterval(dotProduct(vec1, vec2));
        }

        @Override
        public int maxOrd() {
            return 0;
        }

        @Override
        public void setScoringOrdinal(int targetOrd) {}
    }

    private static class ScalarSquareDistance implements UpdateableRandomVectorScorer {
        private final float[] vec1;
        private final float[] vec2;

        private ScalarSquareDistance(float[] vec1, float[] vec2) {
            this.vec1 = vec1;
            this.vec2 = vec2;
        }

        @Override
        public float score(int ordinal) {
            return VectorUtil.normalizeDistanceToUnitInterval(squareDistance(vec1, vec2));
        }

        @Override
        public int maxOrd() {
            return 0;
        }

        @Override
        public void setScoringOrdinal(int targetOrd) {}
    }

    private UpdateableRandomVectorScorer scorer;
    private RandomVectorScorer queryScorer;

    static class VectorData {
        private final float[][] vectorData;
        private final float[] queryVector;

        VectorData(int dims) {
            this(dims, 2);
        }

        VectorData(int dims, int numVectors) {
            vectorData = new float[numVectors][dims];
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int v = 0; v < numVectors; v++) {
                for (int d = 0; d < dims; d++) {
                    vectorData[v][d] = BFloat16.truncateToBFloat16(random.nextFloat());
                }
            }

            queryVector = new float[dims];
            for (int i = 0; i < dims; i++) {
                // query uses full floats
                queryVector[i] = random.nextFloat();
            }
        }

    }

    @Setup
    public void setup() throws IOException {
        setup(new VectorData(dims, numVectors));
    }

    public void setup(VectorData vectorData) throws IOException {
        VectorScorerFactory factory = getScorerFactoryOrDie();

        path = Files.createTempDirectory("BFloat16ScorerBenchmark");
        dir = new MMapDirectory(path);
        writeBFloat16VectorData(dir, vectorData.vectorData);

        in = dir.openInput("vector.data", IOContext.DEFAULT);
        var values = bfloat16VectorValues(dims, numVectors, in, function.function());

        switch (implementation) {
            case SCALAR:
                float[] vec1 = values.vectorValue(0).clone();
                float[] vec2 = values.vectorValue(1).clone();

                scorer = switch (function) {
                    case DOT_PRODUCT -> new ScalarDotProduct(vec1, vec2);
                    case EUCLIDEAN -> new ScalarSquareDistance(vec1, vec2);
                    default -> throw new IllegalArgumentException(function + " not supported");
                };
                break;
            case LUCENE:
                scorer = luceneScoreSupplier(values, function.function()).scorer();
                if (supportsHeapSegments()) {
                    queryScorer = luceneScorer(values, function.function(), vectorData.queryVector);
                }
                break;
            case NATIVE:
                scorer = factory.getBFloat16VectorScorerSupplier(function, in, values).orElseThrow().scorer();
                if (supportsHeapSegments()) {
                    queryScorer = factory.getBFloat16VectorScorer(function.function(), values, vectorData.queryVector).orElseThrow();
                }
                break;
        }

        scorer.setScoringOrdinal(0);
    }

    @TearDown
    public void teardown() throws IOException {
        IOUtils.close(dir, in);
        IOUtils.rm(path);
    }

    @Benchmark
    public float score() throws IOException {
        return scorer.score(1);
    }

    @Benchmark
    public float scoreQuery() throws IOException {
        return queryScorer.score(1);
    }
}
