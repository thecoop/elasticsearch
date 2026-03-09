/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.search.aggregations.bucket;

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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks Arrays.binarySearch on small long arrays, matching the pattern
 * used by Rounding.ArrayRounding.round() — the fastest date histogram
 * rounding path. ArrayRounding pre-calculates up to 128 rounding points
 * then uses binarySearch for O(log n) lookups.
 *
 * JDK changes to Arrays.binarySearch intrinsification or to branch prediction
 * on small arrays could explain the JDK 26 regression in date-histo-entire-range.
 *
 * Run with JDK 25 and JDK 26 to compare:
 * {@code ./gradlew :benchmarks:run --args 'ArrayRoundingBenchmark'}
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(2)
@Warmup(iterations = 10)
@Measurement(iterations = 5)
public class ArrayRoundingBenchmark {

    /**
     * Array size matches realistic fixedRoundingPoints counts:
     * 12 = monthly over 1 year, 120 = monthly over 10 years, 128 = max ArrayRounding size.
     */
    @Param({ "12", "52", "120", "128" })
    private int arraySize;

    /**
     * Number of lookups per benchmark iteration.
     */
    @Param({ "500", "10000" })
    private int lookupCount;

    private long[] roundingPoints;
    private long[] lookupValues;

    @Setup(Level.Trial)
    public void setup() {
        Random random = new Random(42);
        long base = 631152000000L; // 1990-01-01
        long interval = 2592000000L; // ~30 days

        // Build sorted rounding points (like ArrayRounding does)
        roundingPoints = new long[arraySize];
        for (int i = 0; i < arraySize; i++) {
            roundingPoints[i] = base + i * interval;
        }

        // Build random lookup values within the range
        long min = roundingPoints[0];
        long max = roundingPoints[arraySize - 1] + interval;
        lookupValues = new long[lookupCount];
        for (int i = 0; i < lookupCount; i++) {
            lookupValues[i] = min + (long) (random.nextDouble() * (max - min));
        }
    }

    /**
     * Measures Arrays.binarySearch on pre-calculated rounding points,
     * exactly matching the ArrayRounding.round() implementation.
     */
    @Benchmark
    public void arrayRoundingLookup(Blackhole bh) {
        for (int i = 0; i < lookupCount; i++) {
            long utcMillis = lookupValues[i];
            int idx = Arrays.binarySearch(roundingPoints, 0, arraySize, utcMillis);
            if (idx < 0) {
                idx = -2 - idx;
            }
            bh.consume(roundingPoints[idx]);
        }
    }

    /**
     * Sequential array scan as a reference. If the array is small enough,
     * linear scan can beat binary search due to cache/branch effects.
     */
    @Benchmark
    public void linearScanLookup(Blackhole bh) {
        for (int i = 0; i < lookupCount; i++) {
            long utcMillis = lookupValues[i];
            int idx = 0;
            for (int j = 1; j < arraySize; j++) {
                if (roundingPoints[j] <= utcMillis) {
                    idx = j;
                } else {
                    break;
                }
            }
            bh.consume(roundingPoints[idx]);
        }
    }

    /**
     * Measures the floor-division rounding that FixedToMidnightRounding uses
     * as a comparison point — no array needed, just arithmetic.
     */
    @Benchmark
    public void arithmeticRounding(Blackhole bh) {
        long interval = 2592000000L; // ~30 days
        for (int i = 0; i < lookupCount; i++) {
            long utcMillis = lookupValues[i];
            long rounded = utcMillis - (utcMillis % interval);
            bh.consume(rounded);
        }
    }
}
