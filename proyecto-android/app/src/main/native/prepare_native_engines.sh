#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN_DIR="$ROOT_DIR/native/.toolchains"
BUILD_DIR="$ROOT_DIR/native/.build"
JNI_DIR="$ROOT_DIR/native/jniLibs/arm64-v8a"
SD_REF="${STABLE_DIFFUSION_CPP_REF:-3f8527a}"
NDK_VERSION="${ANDROID_NDK_VERSION:-29.0.13113456}"
CMAKE_VERSION="${ANDROID_CMAKE_VERSION:-3.31.6}"

log() { printf '\n[Chat Pro native] %s\n' "$*"; }
fail() { printf '\n[Chat Pro native][ERROR] %s\n' "$*" >&2; exit 1; }

if [[ "${CHATPRO_SKIP_NATIVE:-0}" == "1" ]]; then
    log "CHATPRO_SKIP_NATIVE=1: se omite la compilación nativa."
    exit 0
fi

command -v git >/dev/null 2>&1 || fail "git no está disponible."
command -v cmake >/dev/null 2>&1 || fail "cmake no está disponible."

if command -v apt-get >/dev/null 2>&1; then
    log "Instalando dependencias Vulkan/SPIR-V"
    sudo apt-get update
    sudo apt-get install -y --no-install-recommends \
        build-essential libvulkan-dev glslc glslang-tools spirv-headers
fi

command -v glslc >/dev/null 2>&1 || fail "glslc no encontrado."
command -v glslangValidator >/dev/null 2>&1 || fail "glslangValidator no encontrado."

SPIRV_PREFIX="$TOOLCHAIN_DIR/spirv-headers-install"
SPIRV_HEADERS_DIR=""
SPIRV_SRC="$TOOLCHAIN_DIR/SPIRV-Headers"
for candidate in /usr/share/cmake/SPIRV-Headers /usr/lib/*/cmake/SPIRV-Headers; do
    if [[ -f "$candidate/SPIRV-HeadersConfig.cmake" ]]; then SPIRV_HEADERS_DIR="$candidate"; break; fi
done

if [[ -z "$SPIRV_HEADERS_DIR" ]]; then
    SPIRV_BUILD="$BUILD_DIR/spirv-headers-host"
    if [[ ! -f "$SPIRV_SRC/CMakeLists.txt" ]]; then
        rm -rf "$SPIRV_SRC"
        git clone --depth 1 https://github.com/KhronosGroup/SPIRV-Headers.git "$SPIRV_SRC"
    fi
    rm -rf "$SPIRV_BUILD"
    cmake -S "$SPIRV_SRC" -B "$SPIRV_BUILD" -G Ninja \
        -DSPIRV_HEADERS_ENABLE_TESTS=OFF \
        -DSPIRV_HEADERS_ENABLE_INSTALL=ON \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$SPIRV_PREFIX"
    cmake --build "$SPIRV_BUILD" --target install --config Release
    SPIRV_HEADERS_DIR="$SPIRV_PREFIX/share/cmake/SPIRV-Headers"
fi

SPIRV_INCLUDE_DIR=""
for candidate in "$SPIRV_PREFIX/include" /usr/include /usr/local/include; do
    if [[ -f "$candidate/spirv/unified1/spirv.hpp" ]]; then SPIRV_INCLUDE_DIR="$candidate"; break; fi
done
if [[ -z "$SPIRV_INCLUDE_DIR" && -f "$SPIRV_SRC/include/spirv/unified1/spirv.hpp" ]]; then
    SPIRV_INCLUDE_DIR="$SPIRV_SRC/include"
fi
[[ -n "$SPIRV_INCLUDE_DIR" ]] || fail "No se encontró spirv.hpp"
SPIRV_LOCAL_INCLUDE="$TOOLCHAIN_DIR/spirv-include"
rm -rf "$SPIRV_LOCAL_INCLUDE"; mkdir -p "$SPIRV_LOCAL_INCLUDE"
cp -r "$SPIRV_INCLUDE_DIR/spirv" "$SPIRV_LOCAL_INCLUDE/spirv"
SPIRV_INCLUDE_DIR="$SPIRV_LOCAL_INCLUDE"

SDKMANAGER="$(command -v sdkmanager || true)"
if [[ -z "$SDKMANAGER" ]]; then
    for candidate in "${ANDROID_SDK_ROOT:-}/cmdline-tools/latest/bin/sdkmanager" "${ANDROID_HOME:-}/cmdline-tools/latest/bin/sdkmanager"; do
        if [[ -x "$candidate" ]]; then SDKMANAGER="$candidate"; break; fi
    done
fi
[[ -n "$SDKMANAGER" ]] || fail "sdkmanager no encontrado."

log "Asegurando NDK $NDK_VERSION y CMake $CMAKE_VERSION"
yes | "$SDKMANAGER" "ndk;$NDK_VERSION" >/dev/null 2>&1 || "$SDKMANAGER" "ndk;$NDK_VERSION"
yes | "$SDKMANAGER" "cmake;$CMAKE_VERSION" >/dev/null 2>&1 || "$SDKMANAGER" "cmake;$CMAKE_VERSION"

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$SDK_ROOT" ]] || fail "SDK_ROOT no definido."
ANDROID_NDK="$SDK_ROOT/ndk/$NDK_VERSION"
[[ -d "$ANDROID_NDK" ]] || fail "NDK no existe: $ANDROID_NDK"
export ANDROID_NDK

NDK_VULKAN_CORE="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/vulkan/vulkan_core.h"
[[ -f "$NDK_VULKAN_CORE" ]] || fail "vulkan_core.h no encontrado."

VK_HEADER_VERSION="$(awk '/^[[:space:]]*#define[[:space:]]+VK_HEADER_VERSION[[:space:]]+[0-9]+/{print $3; exit}' "$NDK_VULKAN_CORE")"
VK_HEADER_VERSION_COMPLETE="$(awk '/^[[:space:]]*#define[[:space:]]+VK_HEADER_VERSION_COMPLETE/{print; exit}' "$NDK_VULKAN_CORE")"
VK_MAJOR="$(printf '%s\n' "$VK_HEADER_VERSION_COMPLETE" | sed -n 's/.*VK_MAKE_API_VERSION([^,]*,[[:space:]]*\([0-9][0-9]*\),[[:space:]]*\([0-9][0-9]*\),.*/\1/p')"
VK_MINOR="$(printf '%s\n' "$VK_HEADER_VERSION_COMPLETE" | sed -n 's/.*VK_MAKE_API_VERSION([^,]*,[[:space:]]*\([0-9][0-9]*\),[[:space:]]*\([0-9][0-9]*\),.*/\2/p')"
VULKAN_HEADERS_VERSION="${VK_MAJOR}.${VK_MINOR}.${VK_HEADER_VERSION}"
VULKAN_HEADERS_TAG="v${VULKAN_HEADERS_VERSION}"
VULKAN_HEADERS_SRC="$TOOLCHAIN_DIR/Vulkan-Headers"
VULKAN_HEADERS_INCLUDE_DIR="$VULKAN_HEADERS_SRC/include"

log "NDK Vulkan: ${VULKAN_HEADERS_VERSION}"
if ! git ls-remote --exit-code --refs https://github.com/KhronosGroup/Vulkan-Headers.git "refs/tags/${VULKAN_HEADERS_TAG}" >/dev/null 2>&1; then
    fail "Khronos no tiene el tag ${VULKAN_HEADERS_TAG}"
fi

if [[ ! -f "$VULKAN_HEADERS_INCLUDE_DIR/vulkan/vulkan.hpp" ]]; then
    rm -rf "$VULKAN_HEADERS_SRC"
    git clone --depth 1 --branch "$VULKAN_HEADERS_TAG" https://github.com/KhronosGroup/Vulkan-Headers.git "$VULKAN_HEADERS_SRC"
fi

CMAKE_BIN="$SDK_ROOT/cmake/$CMAKE_VERSION/bin"
[[ -d "$CMAKE_BIN" ]] && export PATH="$CMAKE_BIN:$PATH"
for shader_dir in "$ANDROID_NDK/shader-tools/linux-x86_64" "$ANDROID_NDK/shader-tools/linux-x86_64/bin"; do
    if [[ -d "$shader_dir" ]]; then export PATH="$shader_dir:$PATH"; fi
done

mkdir -p "$TOOLCHAIN_DIR" "$BUILD_DIR" "$JNI_DIR"

clone_repo() {
    local url="$1" dest="$2" ref="$3"
    if [[ ! -f "$dest/CMakeLists.txt" ]]; then
        rm -rf "$dest"
        git clone --filter=blob:none --no-checkout "$url" "$dest"
    fi
    git -C "$dest" cat-file -e "$ref^{commit}" 2>/dev/null || fail "No se pudo resolver '$ref' en $url"
    git -C "$dest" checkout --detach "$ref"
    git -C "$dest" submodule sync --recursive
    git -C "$dest" submodule update --init --recursive --depth 1
}

clone_repo "https://github.com/leejet/stable-diffusion.cpp.git" "$TOOLCHAIN_DIR/stable-diffusion.cpp" "$SD_REF"

log "Configurando stable-diffusion.cpp"
cmake -S "$ROOT_DIR/native/diffusion" -B "$BUILD_DIR/diffusion" \
    -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-28 \
    -DANDROID_STL=c++_static \
    -DCMAKE_C_FLAGS="-fPIC" \
    -DCMAKE_CXX_FLAGS="-fPIC" \
    -DSD_SOURCE="$TOOLCHAIN_DIR/stable-diffusion.cpp" \
    -DSPIRV-Headers_DIR="$SPIRV_HEADERS_DIR" \
    -DVULKAN_HEADERS_INCLUDE_DIR="$VULKAN_HEADERS_INCLUDE_DIR" \
    -DSPIRV_HEADERS_INCLUDE_DIR="$SPIRV_INCLUDE_DIR"

log "Compilando stable-diffusion.cpp"
cmake --build "$BUILD_DIR/diffusion" --config Release -j "${CMAKE_BUILD_JOBS:-$(nproc)}"

DIFFUSION_SO="$(find "$BUILD_DIR/diffusion" -type f -name 'libchatpro-diffusion.so' -print -quit)"
[[ -n "$DIFFUSION_SO" ]] || fail "No se encontró libchatpro-diffusion.so"
cp -f "$DIFFUSION_SO" "$JNI_DIR/libchatpro-diffusion.so"
log "Motor nativo preparado:"
ls -lh "$JNI_DIR/libchatpro-diffusion.so"
