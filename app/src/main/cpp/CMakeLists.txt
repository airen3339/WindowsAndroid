# libwinandroid.so: the engine (../../../../CMakeLists.txt) + the Android glue (JNI, GLES presenter).
cmake_minimum_required(VERSION 3.22)
project(WinAndroid C ASM)

set(WINVM_TESTS OFF CACHE BOOL "" FORCE)
set(WINVM_TOOLS OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../.. winvm-engine)

add_library(winandroid SHARED
  jni_bridge.c
  display_gles.c)
target_compile_options(winandroid PRIVATE -O2 -g -Wall -Wextra -Wno-unused-parameter -fvisibility=hidden -march=armv8.4-a)
target_link_libraries(winandroid PRIVATE winvm_engine android log EGL GLESv3)
# The engine exports nothing: only JNI_OnLoad and the Java_* entry points are public.
target_link_options(winandroid PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections)
