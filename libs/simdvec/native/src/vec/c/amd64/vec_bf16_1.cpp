/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

// BF16 vector implementations for AVX2 processors

#include <stddef.h>
#include <stdint.h>
#include <math.h>
#include <algorithm>
#include "vec.h"
#include "vec_common.h"
#include "amd64/amd64_vec_common.h"

static inline __m256 bf16_to_f32(__m128i bf16) {
    __m256i widened = _mm256_cvtepu16_epi32(bf16);
    __m256i shifted = _mm256_slli_epi32(widened, 16);
    return _mm256_castsi256_ps(shifted);
}

static inline __m256 load_bf16(const bf16_t* ptr, int elements) {
    return bf16_to_f32(_mm_lddqu_si128((const __m128i*)(ptr + elements)));
}

static inline __m256 load_f32(const f32_t* ptr, int elements) {
    return _mm256_loadu_ps(ptr + elements);
}

static inline __m128i f32_to_bf16(__m256 x) {
    __m256i xi = _mm256_castps_si256(x);

    // see BFloat16.floatToBFloat16
    __m256i lsb = _mm256_and_si256(_mm256_srli_epi32(xi, 16), _mm256_set1_epi32(1));
    __m256i bias = _mm256_add_epi32(_mm256_set1_epi32(0x7FFF), lsb);
    __m256i rounded = _mm256_add_epi32(xi, bias);

    __m256i bf16_32 = _mm256_srli_epi32(rounded, 16);

    // pack the 32-bit values together as 16-bit values
    return _mm256_castsi256_si128(_mm256_packus_epi32(bf16_32, _mm256_setzero_si256()));
}

template<
    typename Q,
    __m256(*load_q)(const Q*, int bf16_offset),
    __m256(*vector_op)(__m256, __m256, __m256),
    f32_t(*scalar_op)(bf16_t, Q)
>
static inline f32_t bf16_inner(const bf16_t* d, const Q* q, const int32_t elementCount) {
    constexpr int batches = 4;

    __m256 sums[batches];
    apply_indexed<batches>([&](auto I) {
        sums[I] = _mm256_setzero_ps();
    });

    int i = 0;
    // each value has <elements> floats, and we iterate over <stride> floats at a time
    constexpr int elements = sizeof(__m128) / sizeof(bf16_t);
    constexpr int stride = sizeof(__m128) / sizeof(bf16_t) * batches;
    for (; i < (elementCount & ~(stride - 1)); i += stride) {
        apply_indexed<batches>([&](auto I) {
            sums[I] = vector_op(load_bf16(d, i + I * elements), load_q(q, i + I * elements), sums[I]);
        });
    }

    // Combine all partial sums
    __m256 total_sum = tree_reduce<batches, __m256, _mm256_add_ps>(sums);
    f32_t result = mm256_reduce_ps<_mm_add_ps>(total_sum);

    for (; i < elementCount; ++i) {
        result += scalar_op(d[i], q[i]);
    }

    return result;
}

// const bf16_t* a  pointer to the first float vector
// const f32_t* b  pointer to the second float vector
// const int32_t elementCount  the number of floating point elements
EXPORT f32_t vec_dotDbf16Qf32(const bf16_t* a, const f32_t* b, const int32_t elementCount) {
    return bf16_inner<f32_t, load_f32, _mm256_fmadd_ps, dot_scalar>(a, b, elementCount);
}

// const bf16_t* a  pointer to the first float vector
// const bf16_t* b  pointer to the second float vector
// const int32_t elementCount  the number of floating point elements
EXPORT f32_t vec_dotDbf16Qbf16(const bf16_t* a, const bf16_t* b, const int32_t elementCount) {
    return bf16_inner<bf16_t, load_bf16, _mm256_fmadd_ps, dot_scalar>(a, b, elementCount);
}

static inline __m256 sqrf32_vector(__m256 a, __m256 b, __m256 sum) {
    __m256 diff = _mm256_sub_ps(a, b);
    return _mm256_fmadd_ps(diff, diff, sum);
}

// const bf16_t* a  pointer to the first float vector
// const f32_t* b  pointer to the second float vector
// const int32_t elementCount  the number of floating point elements
EXPORT f32_t vec_sqrDbf16Qf32(const bf16_t* a, const f32_t* b, const int32_t elementCount) {
    return bf16_inner<f32_t, load_f32, sqrf32_vector, sqr_scalar>(a, b, elementCount);
}

// const bf16_t* a  pointer to the first float vector
// const bf16_t* b  pointer to the second float vector
// const int32_t elementCount  the number of floating point elements
EXPORT f32_t vec_sqrDbf16Qbf16(const bf16_t* a, const bf16_t* b, const int32_t elementCount) {
    return bf16_inner<bf16_t, load_bf16, sqrf32_vector, sqr_scalar>(a, b, elementCount);
}
