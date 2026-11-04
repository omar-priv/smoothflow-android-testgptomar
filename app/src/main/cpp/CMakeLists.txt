cmake_minimum_required(VERSION 3.22.1)
project(smoothflow-native LANGUAGES C CXX)

if(NOT DEFINED RIFE_SOURCE_DIR OR NOT EXISTS "${RIFE_SOURCE_DIR}/rife.cpp")
    message(FATAL_ERROR "RIFE_SOURCE_DIR is missing. CI must fetch third_party/rife-ncnn-vulkan first.")
endif()

set(NCNN_VULKAN ON CACHE BOOL "" FORCE)
set(NCNN_VULKAN_ONLINE_SPIRV ON CACHE BOOL "" FORCE)
set(NCNN_OPENMP OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(NCNN_INSTALL_SDK OFF CACHE BOOL "" FORCE)

add_subdirectory("${RIFE_SOURCE_DIR}" "${CMAKE_BINARY_DIR}/rife")

add_library(rifejni SHARED rife_jni.cpp)
target_include_directories(rifejni PRIVATE "${RIFE_SOURCE_DIR}")
target_compile_options(rifejni PRIVATE -O3 -fno-exceptions)
target_link_libraries(rifejni PRIVATE rife-core log)
