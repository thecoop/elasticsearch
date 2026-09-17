#!/bin/bash
# PROTOTYPE ONLY. Not part of any distribution or CI job.
#
# Builds a single-threaded OpenBLAS with Neoverse V2 (Graviton4) kernels and
# copies libopenblas.so into the location that es.nativelibs.path resolves to,
# so :libs:simdvec:test and :libs:simdvec:benchmark pick it up automatically.
#
# Run on the Graviton4 EC2 instance after checking out the repo:
#
#   ./gradlew :libs:native:native-libraries:extractLibs   # populate the platform dir first
#   libs/simdvec/native/build_openblas_graviton4.sh
#   ls -l libs/native/libraries/build/platform/linux-aarch64/libopenblas.so
#
# Requirements: gcc/g++, gfortran is NOT required (ONLY_CBLAS=1 skips LAPACK).
# Build time: ~10 min on a 64-core Graviton4 instance.
set -euo pipefail

VERSION="${VERSION:-0.3.30}"
WORK="${WORK:-/tmp/openblas-build}"
REPO_ROOT=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
DEST="${REPO_ROOT}/libs/native/libraries/build/platform/linux-aarch64"

echo "Building OpenBLAS ${VERSION} for Neoverse V2 (single-threaded) ..."
mkdir -p "${WORK}"
cd "${WORK}"

if [ ! -d OpenBLAS ]; then
    git clone --depth 1 --branch "v${VERSION}" https://github.com/OpenMathLib/OpenBLAS.git
fi

cd OpenBLAS
make -j"$(nproc)" TARGET=NEOVERSEV2 USE_THREAD=0 USE_OPENMP=0 ONLY_CBLAS=1

mkdir -p "${DEST}"
# cp -L dereferences the symlink to get the real .so
cp -L libopenblas.so "${DEST}/libopenblas.so"

echo "Installed: ${DEST}/libopenblas.so"
echo ""
echo "Verify the target with:"
echo "  strings ${DEST}/libopenblas.so | grep -i 'neoverse\|config\|thread'"
