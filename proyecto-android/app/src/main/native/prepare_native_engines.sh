#!/usr/bin/env bash
# Chat Pro (solo chat): descarga y compila llama.cpp (CPU, arm64) y lo empaqueta como libchatpro-llama.so.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN_DIR="$ROOT_DIR/native/.toolchains"
BUILD_DIR="$ROOT_DIR/native/.build"
JNI_DIR="$ROOT_DIR/native/jniLibs/arm64-v8a"
LLAMA_REF="${LLAMA_CPP_REF:-v0.6.0}"
NDK_VERSION="${ANDROID_NDK_VERSION:-29.0.13113456}"
CMAKE_VERSION="${ANDROID_CMAKE_VERSION:-3.31.6}"

log() { printf '\n[DevChat native] %s\n' "$*"; }
fail() { printf '\n[DevChat native][ERROR] %s\n' "$*" >&2; exit 1; }

if [[ "${CHATPRO_SKIP_NATIVE:-0}" == "1" ]]; then
    log "CHATPRO_SKIP_NATIVE=1: se omite la compilación del motor nativo."
    exit 0
fi

command -v git >/dev/null 2>&1 || fail "git no está disponible en el runner."

SDKMANAGER="$(command -v sdkmanager || true)"
if [[ -z "$SDKMANAGER" ]]; then
    for candidate in \
        "${ANDROID_SDK_ROOT:-}/cmdline-tools/latest/bin/sdkmanager" \
        "${ANDROID_HOME:-}/cmdline-tools/latest/bin/sdkmanager"; do
        if [[ -x "$candidate" ]]; then SDKMANAGER="$candidate"; break; fi
    done
fi
[[ -n "$SDKMANAGER" ]] || fail "No se encontró sdkmanager. El workflow debe preparar Android SDK antes de ejecutar Gradle."

log "Asegurando Android NDK $NDK_VERSION y CMake $CMAKE_VERSION"
yes | "$SDKMANAGER" "ndk;$NDK_VERSION" >/dev/null 2>&1 || "$SDKMANAGER" "ndk;$NDK_VERSION"
yes | "$SDKMANAGER" "cmake;$CMAKE_VERSION" >/dev/null 2>&1 || "$SDKMANAGER" "cmake;$CMAKE_VERSION"

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$SDK_ROOT" ]] || fail "ANDROID_SDK_ROOT/ANDROID_HOME no está definido."
ANDROID_NDK="$SDK_ROOT/ndk/$NDK_VERSION"
[[ -d "$ANDROID_NDK" ]] || fail "No existe el NDK esperado: $ANDROID_NDK"
export ANDROID_NDK

# CMake + Ninja del SDK de Android primero en el PATH.
CMAKE_BIN="$SDK_ROOT/cmake/$CMAKE_VERSION/bin"
[[ -d "$CMAKE_BIN" ]] && export PATH="$CMAKE_BIN:$PATH"
command -v cmake >/dev/null 2>&1 || fail "cmake no está disponible."
command -v ninja >/dev/null 2>&1 || fail "ninja no está disponible (debería venir con cmake;$CMAKE_VERSION del SDK)."

mkdir -p "$TOOLCHAIN_DIR" "$BUILD_DIR" "$JNI_DIR"

clone_repo() {
    local url="$1" dest="$2" ref="$3"
    if [[ ! -f "$dest/CMakeLists.txt" ]]; then
        rm -rf "$dest"
        log "Descargando historial ligero de $url para resolver exactamente @ $ref"
        git clone --filter=blob:none --no-checkout "$url" "$dest"
    else
        log "Reutilizando $dest"
    fi
    git -C "$dest" cat-file -e "$ref^{commit}" 2>/dev/null || \
        fail "No se pudo resolver '$ref' como commit/tag en $url. No se hará fallback a master."
    git -C "$dest" checkout --detach "$ref"
    local resolved_commit
    resolved_commit="$(git -C "$dest" rev-parse HEAD)"
    [[ -n "$resolved_commit" ]] || fail "Git no devolvió un commit válido después del checkout de $ref"
    log "Commit resuelto: $resolved_commit"
    git -C "$dest" submodule sync --recursive
    git -C "$dest" submodule update --init --recursive --depth 1
}

clone_repo "https://github.com/ggml-org/llama.cpp.git" "$TOOLCHAIN_DIR/llama.cpp" "$LLAMA_REF"

mkdir -p "$BUILD_DIR/llama"
log "Configurando llama.cpp"
cmake -S "$ROOT_DIR/native/llama" -B "$BUILD_DIR/llama" \
    -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-28 \
    -DANDROID_STL=c++_static \
    -DCMAKE_C_FLAGS="-fPIC" \
    -DCMAKE_CXX_FLAGS="-fPIC" \
    -DLLAMA_SOURCE="$TOOLCHAIN_DIR/llama.cpp" \
    -DGGML_NATIVE=OFF \
    -DGGML_OPENMP=OFF \
    -DGGML_LLAMAFILE=OFF
log "Compilando llama.cpp"
cmake --build "$BUILD_DIR/llama" --config Release -j "${CMAKE_BUILD_JOBS:-$(nproc)}"

LLAMA_SO="$(find "$BUILD_DIR/llama" -type f -name 'libchatpro-llama.so' -print -quit)"
[[ -n "$LLAMA_SO" ]] || fail "No se encontró libchatpro-llama.so tras compilar llama.cpp."
cp -f "$LLAMA_SO" "$JNI_DIR/libchatpro-llama.so"

cat > "$JNI_DIR/build-info.txt" <<INFO
llama_cpp_ref=$LLAMA_REF
android_ndk=$NDK_VERSION
android_cmake=$CMAKE_VERSION
abi=arm64-v8a
android_platform=28
native_stl=c++_static
INFO

log "Motor nativo preparado:"
ls -lh "$JNI_DIR/libchatpro-llama.so"
