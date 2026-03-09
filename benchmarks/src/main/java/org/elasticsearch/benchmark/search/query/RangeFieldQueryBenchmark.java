/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.search.query;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.LongRange;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
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
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks Lucene range field queries using LongRange — the field type
 * used in Elasticsearch's range fields (integer_range, long_range, date_range).
 *
 * Range fields use multi-dimensional BKD trees (2 dimensions per field: min and max).
 * The NOAA benchmarks showing regression include:
 * - range_field_small_range: -2% to -7%
 * - range_field_conjunction_big_range_small_term_query: -0% to -8%
 *
 * Run with JDK 25 and JDK 26 to compare:
 * {@code ./gradlew :benchmarks:run --args 'RangeFieldQueryBenchmark'}
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(2)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
public class RangeFieldQueryBenchmark {

    @Param({ "100000", "1000000" })
    private int numDocs;

    /**
     * Controls how wide the query range is relative to the data range.
     * "small" = 1% of data range, "large" = 50% of data range.
     */
    @Param({ "small", "large" })
    private String queryRange;

    private Directory directory;
    private DirectoryReader reader;
    private IndexSearcher searcher;

    private static final String RANGE_FIELD = "temperature_range";
    private static final String POINT_FIELD = "timestamp";
    private static final long DATA_MIN = 0L;
    private static final long DATA_MAX = 1000000L;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        directory = FSDirectory.open(Files.createTempDirectory("range-field-bench-"));
        buildIndex();
        reader = DirectoryReader.open(directory);
        searcher = new IndexSearcher(reader);
    }

    private void buildIndex() throws IOException {
        IndexWriterConfig config = new IndexWriterConfig();
        Random random = new Random(42);

        try (IndexWriter writer = new IndexWriter(directory, config)) {
            for (int i = 0; i < numDocs; i++) {
                long rangeMin = DATA_MIN + (long) (random.nextDouble() * (DATA_MAX - DATA_MIN));
                long rangeWidth = 1 + (long) (random.nextDouble() * (DATA_MAX - rangeMin) * 0.1);
                long rangeMax = Math.min(rangeMin + rangeWidth, DATA_MAX);

                long timestamp = DATA_MIN + (long) (random.nextDouble() * (DATA_MAX - DATA_MIN));

                Document doc = new Document();
                doc.add(new LongRange(RANGE_FIELD, new long[] { rangeMin }, new long[] { rangeMax }));
                doc.add(new LongPoint(POINT_FIELD, timestamp));
                doc.add(new SortedNumericDocValuesField(POINT_FIELD, timestamp));
                doc.add(new NumericDocValuesField("value", random.nextLong()));
                writer.addDocument(doc);
            }
            writer.commit();
        }
    }

    private long[] getQueryBounds() {
        long dataRange = DATA_MAX - DATA_MIN;
        if ("small".equals(queryRange)) {
            long width = dataRange / 100;
            long mid = DATA_MIN + dataRange / 2;
            return new long[] { mid - width / 2, mid + width / 2 };
        } else {
            long width = dataRange / 2;
            long mid = DATA_MIN + dataRange / 2;
            return new long[] { mid - width / 2, mid + width / 2 };
        }
    }

    /**
     * LongRange.newIntersectsQuery — the default query type for range field queries.
     * This traverses a 2D BKD tree.
     */
    @Benchmark
    public void rangeFieldIntersectsQuery(Blackhole bh) throws IOException {
        long[] bounds = getQueryBounds();
        Query query = LongRange.newIntersectsQuery(RANGE_FIELD, new long[] { bounds[0] }, new long[] { bounds[1] });
        bh.consume(searcher.count(query));
    }

    /**
     * LongRange.newContainsQuery — finds range fields that fully contain the query range.
     */
    @Benchmark
    public void rangeFieldContainsQuery(Blackhole bh) throws IOException {
        long[] bounds = getQueryBounds();
        Query query = LongRange.newContainsQuery(RANGE_FIELD, new long[] { bounds[0] }, new long[] { bounds[1] });
        bh.consume(searcher.count(query));
    }

    /**
     * LongRange.newWithinQuery — finds range fields fully within the query range.
     */
    @Benchmark
    public void rangeFieldWithinQuery(Blackhole bh) throws IOException {
        long[] bounds = getQueryBounds();
        Query query = LongRange.newWithinQuery(RANGE_FIELD, new long[] { bounds[0] }, new long[] { bounds[1] });
        bh.consume(searcher.count(query));
    }

    /**
     * Standard LongPoint range query on the same index for comparison —
     * uses a standard 1D BKD tree.
     */
    @Benchmark
    public void pointRangeQuery(Blackhole bh) throws IOException {
        long[] bounds = getQueryBounds();
        Query query = LongPoint.newRangeQuery(POINT_FIELD, bounds[0], bounds[1]);
        bh.consume(searcher.count(query));
    }

    /**
     * Conjunction of range field query + point range query.
     * Matches the pattern of range_field_conjunction_big_range_small_term_query.
     */
    @Benchmark
    public void conjunctionRangeAndPointQuery(Blackhole bh) throws IOException {
        long dataRange = DATA_MAX - DATA_MIN;
        // Big range query (50% of data)
        long rangeWidth = dataRange / 2;
        long rangeMid = DATA_MIN + dataRange / 2;
        Query rangeQuery = LongRange.newIntersectsQuery(
            RANGE_FIELD,
            new long[] { rangeMid - rangeWidth / 2 },
            new long[] { rangeMid + rangeWidth / 2 }
        );
        // Small point query (1% of data)
        long pointWidth = dataRange / 100;
        Query pointQuery = LongPoint.newRangeQuery(POINT_FIELD, rangeMid - pointWidth / 2, rangeMid + pointWidth / 2);

        org.apache.lucene.search.BooleanQuery.Builder builder = new org.apache.lucene.search.BooleanQuery.Builder();
        builder.add(rangeQuery, org.apache.lucene.search.BooleanClause.Occur.MUST);
        builder.add(pointQuery, org.apache.lucene.search.BooleanClause.Occur.MUST);
        bh.consume(searcher.count(builder.build()));
    }

    /**
     * Per-leaf weight().count() — testing the BKD tree traversal for range fields
     * without searcher overhead.
     */
    @Benchmark
    public void rangeFieldPerLeafCount(Blackhole bh) throws IOException {
        long[] bounds = getQueryBounds();
        Query query = LongRange.newIntersectsQuery(RANGE_FIELD, new long[] { bounds[0] }, new long[] { bounds[1] });
        Weight weight = searcher.createWeight(query, ScoreMode.COMPLETE_NO_SCORES, 1.0f);
        for (LeafReaderContext leaf : reader.leaves()) {
            bh.consume(weight.count(leaf));
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        reader.close();
        directory.close();
    }
}
