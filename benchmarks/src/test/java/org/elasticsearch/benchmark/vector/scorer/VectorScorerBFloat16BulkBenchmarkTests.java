/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.vector.scorer;

import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;

import org.apache.lucene.util.Constants;
import org.elasticsearch.simdvec.VectorSimilarityType;
import org.elasticsearch.test.ESTestCase;
import org.junit.BeforeClass;
import org.openjdk.jmh.annotations.Param;

import java.util.Arrays;

public class VectorScorerBFloat16BulkBenchmarkTests extends ESTestCase {

    private final double delta = 1e-3;
    private final VectorSimilarityType function;
    private final int dims;

    public VectorScorerBFloat16BulkBenchmarkTests(VectorSimilarityType function, int dims) {
        this.function = function;
        this.dims = dims;
    }

    @BeforeClass
    public static void skipWindows() {
        assumeFalse("doesn't work on windows yet", Constants.WINDOWS);
    }

    public void testBulkScores() throws Exception {
        for (int i = 0; i < 5; i++) {
            VectorScorerBFloat16BulkBenchmark.VectorData data = new VectorScorerBFloat16BulkBenchmark.VectorData(dims, 128, 128);
            float[][] expected = new float[VectorImplementation.values().length][];
            int implIdx = 0;
            for (var impl : VectorImplementation.values()) {
                var bench = new VectorScorerBFloat16BulkBenchmark();
                bench.function = function;
                bench.implementation = impl;
                bench.dims = dims;
                bench.numVectors = 128;
                bench.bulkSize = 32;
                bench.setup(data);

                try {
                    float[] result = bench.scoreMultipleSequentialBulk();
                    expected[implIdx++] = result.clone();
                } finally {
                    bench.teardown();
                }
            }

            for (int j = 1; j < expected.length; j++) {
                assertArrayEquals(VectorImplementation.values()[j].toString(), expected[0], expected[j], (float) delta);
            }
        }
    }

    @ParametersFactory
    public static Iterable<Object[]> parametersFactory() {
        try {
            String[] dims = VectorScorerBFloat16BulkBenchmark.class.getField("dims").getAnnotationsByType(Param.class)[0].value();
            String[] functions = VectorScorerBFloat16BulkBenchmark.class.getField("function").getAnnotationsByType(Param.class)[0].value();
            return () -> Arrays.stream(dims)
                .map(Integer::parseInt)
                .flatMap(i -> Arrays.stream(functions).map(VectorSimilarityType::valueOf).map(f -> new Object[] { f, i }))
                .iterator();
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }
}
