/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.search.aggregations.bucket;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
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
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks Lucene DocValues access patterns that are common across the
 * aggregation hot paths affected by the JDK 26 regression (ES-14194).
 *
 * The common denominator across all 5 regressing NOAA benchmarks is tight
 * per-document loops calling advanceExact() + longValue()/nextValue() on
 * DocValues. This benchmark isolates that access pattern from aggregation
 * framework overhead.
 *
 * Run with JDK 25 and JDK 26 to compare:
 * {@code ./gradlew :benchmarks:run --args 'DocValuesAccessBenchmark'}
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(2)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
public class DocValuesAccessBenchmark {

    private static final String FIELD = "@timestamp";
    private static final long BASE_TIMESTAMP = 631152000000L;

    @Param({ "100000", "1000000" })
    private int numDocs;

    private Directory directory;
    private DirectoryReader reader;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        directory = FSDirectory.open(Files.createTempDirectory("dv-access-bench-"));
        buildIndex();
    }

    private static final String SORTED_NUMERIC_FIELD = "@timestamp_sorted";

    private void buildIndex() throws IOException {
        IndexWriterConfig config = new IndexWriterConfig();
        Random random = new Random(42);

        try (IndexWriter writer = new IndexWriter(directory, config)) {
            for (int i = 0; i < numDocs; i++) {
                long timestamp = BASE_TIMESTAMP + (long) (random.nextDouble() * 3786912000000L);
                Document doc = new Document();
                doc.add(new LongPoint(FIELD, timestamp));
                doc.add(new NumericDocValuesField(FIELD, timestamp));
                doc.add(new SortedNumericDocValuesField(SORTED_NUMERIC_FIELD, timestamp));
                writer.addDocument(doc);
            }
            writer.commit();
        }
        reader = DirectoryReader.open(directory);
    }

    /**
     * Sequential scan of SortedNumericDocValues — the multi-valued case.
     * This is the pattern in DateHistogramAggregator.getLeafCollector()
     * for multi-valued fields.
     */
    @Benchmark
    public void sortedNumericDocValuesSequentialScan(Blackhole bh) throws IOException {
        for (LeafReaderContext leaf : reader.leaves()) {
            SortedNumericDocValues values = leaf.reader().getSortedNumericDocValues(SORTED_NUMERIC_FIELD);
            if (values == null) continue;
            for (int doc = 0; doc < leaf.reader().maxDoc(); doc++) {
                if (values.advanceExact(doc)) {
                    for (int j = 0; j < values.docValueCount(); j++) {
                        bh.consume(values.nextValue());
                    }
                }
            }
        }
    }

    /**
     * Sequential scan of NumericDocValues — the singleton-unwrapped case.
     * This is the fast path in DateHistogramAggregator.getLeafCollector()
     * when SortedNumericLongValues.unwrapSingleton() succeeds.
     */
    @Benchmark
    public void numericDocValuesSequentialScan(Blackhole bh) throws IOException {
        for (LeafReaderContext leaf : reader.leaves()) {
            NumericDocValues values = leaf.reader().getNumericDocValues(FIELD);
            if (values == null) continue;
            for (int doc = 0; doc < leaf.reader().maxDoc(); doc++) {
                if (values.advanceExact(doc)) {
                    bh.consume(values.longValue());
                }
            }
        }
    }

    /**
     * Sequential scan with a simple rounding operation, simulating
     * what the DateHistogramAggregator does: read value + round it.
     * Uses a simple floor-division rounding to isolate Lucene overhead
     * from Elasticsearch rounding overhead.
     */
    @Benchmark
    public void numericDocValuesWithRounding(Blackhole bh) throws IOException {
        long interval = 86400000L; // 1 day in millis
        for (LeafReaderContext leaf : reader.leaves()) {
            NumericDocValues values = leaf.reader().getNumericDocValues(FIELD);
            if (values == null) continue;
            for (int doc = 0; doc < leaf.reader().maxDoc(); doc++) {
                if (values.advanceExact(doc)) {
                    long value = values.longValue();
                    long rounded = value - (value % interval);
                    bh.consume(rounded);
                }
            }
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        if (reader != null) {
            reader.close();
        }
        if (directory != null) {
            directory.close();
        }
    }
}
