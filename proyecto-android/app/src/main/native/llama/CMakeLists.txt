cmake_minimum_required(VERSION 3.22)
project("chatpro-llama" LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT DEFINED LLAMA_SOURCE OR NOT EXISTS "${LLAMA_SOURCE}/CMakeLists.txt")
    message(FATAL_ERROR "LLAMA_SOURCE no apunta a un árbol llama.cpp válido")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
# v19.3: con GGML_NATIVE=OFF ggml compilaba para armv8-a básico (sin DOTPROD / I8MM / FP16: se veía en el log
# "NEON = 1 | ARM_FMA = 1 | REPACK = 1"). Este dispositivo (Cortex-X3/A715/A510) los soporta; sin ellos la CPU va mucho más lenta.
# Nota: el APK resultante exige un SoC armv8.6 con i8mm (Snapdragon 8 Gen 1+, Dimensity 9000+, Tensor G2+...).
set(GGML_CPU_ARM_ARCH "armv8.6-a+fp16+dotprod+i8mm" CACHE STRING "" FORCE)

add_subdirectory("${LLAMA_SOURCE}" "llama-build" EXCLUDE_FROM_ALL)

add_library(chatpro-llama SHARED llama_jni.cpp)

target_include_directories(chatpro-llama PRIVATE
    "${LLAMA_SOURCE}"
    "${LLAMA_SOURCE}/common"
    "${LLAMA_SOURCE}/include"
    "${LLAMA_SOURCE}/ggml/include"
    "${LLAMA_SOURCE}/ggml/src"
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(chatpro-llama
    llama
    llama-common
    ${log-lib}
    ${android-lib}
)
