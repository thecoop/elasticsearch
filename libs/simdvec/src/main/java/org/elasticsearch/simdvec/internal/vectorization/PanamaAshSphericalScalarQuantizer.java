/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.simdvec.internal.vectorization;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import org.elasticsearch.simdvec.AshSphericalScalarQuantizer;

import java.util.Arrays;

import static jdk.incubator.vector.VectorOperators.ADD;
import static jdk.incubator.vector.VectorOperators.BITWISE_BLEND;
import static jdk.incubator.vector.VectorOperators.GE;

public final class PanamaAshSphericalScalarQuantizer extends AshSphericalScalarQuantizer {

    private static final VectorSpecies<Float> FLOAT_SPECIES = PanamaVectorConstants.PREFERRED_FLOAT_SPECIES;
    private static final VectorSpecies<Integer> INTEGER_SPECIES = PanamaVectorConstants.PREFERRED_INTEGER_SPECIES;

    public PanamaAshSphericalScalarQuantizer(int bitsPerDim) {
        super(bitsPerDim);
    }

    @Override
    protected float quantizeExact1Bit(float[] z, int zOffset, float[] out, int outOffset, int d) {
        IntVector halfConst = FloatVector.broadcast(FLOAT_SPECIES, 0.5f).reinterpretAsInts();
        IntVector signBit = IntVector.broadcast(INTEGER_SPECIES, 0x80000000);

        int i=0;
        int limit = FLOAT_SPECIES.loopBound(d);
        for (; i < limit; i += FLOAT_SPECIES.length()) {
            IntVector vec = FloatVector.fromArray(FLOAT_SPECIES, z, zOffset + i).reinterpretAsInts();
            // select the sign bit from vec, but the rest of the value from halfConst
            IntVector result = halfConst.bitwiseBlend(vec, signBit);
            result.reinterpretAsFloats().intoArray(out, outOffset + i);
        }
        if (i < d) {
            var mask = FLOAT_SPECIES.indexInRange(i, d);
            IntVector vec = FloatVector.fromArray(FLOAT_SPECIES, z, zOffset + i, mask).reinterpretAsInts();
            IntVector result = halfConst.bitwiseBlend(vec, signBit); // don't need to mask this
            result.reinterpretAsFloats().intoArray(out, outOffset + i, mask);
        }

        return (float) Math.sqrt(0.25 * d);
    }

    @Override
    protected float quantizeExact2Bit(float[] z, int zOffset, float[] out, int outOffset, int d) {
        final int limit = FLOAT_SPECIES.loopBound(d);
        FloatVector halfConst = FloatVector.broadcast(FLOAT_SPECIES, 0.5f);

        float[] absZ = new float[d];
        FloatVector dotAcc = FloatVector.zero(FLOAT_SPECIES);
        int i=0;
        for (; i < limit; i += FLOAT_SPECIES.length()) {
            FloatVector abs = FloatVector.fromArray(FLOAT_SPECIES, z, zOffset + i).abs();
            abs.intoArray(absZ, i);
            dotAcc = halfConst.fma(abs, dotAcc);
        }
        if (i < d) {
            var mask = FLOAT_SPECIES.indexInRange(i, d);
            FloatVector abs = FloatVector.fromArray(FLOAT_SPECIES, z, zOffset + i, mask).abs();
            abs.intoArray(absZ, i, mask);
            dotAcc = halfConst.fma(abs, dotAcc);
        }
        double dot = dotAcc.reduceLanes(ADD);

        // Sorted ascending; the iteration is then done backwards
        Arrays.sort(absZ);

        double normSq = 0.25 * d;
        double bestDot = dot;
        double bestNormSq = normSq;

        // iterate dims in |z| descending order
        int bestK = 0; // number of dimensions to upgrade to level 1.5
        for (int k = 0; k < d; k++) {
            i = d - 1 - k;
            dot += absZ[i];  // upgrading from 0.5 to 1.5 adds 1.0 * |z_dim|
            normSq += 2.0;   // 1.5^2 - 0.5^2 = 2.0

            // Handle ties: skip evaluation if next dim has the same |z|
            if (i > 0 && absZ[i] == absZ[i - 1]) {
                continue;
            }

            // dot / sqrt(normSq) > bestDot / sqrt(bestNormSq), cross-multiplied to avoid a divide
            // and a square root per dimension
            if (dot * dot * bestNormSq > bestDot * bestDot * normSq) {
                bestDot = dot;
                bestNormSq = normSq;
                bestK = k + 1;
            }
        }

        if (bestK == 0) {
            // bah humbug, didn't find anything, so everything is 0.5
            return quantizeExact1Bit(z, zOffset, out, outOffset, d);
        }

        float threshold = absZ[d - bestK];

        IntVector oneHalfConst = FloatVector.broadcast(FLOAT_SPECIES, 1.5f).reinterpretAsInts();
        IntVector signBit = IntVector.broadcast(INTEGER_SPECIES, 0x80000000);
        i=0;
        for (; i < limit; i += FLOAT_SPECIES.length()) {
            FloatVector vec = FloatVector.fromArray(FLOAT_SPECIES, z, zOffset + i);
            // Math.copySign(Math.abs(v) >= threshold ? 1.5f : 0.5f, v);
            VectorMask<Integer> nextLevel = vec.abs().compare(GE, threshold).cast(INTEGER_SPECIES);
            IntVector result = halfConst.reinterpretAsInts().blend(oneHalfConst, nextLevel).bitwiseBlend(vec.reinterpretAsInts(), signBit);
            result.reinterpretAsFloats().intoArray(out, outOffset + i);
        }
        if (i < d) {
            var mask = FLOAT_SPECIES.indexInRange(i, d);
            FloatVector vec = FloatVector.fromArray(FLOAT_SPECIES, z, zOffset + i, mask);
            // Math.copySign(Math.abs(v) >= threshold ? 1.5f : 0.5f, v);
            VectorMask<Integer> nextLevel = vec.abs().compare(GE, threshold).cast(INTEGER_SPECIES);
            IntVector result = halfConst.reinterpretAsInts().blend(oneHalfConst, nextLevel).bitwiseBlend(vec.reinterpretAsInts(), signBit);
            result.reinterpretAsFloats().intoArray(out, outOffset + i, mask);
        }

        // vector is now (d - bestK) x 0.5, and bestK x 1.5,
        // which is what the iteration accumulated into bestNormSq
        // it started out as d x 0.5^2, and was adjusted for every dim switched to 1.5
        return (float) Math.sqrt(bestNormSq);
    }
}
