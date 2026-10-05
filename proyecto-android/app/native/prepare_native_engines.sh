#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN_DIR="$ROOT_DIR/native/.toolchains"
BUILD_DIR="$ROOT_DIR/native/.build"
JNI_DIR="$ROOT_DIR/native/jniLibs/arm64-v8a"
LLAMA_REF="${LLAMA_CPP_REF:-v0.6.0}"
SD_REF="${STABLE_DIFFUSION_CPP_REF:-master}"
NDK_VERSION="${ANDROID_NDK_VERSION:-29.0.13113456}"
CMAKE_VERSION="${ANDROID_CMAKE_VERSION:-3.31.6}"

log() { printf '\n[Chat Pro native] %s\n' "$*"; }
fail() { printf '\n[Chat Pro native][ERROR] %s\n' "$*" >&2; exit 1; }

if [[ "${CHATPRO_SKIP_NATIVE:-0}" == "1" ]]; then
    log "CHATPRO_SKIP_NATIVE=1: se omite la compilación de motores nativos."
    exit 0
fi

command -v git >/dev/null 2>&1 || fail "git no está disponible en el runner."
command -v cmake >/dev/null 2>&1 || fail "cmake no está disponible en el runner."

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

# Put the Android CMake/Ninja/shader tool paths first when present.
CMAKE_BIN="$SDK_ROOT/cmake/$CMAKE_VERSION/bin"
[[ -d "$CMAKE_BIN" ]] && export PATH="$CMAKE_BIN:$PATH"
for shader_dir in \
    "$ANDROID_NDK/shader-tools/linux-x86_64" \
    "$ANDROID_NDK/shader-tools/linux-x86_64/bin"; do
    if [[ -d "$shader_dir" ]]; then export PATH="$shader_dir:$PATH"; fi
done

mkdir -p "$TOOLCHAIN_DIR" "$BUILD_DIR" "$JNI_DIR"

clone_repo() {
    local url="$1" dest="$2" ref="$3"
    local marker="$dest/.chatpro-ref"
    if [[ ! -f "$dest/CMakeLists.txt" || ! -f "$marker" || "$(cat "$marker" 2>/dev/null || true)" != "$ref" ]]; then
        rm -rf "$dest"
        log "Descargando $url @ $ref"
        git clone --depth 1 --recurse-submodules --shallow-submodules --branch "$ref" "$url" "$dest"
        printf '%s\n' "$ref" > "$marker"
    else
        log "Reutilizando $dest @ $ref"
    fi
}

clone_repo "https://github.com/ggml-org/llama.cpp.git" "$TOOLCHAIN_DIR/llama.cpp" "$LLAMA_REF"
clone_repo "https://github.com/leejet/stable-diffusion.cpp.git" "$TOOLCHAIN_DIR/stable-diffusion.cpp" "$SD_REF"

build_one() {
    local name="$1" source="$2" build="$3" cmake_list="$4"
    shift 4
    mkdir -p "$build"
    log "Configurando $name"
    local generator_args=()
    if command -v ninja >/dev/null 2>&1; then
        generator_args=(-G Ninja)
    fi
    cmake -S "$cmake_list" -B "$build" \
        "${generator_args[@]}" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a \
        -DANDROID_PLATFORM=android-28 \
        -DANDROID_STL=c++_static \
        -DCMAKE_C_FLAGS="-fPIC" \
        -DCMAKE_CXX_FLAGS="-fPIC" \
        -D"$([[ "$name" == "llama.cpp" ]] && printf 'LLAMA_SOURCE' || printf 'SD_SOURCE')=$source" \
        "$@"
    log "Compilando $name"
    cmake --build "$build" --config Release -j "${CMAKE_BUILD_JOBS:-$(nproc)}"
}

build_one "llama.cpp" \
    "$TOOLCHAIN_DIR/llama.cpp" \
    "$BUILD_DIR/llama" \
    "$ROOT_DIR/native/llama" \
    -DANDROID_PLATFORM=android-28 \
    -DGGML_NATIVE=OFF \
    -DGGML_OPENMP=OFF \
    -DGGML_LLAMAFILE=OFF

build_one "stable-diffusion.cpp" \
    "$TOOLCHAIN_DIR/stable-diffusion.cpp" \
    "$BUILD_DIR/diffusion" \
    "$ROOT_DIR/native/diffusion" \
    -DANDROID_PLATFORM=android-28 \
    -DSD_VULKAN=ON \
    -DSD_WEBP=OFF \
    -DSD_WEBM=OFF \
    -DGGML_NATIVE=OFF \
    -DGGML_OPENMP=OFF \
    -DGGML_LLAMAFILE=OFF

LLAMA_SO="$(find "$BUILD_DIR/llama" -type f -name 'libchatpro-llama.so' -print -quit)"
DIFFUSION_SO="$(find "$BUILD_DIR/diffusion" -type f -name 'libchatpro-diffusion.so' -print -quit)"

[[ -n "$LLAMA_SO" ]] || fail "No se encontró libchatpro-llama.so tras compilar llama.cpp."
[[ -n "$DIFFUSION_SO" ]] || fail "No se encontró libchatpro-diffusion.so tras compilar stable-diffusion.cpp."

cp -f "$LLAMA_SO" "$JNI_DIR/libchatpro-llama.so"
cp -f "$DIFFUSION_SO" "$JNI_DIR/libchatpro-diffusion.so"

cat > "$JNI_DIR/build-info.txt" <<INFO
llama_cpp_ref=$LLAMA_REF
stable_diffusion_cpp_ref=$SD_REF
android_ndk=$NDK_VERSION
android_cmake=$CMAKE_VERSION
abi=arm64-v8a
android_platform=28
native_stl=c++_static
INFO

log "Motores nativos preparados:"
ls -lh "$JNI_DIR/libchatpro-llama.so" "$JNI_DIR/libchatpro-diffusion.so"
