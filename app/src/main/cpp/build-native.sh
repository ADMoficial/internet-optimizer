#!/bin/bash
#
# Internet Optimizer — build script for the Rust native library.
#
# This script cross-compiles the Rust crate (src/lib.rs) for all
# Android ABIs and places the resulting .so files in the correct
# jniLibs/ directories for the Android Gradle plugin to package them.
#
# Prerequisites:
#   - Rust (rustup + cargo)
#   - Android NDK (or set ANDROID_NDK_HOME)
#   - cargo-ndk (cargo install cargo-ndk)
#
# Usage:
#   ./build-native.sh
#
# For a full Gradle build, run:
#   ./gradlew assembleDebug
# The Gradle ExternalNativeBuild CMake config will also trigger this
# script if configured properly, but running it separately is fine
# since the .so files are pre-built and packaged via jniLibs.

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
RUST_CRATE="${PROJECT_ROOT}/../../internet-optimizer-rust"
OUTPUT_DIR="${PROJECT_ROOT}/src/main/jniLibs"

echo "=== Building native tunnel library ==="
echo "Rust crate: ${RUST_CRATE}"
echo "Output dir: ${OUTPUT_DIR}"

# Install cargo-ndk if not present
if ! command -v cargo-ndk &>/dev/null; then
    echo "Installing cargo-ndk..."
    cargo install cargo-ndk --locked
fi

# Cross-compile for all supported ABIs
cd "${RUST_CRATE}"

# NOTE: On this Termux environment, only aarch64-linux-android has complete
# rlib std libraries. armv7 and x86_64 targets may fail with "rlib not found"
# errors. On a full desktop Rust toolchain, all three ABIs compile normally.
# The primary target (arm64-v8a) is built and packaged in all cases.

ABIS=("arm64-v8a")  # Primary target for modern Android devices

for abi in "${ABIS[@]}"; do
    echo "Compiling for ${abi}..."
    cargo ndk -t "${abi}" build --release || {
        echo "  -> Fallback: direct cargo build for ${abi}"
        case "${abi}" in
            arm64-v8a) rust_abi="aarch64-linux-android" ;;
        esac
        cargo build --target "${rust_abi}" --release
    }
done

# Also attempt armv7 and x86_64 (may fail on minimal toolchains)
for abi in "armeabi-v7a" "x86_64"; do
    echo "Compiling for ${abi} (optional)..."
    cargo ndk -t "${abi}" build --release 2>/dev/null || \
        echo "  -> SKIP: ${abi} (rlib not available — use full Rust toolchain)"
done

# Copy the built .so files into jniLibs/
for abi in "${ABIS[@]}"; do
    case "${abi}" in
        arm64-v8a)
            rust_abi="aarch64-linux-android"
            ;;
        armeabi-v7a)
            rust_abi="armv7-linux-androideabi"
            ;;
        x86_64)
            rust_abi="x86_64-linux-android"
            ;;
    esac

    so_file="${RUST_CRATE}/target/${rust_abi}/release/libtun_interface.so"
    if [ -f "${so_file}" ]; then
        mkdir -p "${OUTPUT_DIR}/${abi}"
        cp "${so_file}" "${OUTPUT_DIR}/${abi}/libtun_interface.so"
        echo "  -> Installed: ${OUTPUT_DIR}/${abi}/libtun_interface.so"
    else
        echo "  -> WARNING: ${so_file} not found (check Rust target)"
    fi
done

echo "=== Native library build complete ==="
echo "Run ./gradlew assembleDebug to build the full APK."
