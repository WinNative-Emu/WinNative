# ----------------------------------------------------------------------------
# wn-steam-client
# Rust native Steam protocol client for WinNative.
# ----------------------------------------------------------------------------

cmake_minimum_required(VERSION 3.22.1)

set(WN_STEAM_CLIENT_VERSION_MAJOR 0)
set(WN_STEAM_CLIENT_VERSION_MINOR 1)
set(WN_STEAM_CLIENT_VERSION_PATCH 0)
set(WN_STEAM_CLIENT_VERSION_STRING
    "${WN_STEAM_CLIENT_VERSION_MAJOR}.${WN_STEAM_CLIENT_VERSION_MINOR}.${WN_STEAM_CLIENT_VERSION_PATCH}")

find_program(CARGO_EXECUTABLE cargo REQUIRED)

if(NOT CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Rust wnsteam currently supports arm64-v8a; got ${CMAKE_ANDROID_ARCH_ABI}")
endif()
if(NOT DEFINED ANDROID_NDK)
    message(FATAL_ERROR "ANDROID_NDK not defined; Rust wnsteam must be built via Android Gradle/CMake")
endif()

set(WNSTEAM_RUST_TARGET "aarch64-linux-android")
if(DEFINED ANDROID_PLATFORM)
    string(REGEX REPLACE "^android-" "" WNSTEAM_ANDROID_API "${ANDROID_PLATFORM}")
elseif(DEFINED CMAKE_SYSTEM_VERSION)
    set(WNSTEAM_ANDROID_API "${CMAKE_SYSTEM_VERSION}")
else()
    set(WNSTEAM_ANDROID_API "26")
endif()

if(CMAKE_HOST_WIN32)
    set(WNSTEAM_NDK_HOST_TAG "windows-x86_64")
    set(WNSTEAM_LINKER_SUFFIX ".cmd")
    set(WNSTEAM_AR_SUFFIX ".exe")
elseif(CMAKE_HOST_APPLE)
    set(WNSTEAM_NDK_HOST_TAG "darwin-x86_64")
    set(WNSTEAM_LINKER_SUFFIX "")
    set(WNSTEAM_AR_SUFFIX "")
else()
    set(WNSTEAM_NDK_HOST_TAG "linux-x86_64")
    set(WNSTEAM_LINKER_SUFFIX "")
    set(WNSTEAM_AR_SUFFIX "")
endif()

set(WNSTEAM_RUST_DIR "${CMAKE_CURRENT_SOURCE_DIR}/rust")
file(GLOB_RECURSE WNSTEAM_RUST_SOURCES CONFIGURE_DEPENDS
    "${WNSTEAM_RUST_DIR}/src/*.rs")
set(WNSTEAM_RUST_BUILD_INPUTS
    "${WNSTEAM_RUST_DIR}/Cargo.toml"
    "${WNSTEAM_RUST_DIR}/Cargo.lock"
    ${WNSTEAM_RUST_SOURCES})
set(WNSTEAM_RUST_LIB
    "${WNSTEAM_RUST_DIR}/target/${WNSTEAM_RUST_TARGET}/release/libwnsteam.so")
set(WNSTEAM_RUST_OUT "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libwnsteam.so")
set(WNSTEAM_RUST_LINKER
    "${ANDROID_NDK}/toolchains/llvm/prebuilt/${WNSTEAM_NDK_HOST_TAG}/bin/${WNSTEAM_RUST_TARGET}${WNSTEAM_ANDROID_API}-clang${WNSTEAM_LINKER_SUFFIX}")

# Android 15+ ships 16 KB page-size kernels. Cargo drives the NDK linker itself
# and therefore does not inherit Gradle's CMake linker flags, so ask for 16 KB
# LOAD alignment explicitly here; otherwise libwnsteam.so would be the only ELF
# in the APK still built for 4 KB pages.
add_custom_command(
    OUTPUT "${WNSTEAM_RUST_OUT}"
    COMMAND "${CMAKE_COMMAND}" -E env
        "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=${WNSTEAM_RUST_LINKER}"
        "CC_aarch64_linux_android=${WNSTEAM_RUST_LINKER}"
        "AR_aarch64_linux_android=${ANDROID_NDK}/toolchains/llvm/prebuilt/${WNSTEAM_NDK_HOST_TAG}/bin/llvm-ar${WNSTEAM_AR_SUFFIX}"
        "${CARGO_EXECUTABLE}" rustc --release --target "${WNSTEAM_RUST_TARGET}" --lib --
        -Clink-arg=-Wl,-soname,libwnsteam.so
        -Clink-arg=-Wl,-z,max-page-size=16384
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different
        "${WNSTEAM_RUST_LIB}"
        "${WNSTEAM_RUST_OUT}"
    WORKING_DIRECTORY "${WNSTEAM_RUST_DIR}"
    DEPENDS ${WNSTEAM_RUST_BUILD_INPUTS}
    COMMENT "Building Rust wnsteam for ${WNSTEAM_RUST_TARGET}"
    VERBATIM
)

add_custom_target(wnsteam_rust_build DEPENDS "${WNSTEAM_RUST_OUT}")
add_library(wnsteam SHARED IMPORTED GLOBAL)
set_target_properties(wnsteam PROPERTIES
    IMPORTED_LOCATION "${WNSTEAM_RUST_OUT}"
    IMPORTED_SONAME "libwnsteam.so")
add_dependencies(wnsteam wnsteam_rust_build)
