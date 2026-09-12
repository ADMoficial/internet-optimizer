# CMakeLists.txt
#
# This file configures the Android NDK build for the Rust native library.
# Since Rust produces its own .so files (via cargo-ndk), this CMake file
# is a thin wrapper that either:
#   1. Uses a pre-built .so file from jniLibs/ (if present), OR
#   2. Triggers the build-native.sh script to compile from source.
#
# In a standard Gradle build, the .so files are placed in:
#   app/src/main/jniLibs/<abi>/libtun_interface.so
# and are automatically packaged into the APK by the Android Gradle Plugin.
#
# This CMakeLists.txt is required by the externalNativeBuild config
# in build.gradle. It registers libtun_interface as a shared library
# so Gradle knows to look for it at link time.

cmake_minimum_required(VERSION 3.22.1)

project("tun_interface")

# Import the pre-built shared library for each ABI.
# The actual compilation happens in Rust (cargo-ndk), not in CMake.
#
# If the .so files are not present, run:
#   ./app/src/main/cpp/build-native.sh
# before building the APK.

add_library(
    tun_interface
    SHARED
    IMPORTED
)

# Only arm64-v8a is supported for now (the Rust crate was built for this ABI).
set(_so_path_arm64 "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../internet-optimizer-rust/target/aarch64-linux-android/release/libtun_interface.so")
if(EXISTS "${_so_path_arm64}")
    set_target_properties(tun_interface PROPERTIES
        IMPORTED_LOCATION "${_so_path_arm64}"
    )
else()
    message(FATAL_ERROR "libtun_interface.so not found for arm64-v8a. Run build-native.sh first.")
endif()

# Log a message so the developer knows the native lib is managed by Rust
message(STATUS "tun_interface: native library built via Rust cargo-ndk")
