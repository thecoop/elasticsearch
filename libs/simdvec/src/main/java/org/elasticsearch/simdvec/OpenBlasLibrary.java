/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.simdvec;

import org.elasticsearch.foreign.Critical;
import org.elasticsearch.foreign.Function;
import org.elasticsearch.foreign.LibraryProvider;
import org.elasticsearch.foreign.LibrarySpecification;
import org.elasticsearch.foreign.Platform;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;

import java.lang.foreign.MemorySegment;
import java.util.Optional;

/**
 * PROTOTYPE ONLY. FFM binding for <a href="https://github.com/OpenMathLib/OpenBLAS">OpenBLAS</a>
 * {@code cblas_sgemm}, used to benchmark single-threaded SGEMM against the Panama
 * {@code matrixMultiply} implementation.
 *
 * <p>The library is only loaded on Linux aarch64 and only when {@code libopenblas.so}
 * exists in {@code es.nativelibs.path}. Every other platform or a missing library
 * results in {@link #instance()} returning an empty {@code Optional}; callers must
 * fall back to the Panama path in that case.
 *
 * <p>Build {@code libopenblas.so} for Graviton4 with
 * {@code libs/simdvec/native/build_openblas_graviton4.sh}, which compiles OpenBLAS
 * with {@code TARGET=NEOVERSEV2 USE_THREAD=0} and copies the result into
 * {@code libs/native/libraries/build/platform/linux-aarch64/}.
 */
@LibrarySpecification(
    name = "openblas",
    unavailableOn = { Platform.LINUX_X64, Platform.DARWIN_X64, Platform.DARWIN_AARCH64, Platform.WINDOWS_X64 }
)
public abstract class OpenBlasLibrary {

    private static final Logger logger = LogManager.getLogger(OpenBlasLibrary.class);

    // CBLAS constants for row-major, no-transpose layout.
    private static final int CBLAS_ROW_MAJOR = 101;
    private static final int CBLAS_NO_TRANS = 111;

    private static final OpenBlasLibrary INSTANCE = load();

    /**
     * Returns the OpenBLAS library, or an empty {@code Optional} if the library is
     * not available on this platform or not present in {@code es.nativelibs.path}.
     */
    public static Optional<OpenBlasLibrary> instance() {
        return Optional.ofNullable(INSTANCE);
    }

    private static OpenBlasLibrary load() {
        try {
            OpenBlasLibrary lib = LibraryProvider.lookupLibrary(OpenBlasLibrary.class);
            if (lib == null) {
                // null from lookupLibrary means the current platform is in unavailableOn
                return null;
            }
            logger.info("Loaded OpenBLAS: " + lib.config());
            return lib;
        } catch (Throwable t) {
            logger.debug("OpenBLAS not available: " + t.getMessage());
            return null;
        }
    }

    /**
     * Returns the OpenBLAS build configuration string, e.g.
     * {@code "OpenBLAS 0.3.30 NEOVERSEV2 MAX_THREADS=1"}.
     */
    @Function("openblas_get_config")
    public abstract String config();

    /**
     * Raw CBLAS SGEMM binding. Parameters follow the CBLAS convention exactly:
     * {@code C = alpha * A @ B + beta * C}, where A is (m x k), B is (k x n), C is (m x n),
     * all row-major with leading dimensions lda, ldb, ldc respectively.
     */
    @Function("cblas_sgemm")
    @Critical(fallbackAdapter = Critical.UnsupportedFallback.class)
    protected abstract void sgemm(
        int layout,
        int transA,
        int transB,
        int m,
        int n,
        int k,
        float alpha,
        MemorySegment a,
        int lda,
        MemorySegment b,
        int ldb,
        float beta,
        MemorySegment c,
        int ldc
    );

    /**
     * Raw CBLAS SGEMV binding. Parameters follow the CBLAS convention exactly:
     * {@code y = alpha * A @ x + beta * y}, where A is (m x n) row-major with leading
     * dimension lda, x has length n, and y has length m.
     */
    @Function("cblas_sgemv")
    @Critical(fallbackAdapter = Critical.UnsupportedFallback.class)
    protected abstract void sgemv(
        int layout,
        int trans,
        int m,
        int n,
        float alpha,
        MemorySegment a,
        int lda,
        MemorySegment x,
        int incx,
        float beta,
        MemorySegment y,
        int incy
    );

    /**
     * Computes {@code C = A @ B}, overwriting C. A is (m x k), B is (k x n), C is (m x n),
     * all row-major. Semantically identical to
     * {@link org.elasticsearch.simdvec.ESVectorUtil#matrixMultiply(float[], float[], int, int, int, float[])}.
     */
    public void matrixMultiply(float[] a, float[] b, int m, int k, int n, float[] c) {
        // Note: CBLAS argument order for matrix dimensions is (m, n, k), not (m, k, n).
        sgemm(
            CBLAS_ROW_MAJOR,
            CBLAS_NO_TRANS,
            CBLAS_NO_TRANS,
            m,
            n,
            k,
            1.0f,
            MemorySegment.ofArray(a),
            k,
            MemorySegment.ofArray(b),
            n,
            0.0f,
            MemorySegment.ofArray(c),
            n
        );
    }

    /**
     * Computes {@code result = A @ v}, overwriting result. A is (rows x cols) row-major,
     * v has length cols, result has length rows. Semantically identical to
     * {@link org.elasticsearch.simdvec.ESVectorUtil#matrixVectorMultiply(float[], int, int, float[], float[])}.
     */
    public void matrixVectorMultiply(float[] a, int rows, int cols, float[] v, float[] result) {
        sgemv(
            CBLAS_ROW_MAJOR,
            CBLAS_NO_TRANS,
            rows,
            cols,
            1.0f,
            MemorySegment.ofArray(a),
            cols,
            MemorySegment.ofArray(v),
            1,
            0.0f,
            MemorySegment.ofArray(result),
            1
        );
    }
}
