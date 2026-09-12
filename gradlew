#!/bin/bash
#
# gradlew — Gradle wrapper script for the Internet Optimizer project.
#
# This script checks if the Gradle wrapper jar is available. If not,
# it falls back to using a system-installed gradle command.
#
# Prerequisites for building:
#   - JDK 17+ (JAVA_HOME must be set)
#   - Android SDK (ANDROID_HOME or ANDROID_SDK_ROOT must be set)
#     with build-tools 35.0.0+, platform-tools, platforms;android-35
#   - Android NDK r26+ (for CMake native build)
#   - Rust + cargo-ndk (for the native tunnel library)
#
# Usage:
#   ./gradlew assembleDebug       # Build debug APK
#   ./gradlew assembleRelease     # Build release APK
#   ./gradlew clean               # Clean build outputs

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_JAR="${SCRIPT_DIR}/gradle/wrapper/gradle-wrapper.jar"
GRADLE_CMD="gradle"

# Check if we can use the wrapper jar
if [ -f "${WRAPPER_JAR}" ] && [ -n "${JAVA_HOME:-}" ]; then
    exec "${JAVA_HOME}/bin/java" \
        -Xmx4096m \
        -Dfile.encoding=UTF-8 \
        -cp "${WRAPPER_JAR}" \
        org.gradle.wrapper.GradleWrapperMain "$@"
fi

# Fall back to system gradle
if command -v "${GRADLE_CMD}" &>/dev/null; then
    echo "Using system gradle: $(which ${GRADLE_CMD})"
    exec gradle "$@"
fi

echo "ERROR: Neither gradle-wrapper.jar nor system 'gradle' found."
echo ""
echo "To build this project, you need:"
echo "  1. Download the Gradle wrapper jar manually:"
echo "     curl -o ${WRAPPER_JAR} https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar"
echo "  2. Or install Gradle system-wide:"
echo "     sdk install gradle 8.13"
echo "  3. Ensure JAVA_HOME points to JDK 17+"
echo "  4. Ensure ANDROID_HOME points to your Android SDK"
echo ""
echo "Native library build:"
echo "  cd app/src/main/cpp && ./build-native.sh"
echo "  (requires Rust + cargo-ndk + Android NDK)"

exit 1
