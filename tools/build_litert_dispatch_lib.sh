#!/usr/bin/env bash
# Build a libLiteRt.so for Android arm64 that includes the LiteRtQualcomm* options
# symbols the Qualcomm dispatch library depends on. The publicly-distributed
# litert:2.1.4 AAR ships a 5 MB libLiteRt.so without those symbols; the Bazel build
# below produces the larger build (~30+ MB) the official sample app comments call
# the "CLI build".
#
# This is heavy. First-time run downloads ~5 GB of Bazel deps (TensorFlow,
# XNNPACK, abseil, etc.) and writes ~25 GB to ~/.cache/bazel. Allow 30–90 min on
# a fast machine. Subsequent incremental builds are minutes.
#
# Outputs: $REPO_ROOT/android/app/src/main/jniLibs/arm64-v8a/libLiteRt.so
#
# Idempotent: re-running picks up where it stopped (Bazel handles caching).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LITERT_DIR="$HOME/projects/litert"
LITERT_TAG="v2.1.4"
DEST_DIR="$REPO_ROOT/android/app/src/main/jniLibs/arm64-v8a"

# --- 1. Ensure Bazelisk is installed ----------------------------------------
if ! command -v bazel >/dev/null 2>&1; then
    if ! command -v brew >/dev/null 2>&1; then
        echo "Homebrew not found. Install bazelisk manually:" >&2
        echo "  https://github.com/bazelbuild/bazelisk" >&2
        exit 1
    fi
    echo "Installing bazelisk via Homebrew (resolves to Bazel 7.7.0 per repo's .bazelversion)..."
    brew install bazelisk
fi

# --- 2. Clone or update the LiteRT source -----------------------------------
if [[ ! -d "$LITERT_DIR/.git" ]]; then
    echo "Cloning LiteRT $LITERT_TAG to $LITERT_DIR..."
    mkdir -p "$(dirname "$LITERT_DIR")"
    git clone --depth 1 --branch "$LITERT_TAG" \
        https://github.com/google-ai-edge/LiteRT.git "$LITERT_DIR"
else
    echo "Existing LiteRT checkout at $LITERT_DIR — using as-is."
    echo "  Tag/HEAD: $(cd "$LITERT_DIR" && git describe --tags --dirty 2>/dev/null || git rev-parse --short HEAD)"
fi

cd "$LITERT_DIR"

# --- 3. Pick an installed NDK ------------------------------------------------
NDK_CANDIDATES=(
    "$HOME/Library/Android/sdk/ndk/25.2.9519653"
    "$HOME/Library/Android/sdk/ndk/25.1.8937393"
    "${ANDROID_NDK_HOME:-}"
)
ANDROID_NDK_HOME=""
for candidate in "${NDK_CANDIDATES[@]}"; do
    if [[ -n "$candidate" && -d "$candidate" ]]; then
        ANDROID_NDK_HOME="$candidate"
        break
    fi
done
if [[ -z "$ANDROID_NDK_HOME" ]]; then
    echo "No Android NDK found. Install NDK r25.2 via Android Studio SDK Manager." >&2
    exit 1
fi
export ANDROID_NDK_HOME
export ANDROID_NDK_API_LEVEL=26
export ANDROID_BUILD_TOOLS_VERSION="$(ls -1 "$HOME/Library/Android/sdk/build-tools" | sort -rV | head -1)"
export ANDROID_SDK_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_API_LEVEL=35
export TF_SET_ANDROID_WORKSPACE=1
export PYTHON_BIN_PATH="$(which python3)"
export USE_DEFAULT_PYTHON_LIB_PATH=1
# LiteRT v2.1.4 ships requirements_lock.txt only for Python 3.10–3.13. Pinning to 3.12
# tells Bazel's hermetic Python repo rule to download that version (no local install
# needed). Without this, builds on macOS with Python 3.14 in the system path abort
# during repo fetch with "Could not find requirements_lock.txt file matching ...".
export HERMETIC_PYTHON_VERSION=3.12

echo "ANDROID_NDK_HOME=$ANDROID_NDK_HOME"
echo "ANDROID_BUILD_TOOLS_VERSION=$ANDROID_BUILD_TOOLS_VERSION"

# --- 4. Run ./configure (non-interactively via env) -------------------------
if [[ ! -f "./.tf_configure.bazelrc" ]] || ! grep -q "ANDROID_NDK_HOME" ./.tf_configure.bazelrc 2>/dev/null; then
    echo "Running ./configure with Android workspace enabled..."
    # configure.py reads TF_SET_ANDROID_WORKSPACE and the ANDROID_* vars and writes
    # .tf_configure.bazelrc. Pipe empty stdin to skip remaining prompts.
    yes "" | ./configure || true
fi

# --- 5. Build the shared library --------------------------------------------
echo ""
echo "Starting Bazel build. This is the long step — go make coffee."
echo "Target: //litert/c:litert_runtime_c_api_so"
echo ""

bazel build \
    -c opt \
    --config=android_arm64 \
    --repo_env=HERMETIC_PYTHON_VERSION=3.12 \
    //litert/c:litert_runtime_c_api_so \
    --verbose_failures

# --- 6. Copy the .so into the Vela project ----------------------------------
BUILT_SO="$LITERT_DIR/bazel-bin/litert/c/libLiteRt.so"
if [[ ! -f "$BUILT_SO" ]]; then
    echo "Build claimed success but $BUILT_SO is missing." >&2
    echo "Searching bazel-out for any libLiteRt.so..." >&2
    find "$LITERT_DIR/bazel-out" -name "libLiteRt.so" 2>/dev/null
    exit 1
fi

mkdir -p "$DEST_DIR"
cp -f "$BUILT_SO" "$DEST_DIR/libLiteRt.so"

echo ""
echo "===================================================="
echo "Built libLiteRt.so:  $(ls -la "$DEST_DIR/libLiteRt.so")"
echo ""
echo "Spot-check exports for the Qualcomm symbols:"
xcrun llvm-nm -D --defined-only "$DEST_DIR/libLiteRt.so" 2>/dev/null | grep -c "LiteRtQualcomm" || true
echo "  ^ should be > 0 (the AAR ships 0)"
echo "===================================================="
echo ""
echo "Now: cd android && ./gradlew installDebug"
echo "The pickFirsts rule in app/build.gradle.kts forces this version over the AAR's."
