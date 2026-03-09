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
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortedNumericSortField;
import org.apache.lucene.search.Weight;
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
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks the core operations of the date histogram aggregation's hot path,
 * focusing on Lucene-level weight().count() for PointRangeQuery — the critical
 * path exercised by FilterByFilterAggregator when date histogram delegates to
 * range aggregation via fixedRoundingPoints.
 *
 * This benchmark was created to investigate the JDK 26 regression observed in
 * NOAA benchmarks (ES-14194), specifically the date-histo-entire-range task
 * which showed a 15-20% latency regression compared to JDK 25.
 *
 * Run with JDK 25 and JDK 26 to compare:
 * {@code ./gradlew :benchmarks:run --args 'DateHistogramAggregatorBenchmark'}
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(2)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
public class DateHistogramAggregatorBenchmark {

    private static final String TIMESTAMP_FIELD = "@timestamp";

    /**
     * NOAA dataset spans ~120 years. Use different scales to isolate performance.
     */
    @Param({ "10000", "100000", "1000000" })
    private int numDocs;

    /**
     * Number of date histogram buckets (rounding points).
     * "entire-range" with yearly buckets over 120y would be ~120 buckets.
     */
    @Param({ "12", "120" })
    private int numBuckets;

    /**
     * Number of Lucene segments — affects FilterByFilterAggregator iteration.
     */
    @Param({ "1", "5" })
    private int numSegments;

    private static final long BASE_TIMESTAMP = 631152000000L; // 1990-01-01T00:00:00Z
    private static final long TIME_RANGE = 3786912000000L; // ~120 years in millis

    private Directory directory;
    private DirectoryReader reader;
    private IndexSearcher searcher;
    private long[] bucketBoundaries;
    private Query[] rangeQueries;
    private Weight[] rangeWeights;
    private Weight matchAllWeight;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        directory = FSDirectory.open(Files.createTempDirectory("date-histo-bench-"));
        buildIndex();
        buildQueries();
    }

    private void buildIndex() throws IOException {
        IndexWriterConfig config = new IndexWriterConfig();
        config.setIndexSort(new Sort(new SortedNumericSortField(TIMESTAMP_FIELD, SortedNumericSortField.Type.LONG)));

        int docsPerSegment = numDocs / numSegments;
        Random random = new Random(42);

        try (IndexWriter writer = new IndexWriter(directory, config)) {
            for (int i = 0; i < numDocs; i++) {
                long timestamp = BASE_TIMESTAMP + (long) (random.nextDouble() * TIME_RANGE);
                Document doc = new Document();
                doc.add(new LongPoint(TIMESTAMP_FIELD, timestamp));
                doc.add(new SortedNumericDocValuesField(TIMESTAMP_FIELD, timestamp));
                writer.addDocument(doc);

                if ((i + 1) % docsPerSegment == 0 && i < numDocs - 1) {
                    writer.commit();
                }
            }
            writer.commit();
        }

        reader = DirectoryReader.open(directory);
        searcher = new IndexSearcher(reader);
    }

    private void buildQueries() throws IOException {
        // Build bucket boundaries similar to what ArrayRounding.fixedRoundingPoints() returns
        bucketBoundaries = new long[numBuckets + 1];
        long bucketWidth = TIME_RANGE / numBuckets;
        for (int i = 0; i <= numBuckets; i++) {
            bucketBoundaries[i] = BASE_TIMESTAMP + i * bucketWidth;
        }

        // Build range queries — one per bucket, same as DateHistogramAggregator.FromDateRange
        rangeQueries = new Query[numBuckets];
        for (int i = 0; i < numBuckets; i++) {
            rangeQueries[i] = LongPoint.newRangeQuery(TIMESTAMP_FIELD, bucketBoundaries[i], bucketBoundaries[i + 1] - 1);
        }

        // Pre-create weights (as FilterByFilterAggregator does lazily on first access)
        rangeWeights = new Weight[numBuckets];
        for (int i = 0; i < numBuckets; i++) {
            rangeWeights[i] = searcher.createWeight(rangeQueries[i], ScoreMode.COMPLETE_NO_SCORES, 1.0f);
        }
        matchAllWeight = searcher.createWeight(new MatchAllDocsQuery(), ScoreMode.COMPLETE_NO_SCORES, 1.0f);
    }

    /**
     * Measures the constant-time weight().count() path used by FilterByFilterAggregator
     * when the date histogram has fixedRoundingPoints. This is the primary hot path
     * for date-histo-entire-range: for each bucket, call weight().count() on each segment.
     */
    @Benchmark
    public void pointRangeCountPerBucket(Blackhole bh) throws IOException {
        List<LeafReaderContext> leaves = reader.leaves();
        for (int bucket = 0; bucket < numBuckets; bucket++) {
            Weight weight = rangeWeights[bucket];
            for (LeafReaderContext leaf : leaves) {
                bh.consume(weight.count(leaf));
            }
        }
    }

    /**
     * Measures weight creation + count (simulates FilterByFilterAggregator's lazy weight init).
     */
    @Benchmark
    public void pointRangeWeightCreateAndCount(Blackhole bh) throws IOException {
        List<LeafReaderContext> leaves = reader.leaves();
        for (int i = 0; i < numBuckets; i++) {
            Weight weight = searcher.createWeight(rangeQueries[i], ScoreMode.COMPLETE_NO_SCORES, 1.0f);
            for (LeafReaderContext leaf : leaves) {
                bh.consume(weight.count(leaf));
            }
        }
    }

    /**
     * Measures MatchAllDocsQuery counting — provides a baseline for segment iteration overhead.
     */
    @Benchmark
    public void matchAllCount(Blackhole bh) throws IOException {
        for (LeafReaderContext leaf : reader.leaves()) {
            bh.consume(matchAllWeight.count(leaf));
        }
    }

    /**
     * Measures searcher.count() for range queries — the full Lucene count path
     * including rewriting and weight creation.
     */
    @Benchmark
    public void searcherCountPerBucket(Blackhole bh) throws IOException {
        for (int i = 0; i < numBuckets; i++) {
            bh.consume(searcher.count(rangeQueries[i]));
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
