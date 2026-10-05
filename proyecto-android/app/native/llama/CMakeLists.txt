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
