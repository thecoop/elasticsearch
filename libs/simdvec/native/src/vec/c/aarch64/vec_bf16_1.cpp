/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

// BF16 vector implementations for basic ARM NEON processors

#include <stddef.h>
#include <arm_neon.h>
#include <math.h>
#include <algorithm>
#include "vec.h"
#include "vec_common.h"
#include "aarch64/aarch64_vec_common.h"

static inline float32x4_t bf16_to_f32(uint16x4_t bf16) {
    return vreinterpretq_f32_u32(vshll_n_u16(bf16, 16));
}

static inline float32x4_t load_bf16(const bf16_t* ptr, int elements) {
    return bf16_to_f32(vld1_u16((const uint16_t*)(ptr + elements)));
}

static inline float32x4_t load_f32(const f32_t* ptr, int elements) {
    return vld1q_f32(ptr + elements);
}

template<
    typename Q,
    float32x4_t(*load_q)(const Q*, int element),
    float32x4_t(*vector_op)(float32x4_t, float32x4_t, float32x4_t),
    f32_t(*scalar_op)(bf16_t, Q)
>
static inline f32_t bf16_inner(const bf16_t* d, const Q* q, const int32_t elementCount) {
    constexpr int batches = 8;

    float32x4_t sums[batches];
    apply_indexed<batches>([&](auto I) {
        sums[I] = vdupq_n_f32(0.0f);
    });

    int i = 0;
    // each value has <elements> floats, and we iterate over <stride> floats at a time
    constexpr int elements = sizeof(uint16x4_t) / sizeof(bf16_t);
    constexpr int stride = sizeof(uint16x4_t) / sizeof(bf16_t) * batches;
    for (; i < (elementCount & ~(stride - 1)); i += stride) {
        apply_indexed<batches>([&](auto I) {
            sums[I] = vector_op(sums[I], load_bf16(d, i + I * elements), load_q(q, i + I * elements));
        });
    }

    float32x4_t total = tree_reduce<batches, float32x4_t, vaddq_f32>(sums);
    f32_t result = vaddvq_f32(total);

    // Handle remaining elements
    for (; i < elementCount; ++i) {
        result += scalar_op(d[i], q[i]);
    }

    return result;
}

// const bf16_t* a  pointer to the first float vector
// const f32_t* b  pointer to the second float vector
// const int32_t elementCount  the number of floating point elements
EXPORT f32_t vec_dotDbf16Qf32(const bf16_t* a, const f32_t* b, const int32_t elementCount) {
    return bf16_inner<f32_t, load_f32, vfmaq_f32, dot_scalar>(a, b, elementCount);
}

// const bf16_t* a  pointer to the first float vector
// const bf16_t* b  pointer to the second float vector
// const int32_t elementCount  the number of floating point elements
EXPORT f32_t vec_dotDbf16Qbf16(const bf16_t* a, const bf16_t* b, const int32_t elementCount) {
    return bf16_inner<bf16_t, load_bf16, vfmaq_f32, dot_scalar>(a, b, elementCount);
}

static inline float32x4_t sqrf32_vector(float32x4_t sum, float32x4_t a, float32x4_t b) {
    float32x4_t diff = vsubq_f32(a, b);
    return vmlaq_f32(sum, diff, diff);
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
