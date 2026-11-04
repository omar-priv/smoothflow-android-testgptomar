#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_rife_cmake.py path/to/rife/src")

cmake = Path(sys.argv[1]) / "CMakeLists.txt"
text = cmake.read_text(encoding="utf-8")

# RIFE's top-level CMake adds -fopenmp globally when OpenMP is detected.
# The Android NDK toolchain selected OpenMP compile flags but did not link
# an OpenMP runtime into the JNI shared library. NCNN is already configured
# with NCNN_OPENMP=OFF by the app CMake project, so disable this global probe.
openmp_probe = "find_package(OpenMP)"
if openmp_probe not in text:
    raise SystemExit("Could not find the expected OpenMP probe; refusing unsafe patch")
text = text.replace(
    openmp_probe,
    "set(OPENMP_FOUND FALSE)"
)


old_target = """add_executable(rife-ncnn-vulkan
    main.cpp
    rife.cpp
    warp.cpp
)"""
new_target = """add_library(rife-core STATIC
    rife.cpp
    warp.cpp
)
target_include_directories(rife-core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})"""

if old_target not in text:
    if "add_library(rife-core STATIC" in text:
        raise SystemExit("RIFE CMake already patched")
    raise SystemExit("Could not find the expected RIFE executable target; refusing unsafe patch")

text = text.replace(old_target, new_target)
text = text.replace(
    "add_dependencies(rife-ncnn-vulkan generate-spirv)",
    "add_dependencies(rife-core generate-spirv)"
)
text = text.replace(
    "target_link_libraries(rife-ncnn-vulkan ${RIFE_LINK_LIBRARIES})",
    "target_link_libraries(rife-core PUBLIC ${RIFE_LINK_LIBRARIES})"
)
if "add_executable(rife-ncnn-vulkan" in text or "target_link_libraries(rife-ncnn-vulkan" in text:
    raise SystemExit("Unexpected remaining executable target reference")
cmake.write_text(text, encoding="utf-8")
print(f"Patched {cmake}")
