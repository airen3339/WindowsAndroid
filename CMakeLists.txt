# WindowsAndroid engine build for Linux / Android (the macOS runner uses the Makefile).
#
#   winvm_engine   static library: ARM64->ARM64 JIT, 4 KB software MMU, virtual PC platform
#   winvm          headless runner (host/main_linux.c), runs from `adb shell`
#   hvsparse       sparse disk image packer
#   test_*         core engine unit tests (run them on the device / emulator)
#
# Standalone cross build for Android (what scripts/android_build_engine.sh does):
#   cmake -S . -B build/android -G Ninja \
#     -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
#     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-34
#
# The Android app (app/) pulls this file in with add_subdirectory and links winvm_engine.
cmake_minimum_required(VERSION 3.22)
project(WindowsAndroidEngine C ASM)

if(NOT CMAKE_SYSTEM_PROCESSOR MATCHES "^(aarch64|arm64|ARM64)$" AND NOT ANDROID_ABI STREQUAL "arm64-v8a")
  message(FATAL_ERROR "The engine translates ARM64 to ARM64: build it for an arm64 host (got ${CMAKE_SYSTEM_PROCESSOR}).")
endif()

option(WINVM_NET "Guest networking through libslirp (needs deps from scripts/build_android_deps.sh)" ON)
option(WINVM_TESTS "Build the core engine unit tests" ON)
option(WINVM_TOOLS "Build the headless runner and hvsparse" ON)
set(WINVM_DEPS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/android-deps/${ANDROID_ABI}" CACHE PATH "Prefix with libslirp + glib for Android")

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_EXTENSIONS ON)       # gnu11: POSIX + GNU extensions on glibc as well as bionic
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE RelWithDebInfo)
endif()

set(WINVM_ROOT ${CMAKE_CURRENT_SOURCE_DIR})
set(WINVM_INCLUDES ${WINVM_ROOT}/core ${WINVM_ROOT}/host ${WINVM_ROOT}/devices)
set(WINVM_COPTS -O2 -g -Wall -Wextra -Wno-unused-parameter -fno-strict-aliasing)
if(ANDROID)
  # The guest is told it has LSE atomics, LRCPC2, DotProd and FHM (core/sysreg.c ID_AA64ISAR*), and
  # the JIT runs those instructions natively, so the host is Armv8.4 at least. Building for it turns
  # the engine's __atomic operations into inline LSE instructions instead of outline-atomic calls.
  list(APPEND WINVM_COPTS -march=armv8.4-a)
endif()

file(GLOB CORE_SRC CONFIGURE_DEPENDS ${WINVM_ROOT}/core/*.c)
file(GLOB DEVICE_SRC CONFIGURE_DEPENDS ${WINVM_ROOT}/devices/*.c)
set(HOST_SRC
  ${WINVM_ROOT}/host/hv_os.c
  ${WINVM_ROOT}/host/jit_region.c
  ${WINVM_ROOT}/host/enter.S
  ${WINVM_ROOT}/host/disk.c
  ${WINVM_ROOT}/host/hvsparse.c
  ${WINVM_ROOT}/host/winvm.c
  ${WINVM_ROOT}/host/share.c
  ${WINVM_ROOT}/host/vm_commands.c)

if(ANDROID)
  list(APPEND HOST_SRC ${WINVM_ROOT}/host/audio_aaudio.c)
else()
  list(APPEND HOST_SRC ${WINVM_ROOT}/host/audio_null.c)
endif()

# Networking: libslirp (+ glib) when available, otherwise an unplugged NIC.
set(WINVM_HAVE_SLIRP OFF)
if(WINVM_NET)
  if(ANDROID)
    if(EXISTS ${WINVM_DEPS_DIR}/lib/libslirp.a AND EXISTS ${WINVM_DEPS_DIR}/lib/libglib-2.0.a)
      set(WINVM_HAVE_SLIRP ON)
      set(SLIRP_INCLUDE_DIRS ${WINVM_DEPS_DIR}/include/slirp ${WINVM_DEPS_DIR}/include/glib-2.0
                             ${WINVM_DEPS_DIR}/lib/glib-2.0/include)
      # libslirp needs only glib proper (and what glib itself pulls in), not gobject/gio.
      set(SLIRP_LIBRARIES ${WINVM_DEPS_DIR}/lib/libslirp.a ${WINVM_DEPS_DIR}/lib/libglib-2.0.a)
      foreach(lib libpcre2-8.a libintl.a)
        if(EXISTS ${WINVM_DEPS_DIR}/lib/${lib})
          list(APPEND SLIRP_LIBRARIES ${WINVM_DEPS_DIR}/lib/${lib})
        endif()
      endforeach()
    else()
      message(WARNING "libslirp for Android not found in ${WINVM_DEPS_DIR}: building without guest networking. "
                      "Run scripts/build_android_deps.sh to enable it.")
    endif()
  else()
    find_package(PkgConfig)
    if(PKG_CONFIG_FOUND)
      pkg_check_modules(SLIRP slirp)
      if(SLIRP_FOUND)
        set(WINVM_HAVE_SLIRP ON)
        set(SLIRP_LIBRARIES ${SLIRP_LINK_LIBRARIES})
      endif()
    endif()
  endif()
endif()
if(WINVM_HAVE_SLIRP)
  list(APPEND HOST_SRC ${WINVM_ROOT}/host/net_slirp.c)
else()
  list(APPEND HOST_SRC ${WINVM_ROOT}/host/net_null.c)
endif()
message(STATUS "WindowsAndroid engine: networking ${WINVM_HAVE_SLIRP}")

add_library(winvm_engine STATIC ${CORE_SRC} ${DEVICE_SRC} ${HOST_SRC})
target_include_directories(winvm_engine PUBLIC ${WINVM_INCLUDES})
target_compile_options(winvm_engine PRIVATE $<$<COMPILE_LANGUAGE:C>:${WINVM_COPTS}>)
# hv_tlb_refill runs with the guest's SIMD registers live: no SIMD in mmu.c.
set_source_files_properties(${WINVM_ROOT}/core/mmu.c PROPERTIES COMPILE_OPTIONS "-mgeneral-regs-only")
if(WINVM_HAVE_SLIRP)
  target_include_directories(winvm_engine PRIVATE ${SLIRP_INCLUDE_DIRS})
  target_link_libraries(winvm_engine PUBLIC ${SLIRP_LIBRARIES})
endif()
find_package(Threads REQUIRED)
target_link_libraries(winvm_engine PUBLIC Threads::Threads z m)
if(ANDROID)
  target_link_libraries(winvm_engine PUBLIC aaudio log)
endif()

if(WINVM_TOOLS)
  add_executable(winvm ${WINVM_ROOT}/host/main_linux.c)
  target_compile_options(winvm PRIVATE ${WINVM_COPTS})
  target_link_libraries(winvm PRIVATE winvm_engine)

  add_executable(hvsparse ${WINVM_ROOT}/tools/hvsparse_main.c ${WINVM_ROOT}/host/hvsparse.c)
  target_include_directories(hvsparse PRIVATE ${WINVM_INCLUDES})
endif()

if(WINVM_TESTS)
  set(T ${WINVM_ROOT}/tests)
  add_executable(test_classify ${T}/test_classify.c ${T}/classify_cases.S ${WINVM_ROOT}/core/classify.c)
  add_executable(test_operands ${T}/test_operands.c ${T}/operand_cases.S ${WINVM_ROOT}/core/operands.c)
  add_executable(test_jit_region ${T}/test_jit_region.c)
  add_executable(test_mmu ${T}/test_mmu.c)
  foreach(t jit mem exc bench)
    add_executable(test_${t} ${T}/test_${t}.c ${T}/${t}_programs.S ${T}/harness.S)
  endforeach()
  # The test programs exercise LSE, RCpc, DIT and PAuth encodings (Apple clang's default
  # CPU has them all). They run natively too, so the host must have them: every
  # Apple-silicon-hosted emulator and every Armv8.5+ phone core does.
  file(GLOB TEST_ASM CONFIGURE_DEPENDS ${T}/*.S)
  set_source_files_properties(${TEST_ASM} PROPERTIES COMPILE_OPTIONS "-march=armv8.5-a+crypto+rcpc+sha3")
  foreach(t classify operands jit_region mmu jit mem exc bench)
    target_include_directories(test_${t} PRIVATE ${WINVM_INCLUDES} ${T})
    target_compile_options(test_${t} PRIVATE $<$<COMPILE_LANGUAGE:C>:${WINVM_COPTS}>)
  endforeach()
  foreach(t jit_region mmu jit mem exc bench)
    target_link_libraries(test_${t} PRIVATE winvm_engine)
  endforeach()
endif()
