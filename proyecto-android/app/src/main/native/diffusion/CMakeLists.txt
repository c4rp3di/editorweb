cmake_minimum_required(VERSION 3.22)
project("chatpro-diffusion" LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT DEFINED SD_SOURCE OR NOT EXISTS "${SD_SOURCE}/CMakeLists.txt")
    message(FATAL_ERROR "SD_SOURCE no apunta a un árbol stable-diffusion.cpp válido")
endif()

if(NOT DEFINED VULKAN_HEADERS_INCLUDE_DIR OR NOT EXISTS "${VULKAN_HEADERS_INCLUDE_DIR}/vulkan/vulkan.hpp")
    message(FATAL_ERROR "VULKAN_HEADERS_INCLUDE_DIR inválido")
endif()

include_directories(BEFORE SYSTEM "${VULKAN_HEADERS_INCLUDE_DIR}")

if(NOT DEFINED SPIRV_HEADERS_INCLUDE_DIR OR NOT EXISTS "${SPIRV_HEADERS_INCLUDE_DIR}/spirv/unified1/spirv.hpp")
    message(FATAL_ERROR "SPIRV_HEADERS_INCLUDE_DIR inválido")
endif()

include_directories(BEFORE SYSTEM "${SPIRV_HEADERS_INCLUDE_DIR}")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_GGML_LIB OFF CACHE BOOL "" FORCE)
set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(SD_WEBP OFF CACHE BOOL "" FORCE)
set(SD_WEBM OFF CACHE BOOL "" FORCE)
set(SD_VULKAN ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.6-a+fp16+dotprod+i8mm" CACHE STRING "" FORCE)

add_subdirectory("${SD_SOURCE}" "stable-diffusion-build" EXCLUDE_FROM_ALL)

add_library(chatpro-diffusion SHARED diffusion_jni.cpp)

target_include_directories(chatpro-diffusion PRIVATE "${SD_SOURCE}/include")

find_library(log-lib log)
find_library(android-lib android)
find_library(vulkan-lib vulkan)
find_library(z-lib z)

if(NOT vulkan-lib)
    message(FATAL_ERROR "No se encontró libvulkan para el target Android")
endif()

target_link_libraries(chatpro-diffusion
    stable-diffusion
    ${log-lib}
    ${android-lib}
    ${vulkan-lib}
    ${z-lib}
)
