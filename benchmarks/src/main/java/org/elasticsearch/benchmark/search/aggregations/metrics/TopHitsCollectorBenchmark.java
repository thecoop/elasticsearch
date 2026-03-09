/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.search.aggregations.metrics;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortedNumericSortField;
import org.apache.lucene.search.TopFieldCollector;
import org.apache.lucene.search.TopFieldCollectorManager;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
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
 * Benchmarks the Lucene-level operations that underpin TopHitsAggregator:
 * TopFieldCollector with sorted field collection and per-bucket collection patterns.
 *
 * The NOAA benchmarks showing regression include:
 * - last_max_temp_per_station_top_hits_10_depth_first: -1.5% to -7%
 * - last_max_temp_per_station_top_hits_5000: -1% to -6%
 *
 * TopHitsAggregator creates a TopFieldCollector per bucket and uses it to
 * collect documents. The hot path involves:
 * 1. Per-document collection into a priority queue (heap)
 * 2. FieldComparator comparisons using doc values
 * 3. Heap sift-up/sift-down operations
 *
 * Run with JDK 25 and JDK 26 to compare:
 * {@code ./gradlew :benchmarks:run --args 'TopHitsCollectorBenchmark'}
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(2)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
public class TopHitsCollectorBenchmark {

    @Param({ "100000", "1000000" })
    private int numDocs;

    /**
     * Number of top hits to collect — matches the NOAA benchmark variants.
     * 10 = depth_first top_hits_10, 5000 = top_hits_5000
     */
    @Param({ "10", "100", "5000" })
    private int topN;

    /**
     * Number of distinct "stations" (buckets).
     * In NOAA data, this simulates per-station grouping.
     */
    @Param({ "100", "1000" })
    private int numStations;

    private Directory directory;
    private DirectoryReader reader;
    private IndexSearcher searcher;

    private static final String STATION_FIELD = "station";
    private static final String TEMPERATURE_FIELD = "max_temp";
    private static final String TIMESTAMP_FIELD = "@timestamp";

    @Setup(Level.Trial)
    public void setup() throws IOException {
        directory = FSDirectory.open(Files.createTempDirectory("top-hits-bench-"));
        buildIndex();
        reader = DirectoryReader.open(directory);
        searcher = new IndexSearcher(reader);
    }

    private void buildIndex() throws IOException {
        IndexWriterConfig config = new IndexWriterConfig();
        Random random = new Random(42);

        try (IndexWriter writer = new IndexWriter(directory, config)) {
            for (int i = 0; i < numDocs; i++) {
                int stationId = random.nextInt(numStations);
                long temperature = -400 + random.nextInt(1200); // -40.0 to 80.0 scaled by 10
                long timestamp = 631152000000L + (long) (random.nextDouble() * 3786912000000L);

                Document doc = new Document();
                doc.add(new StringField(STATION_FIELD, "station-" + stationId, Field.Store.NO));
                doc.add(new SortedDocValuesField(STATION_FIELD, new BytesRef("station-" + stationId)));
                doc.add(new LongPoint(TIMESTAMP_FIELD, timestamp));
                doc.add(new SortedNumericDocValuesField(TIMESTAMP_FIELD, timestamp));
                doc.add(new NumericDocValuesField(TEMPERATURE_FIELD, temperature));
                writer.addDocument(doc);
            }
            writer.commit();
        }
    }

    /**
     * Measures TopFieldCollector with sorting by temperature (descending).
     * This is the core of top_hits aggregation — collect top N hits sorted by a field.
     * The hot path is the priority queue insert/compare in per-document collection.
     */
    @Benchmark
    public void topFieldCollectorSortByValue(Blackhole bh) throws IOException {
        Sort sort = new Sort(new SortField(TEMPERATURE_FIELD, SortField.Type.LONG, true));
        TopFieldCollectorManager manager = new TopFieldCollectorManager(sort, topN, null, Integer.MAX_VALUE);
        TopFieldDocs topDocs = searcher.search(new MatchAllDocsQuery(), manager);
        bh.consume(topDocs);
    }

    /**
     * Measures TopFieldCollector sorted by timestamp (descending).
     * "Last N per station" patterns sort by time.
     */
    @Benchmark
    public void topFieldCollectorSortByTimestamp(Blackhole bh) throws IOException {
        Sort sort = new Sort(new SortedNumericSortField(TIMESTAMP_FIELD, SortField.Type.LONG, true));
        TopFieldCollectorManager manager = new TopFieldCollectorManager(sort, topN, null, Integer.MAX_VALUE);
        TopFieldDocs topDocs = searcher.search(new MatchAllDocsQuery(), manager);
        bh.consume(topDocs);
    }

    /**
     * Per-bucket top hits simulation: creates separate TopFieldCollectors
     * and distributes documents across them (mimicking depth-first collection
     * where each bucket's collector sees its own documents).
     *
     * This exercises the per-document overhead of:
     * 1. Looking up which collector to use (bucket routing)
     * 2. Calling leafCollector.collect(docId)
     * 3. Priority queue maintenance inside each collector
     */
    @Benchmark
    public void perBucketTopFieldCollection(Blackhole bh) throws IOException {
        Sort sort = new Sort(new SortField(TEMPERATURE_FIELD, SortField.Type.LONG, true));
        int effectiveStations = Math.min(numStations, 100); // cap for memory

        // Create per-bucket collectors (like TopHitsAggregator does)
        TopFieldCollector[] collectors = new TopFieldCollector[effectiveStations];
        for (int i = 0; i < effectiveStations; i++) {
            collectors[i] = new TopFieldCollectorManager(sort, topN, null, Integer.MAX_VALUE).newCollector();
        }

        // Simulate per-doc collection across buckets
        for (LeafReaderContext leaf : reader.leaves()) {
            LeafCollector[] leafCollectors = new LeafCollector[effectiveStations];
            for (int i = 0; i < effectiveStations; i++) {
                leafCollectors[i] = collectors[i].getLeafCollector(leaf);
                leafCollectors[i].setScorer(new FakeScorer());
            }

            int maxDoc = leaf.reader().maxDoc();
            for (int doc = 0; doc < maxDoc; doc++) {
                int bucket = doc % effectiveStations;
                leafCollectors[bucket].collect(doc);
            }
        }

        for (int i = 0; i < effectiveStations; i++) {
            bh.consume(collectors[i].topDocs());
        }
    }

    /**
     * Minimal scorer that returns 0 — TopFieldCollector with field sort doesn't use the score.
     */
    private static class FakeScorer extends Scorable {
        @Override
        public float score() {
            return 0f;
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        reader.close();
        directory.close();
    }
}
