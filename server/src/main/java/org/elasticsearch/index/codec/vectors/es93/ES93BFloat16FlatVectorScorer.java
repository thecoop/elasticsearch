/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.codec.vectors.es93;

import org.apache.lucene.codecs.hnsw.DefaultFlatVectorScorer;
import org.apache.lucene.codecs.hnsw.FlatVectorsScorer;
import org.apache.lucene.codecs.lucene95.HasIndexSlice;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.KnnVectorValues;
import org.apache.lucene.index.VectorEncoding;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.util.hnsw.RandomVectorScorer;
import org.apache.lucene.util.hnsw.RandomVectorScorerSupplier;
import org.elasticsearch.simdvec.VectorScorerFactory;
import org.elasticsearch.simdvec.VectorSimilarityType;

import java.io.IOException;
import java.util.Optional;

public class ES93BFloat16FlatVectorScorer implements FlatVectorsScorer {

    // can't use MemorySegment/Panama implementation here, as that expects FloatVectorValues
    // to store things as 4-byte floats
    private static final FlatVectorsScorer FALLBACK = DefaultFlatVectorScorer.INSTANCE;
    private static final VectorScorerFactory FACTORY = VectorScorerFactory.instance().orElse(null);

    public static final ES93BFloat16FlatVectorScorer INSTANCE = new ES93BFloat16FlatVectorScorer();

    @Override
    public RandomVectorScorerSupplier getRandomVectorScorerSupplier(
        VectorSimilarityFunction similarityFunction,
        KnnVectorValues vectorValues
    ) throws IOException {
        if (vectorValues.getEncoding() == VectorEncoding.FLOAT32 && FACTORY != null && vectorValues instanceof HasIndexSlice sl) {
            Optional<RandomVectorScorerSupplier> scorer = FACTORY.getBFloat16VectorScorerSupplier(
                VectorSimilarityType.of(similarityFunction),
                sl.getSlice(),
                (FloatVectorValues) vectorValues
            );
            if (scorer.isPresent()) {
                return scorer.get();
            }
        }
        return FALLBACK.getRandomVectorScorerSupplier(similarityFunction, vectorValues);
    }

    @Override
    public RandomVectorScorer getRandomVectorScorer(
        VectorSimilarityFunction similarityFunction,
        KnnVectorValues vectorValues,
        float[] target
    ) throws IOException {
        if (FACTORY != null) {
            var scorer = FACTORY.getBFloat16VectorScorer(similarityFunction, (FloatVectorValues) vectorValues, target);
            if (scorer.isPresent()) {
                return scorer.get();
            }
        }
        return FALLBACK.getRandomVectorScorer(similarityFunction, vectorValues, target);
    }

    @Override
    public RandomVectorScorer getRandomVectorScorer(
        VectorSimilarityFunction similarityFunction,
        KnnVectorValues vectorValues,
        byte[] target
    ) throws IOException {
        return FALLBACK.getRandomVectorScorer(similarityFunction, vectorValues, target);
    }

    @Override
    public String toString() {
        return "ES93BFloat16FlatVectorScorer(delegate=" + FALLBACK + ")";
    }
}
