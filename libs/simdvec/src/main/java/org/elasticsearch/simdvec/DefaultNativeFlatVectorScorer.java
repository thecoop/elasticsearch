/*
 * @notice
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modifications copyright (C) 2025 Elasticsearch B.V.
 */
package org.elasticsearch.simdvec;

import org.apache.lucene.codecs.hnsw.FlatVectorsScorer;
import org.apache.lucene.index.ByteVectorValues;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.KnnVectorValues;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.util.VectorUtil;
import org.apache.lucene.util.hnsw.RandomVectorScorer;
import org.apache.lucene.util.hnsw.RandomVectorScorerSupplier;
import org.apache.lucene.util.hnsw.UpdateableRandomVectorScorer;

import java.io.IOException;

import static org.apache.lucene.util.VectorUtil.normalizeDistanceToUnitInterval;
import static org.apache.lucene.util.VectorUtil.normalizeToUnitInterval;
import static org.apache.lucene.util.VectorUtil.scaleMaxInnerProductScore;

public class DefaultNativeFlatVectorScorer implements FlatVectorsScorer {

    public static final DefaultNativeFlatVectorScorer INSTANCE = new DefaultNativeFlatVectorScorer();

    @Override
    public RandomVectorScorerSupplier getRandomVectorScorerSupplier(
        VectorSimilarityFunction similarityFunction,
        KnnVectorValues vectorValues
    ) throws IOException {
        switch (vectorValues.getEncoding()) {
            case FLOAT32 -> {
                return new FloatScoringSupplier((FloatVectorValues) vectorValues, similarityFunction);
            }
            case BYTE -> {
                return new ByteScoringSupplier((ByteVectorValues) vectorValues, similarityFunction);
            }
        }
        throw new IllegalArgumentException(
            "vectorValues must be an instance of FloatVectorValues or ByteVectorValues, got a " + vectorValues.getClass().getName()
        );
    }

    @Override
    public RandomVectorScorer getRandomVectorScorer(
        VectorSimilarityFunction similarityFunction,
        KnnVectorValues vectorValues,
        float[] target
    ) throws IOException {
        assert vectorValues instanceof FloatVectorValues;
        if (target.length != vectorValues.dimension()) {
            throw new IllegalArgumentException(
                "vector query dimension: " + target.length + " differs from field dimension: " + vectorValues.dimension()
            );
        }
        return new FloatVectorScorer((FloatVectorValues) vectorValues, target, similarityFunction);
    }

    @Override
    public RandomVectorScorer getRandomVectorScorer(
        VectorSimilarityFunction similarityFunction,
        KnnVectorValues vectorValues,
        byte[] target
    ) throws IOException {
        assert vectorValues instanceof ByteVectorValues;
        if (target.length != vectorValues.dimension()) {
            throw new IllegalArgumentException(
                "vector query dimension: " + target.length + " differs from field dimension: " + vectorValues.dimension()
            );
        }
        return new ByteVectorScorer((ByteVectorValues) vectorValues, target, similarityFunction);
    }

    @Override
    public String toString() {
        return "DefaultNativeFlatVectorScorer()";
    }

    public static float compare(VectorSimilarityFunction function, float[] a, float[] b) {
        return switch (function) {
            case EUCLIDEAN -> normalizeDistanceToUnitInterval(ESVectorUtil.squareDistance(a, b));
            case DOT_PRODUCT -> normalizeToUnitInterval(ESVectorUtil.dotProduct(a, b));
            case COSINE -> normalizeToUnitInterval(VectorUtil.cosine(a, b)); // no ES version, we expect all float vectors to be normalized
            case MAXIMUM_INNER_PRODUCT -> scaleMaxInnerProductScore(ESVectorUtil.dotProduct(a, b));
        };
    }

    public static float compare(VectorSimilarityFunction function, byte[] a, byte[] b) {
        return switch (function) {
            case EUCLIDEAN -> 1 / (1f + ESVectorUtil.squareDistance(a, b));
            case DOT_PRODUCT -> {
                float denom = (float) (a.length * (1 << 15));
                yield 0.5f + ESVectorUtil.dotProduct(a, b) / denom;
            }
            case COSINE -> (1 + ESVectorUtil.cosine(a, b)) / 2;
            case MAXIMUM_INNER_PRODUCT -> scaleMaxInnerProductScore(ESVectorUtil.dotProduct(a, b));
        };
    }

    private static final class ByteScoringSupplier implements RandomVectorScorerSupplier {
        private final ByteVectorValues vectors;
        private final ByteVectorValues targetVectors;
        private final VectorSimilarityFunction similarityFunction;

        private ByteScoringSupplier(ByteVectorValues vectors, VectorSimilarityFunction similarityFunction) throws IOException {
            this.vectors = vectors;
            targetVectors = vectors.copy();
            this.similarityFunction = similarityFunction;
        }

        @Override
        public UpdateableRandomVectorScorer scorer() throws IOException {
            byte[] vector = new byte[vectors.dimension()];
            return new UpdateableRandomVectorScorer.AbstractUpdateableRandomVectorScorer(vectors) {

                @Override
                public void setScoringOrdinal(int node) throws IOException {
                    System.arraycopy(targetVectors.vectorValue(node), 0, vector, 0, vector.length);
                }

                @Override
                public float score(int node) throws IOException {
                    return compare(similarityFunction, vector, targetVectors.vectorValue(node));
                }
            };
        }

        @Override
        public RandomVectorScorerSupplier copy() throws IOException {
            return new ByteScoringSupplier(vectors, similarityFunction);
        }

        @Override
        public String toString() {
            return "ByteScoringSupplier(similarityFunction=" + similarityFunction + ")";
        }
    }

    private static final class FloatScoringSupplier implements RandomVectorScorerSupplier {
        private final FloatVectorValues vectors;
        private final FloatVectorValues targetVectors;
        private final VectorSimilarityFunction similarityFunction;

        private FloatScoringSupplier(FloatVectorValues vectors, VectorSimilarityFunction similarityFunction) throws IOException {
            this.vectors = vectors;
            targetVectors = vectors.copy();
            this.similarityFunction = similarityFunction;
        }

        @Override
        public UpdateableRandomVectorScorer scorer() throws IOException {
            float[] vector = new float[vectors.dimension()];
            return new UpdateableRandomVectorScorer.AbstractUpdateableRandomVectorScorer(vectors) {
                @Override
                public float score(int node) throws IOException {
                    return compare(similarityFunction, vector, targetVectors.vectorValue(node));
                }

                @Override
                public void setScoringOrdinal(int node) throws IOException {
                    System.arraycopy(targetVectors.vectorValue(node), 0, vector, 0, vector.length);
                }
            };
        }

        @Override
        public RandomVectorScorerSupplier copy() throws IOException {
            return new FloatScoringSupplier(vectors, similarityFunction);
        }

        @Override
        public String toString() {
            return "FloatScoringSupplier(similarityFunction=" + similarityFunction + ")";
        }
    }

    private static class FloatVectorScorer extends RandomVectorScorer.AbstractRandomVectorScorer {
        private final FloatVectorValues values;
        private final float[] query;
        private final VectorSimilarityFunction similarityFunction;

        FloatVectorScorer(FloatVectorValues values, float[] query, VectorSimilarityFunction similarityFunction) {
            super(values);
            this.values = values;
            this.query = query;
            this.similarityFunction = similarityFunction;
        }

        @Override
        public float score(int node) throws IOException {
            return compare(similarityFunction, query, values.vectorValue(node));
        }
    }

    private static class ByteVectorScorer extends RandomVectorScorer.AbstractRandomVectorScorer {
        private final ByteVectorValues values;
        private final byte[] query;
        private final VectorSimilarityFunction similarityFunction;

        ByteVectorScorer(ByteVectorValues values, byte[] query, VectorSimilarityFunction similarityFunction) {
            super(values);
            this.values = values;
            this.query = query;
            this.similarityFunction = similarityFunction;
        }

        @Override
        public float score(int node) throws IOException {
            return compare(similarityFunction, query, values.vectorValue(node));
        }
    }
}
