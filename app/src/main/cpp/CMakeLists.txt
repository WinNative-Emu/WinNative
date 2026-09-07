cmake_minimum_required(VERSION 3.22.1)

project(Winlator)

include(FetchContent)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

# Zstandard is used by winlator/native_content_io.cpp. Keep this dependency in
# the parent build instead of relying on the Steam client subproject to create it.
FetchContent_Declare(
    zstd
    GIT_REPOSITORY https://github.com/facebook/zstd.git
    GIT_TAG        v1.5.6
    GIT_SHALLOW    TRUE
    SOURCE_SUBDIR  build/cmake
)
set(ZSTD_BUILD_STATIC    ON  CACHE BOOL "" FORCE)
set(ZSTD_BUILD_SHARED    OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_PROGRAMS  OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(ZSTD_LEGACY_SUPPORT  OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(zstd)

# liblzma (xz): fast XZ decoder for .txz/.xz layer extraction.
FetchContent_Declare(
    xz
    GIT_REPOSITORY https://github.com/tukaani-project/xz.git
    GIT_TAG        v5.4.6
    GIT_SHALLOW    TRUE
)
FetchContent_GetProperties(xz)
if(NOT xz_POPULATED)
    FetchContent_Populate(xz)
    set(_winlator_saved_bsl "${BUILD_SHARED_LIBS}")
    set(BUILD_SHARED_LIBS OFF)
    add_subdirectory(${xz_SOURCE_DIR} ${xz_BINARY_DIR} EXCLUDE_FROM_ALL)
    set(BUILD_SHARED_LIBS "${_winlator_saved_bsl}")
endif()

add_subdirectory(thirdparty/dxbc EXCLUDE_FROM_ALL)
add_subdirectory(patchelf)
add_subdirectory(adrenotools)
add_subdirectory(wn-steam-client)
add_subdirectory(wn-steam-bootstrap)
add_subdirectory(wn-libsteamclient)

find_package(curl REQUIRED CONFIG)

# ----------------------------------------------------------------------------
# SPIR-V shader compilation
# Each .glsl is compiled by glslc (shipped with the NDK) into a .spv binary,
# then converted to a C uint32_t array via bin2c.cmake. Headers are emitted
# under ${CMAKE_CURRENT_BINARY_DIR}/shaders/*.spv.h and included from vk_renderer.c.
# ----------------------------------------------------------------------------

# Locate glslc shipped with the NDK. ANDROID_NDK is provided by the Android Gradle plugin.
if(NOT DEFINED ANDROID_NDK)
    message(FATAL_ERROR "ANDROID_NDK not defined; this project must be built via the Android Gradle plugin")
endif()

if(CMAKE_HOST_WIN32)
    set(GLSLC_HOST_TAG "windows-x86_64")
    set(GLSLC_EXE_NAME "glslc.exe")
elseif(CMAKE_HOST_APPLE)
    set(GLSLC_HOST_TAG "darwin-x86_64")
    set(GLSLC_EXE_NAME "glslc")
else()
    set(GLSLC_HOST_TAG "linux-x86_64")
    set(GLSLC_EXE_NAME "glslc")
endif()

set(GLSLC "${ANDROID_NDK}/shader-tools/${GLSLC_HOST_TAG}/${GLSLC_EXE_NAME}")
if(NOT EXISTS "${GLSLC}")
    message(FATAL_ERROR "glslc not found at ${GLSLC}; check NDK installation")
endif()

set(SHADER_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/winlator/vk/shaders")
set(SHADER_OUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/shaders")
file(MAKE_DIRECTORY "${SHADER_OUT_DIR}")

set(BIN2C_SCRIPT "${CMAKE_CURRENT_SOURCE_DIR}/winlator/vk/bin2c.cmake")

# (input filename without extension, var name, stage)
set(SHADER_LIST
    "window:vert:window_vert"
    "window:frag:window_frag"
    "cursor:frag:cursor_frag"
    "quad:vert:quad_vert"
    "blit:frag:blit_frag"
    "effect_crt:frag:effect_crt_frag"
    "effect_vivid:frag:effect_vivid_frag"
    "effect_hdr:frag:effect_hdr_frag"
    "effect_natural:frag:effect_natural_frag"
    "effect_toon:frag:effect_toon_frag"
    "effect_ntsc:frag:effect_ntsc_frag"
    "effect_ntsc2:frag:effect_ntsc2_frag"
    "effect_coloradj:frag:effect_coloradj_frag"
    "effect_colorgrade:frag:effect_colorgrade_frag"
    "effect_sharpen:frag:effect_sharpen_frag"
    "effect_scanlines:frag:effect_scanlines_frag"
    "effect_colorblind:frag:effect_colorblind_frag"
    "effect_pixelate:frag:effect_pixelate_frag"
    "sgsr1:frag:sgsr1_frag"
)

set(SHADER_HEADERS "")
foreach(entry ${SHADER_LIST})
    string(REPLACE ":" ";" parts ${entry})
    list(GET parts 0 base)
    list(GET parts 1 stage)
    list(GET parts 2 var)
    set(input "${SHADER_SRC_DIR}/${base}.${stage}")
    set(spv   "${SHADER_OUT_DIR}/${var}.spv")
    set(hdr   "${SHADER_OUT_DIR}/${var}.spv.h")

    add_custom_command(
        OUTPUT  "${hdr}"
        COMMAND "${GLSLC}" --target-env=vulkan1.1 -O "${input}" -o "${spv}"
        COMMAND "${CMAKE_COMMAND}"
            -DINPUT_FILE=${spv}
            -DOUTPUT_FILE=${hdr}
            -DVAR_NAME=${var}
            -P "${BIN2C_SCRIPT}"
        DEPENDS "${input}" "${BIN2C_SCRIPT}"
        COMMENT "Compiling shader ${base}.${stage} -> ${var}.spv.h"
        VERBATIM
    )
    list(APPEND SHADER_HEADERS "${hdr}")
endforeach()

add_custom_target(winlator_shaders DEPENDS ${SHADER_HEADERS})

# ----------------------------------------------------------------------------
# Winlator native library (X-server, AHB, Vulkan compositor, helpers)
# ----------------------------------------------------------------------------

add_library(winlator SHARED
        winlator/drawable.c
        winlator/native_content_io.cpp
        winlator/gpu_image.c
        winlator/ring_fence.c
        winlator/sync_fence.c
        winlator/sysvshared_memory.c
        winlator/xconnector_epoll.c
        winlator/process_lifecycle.c
        winlator/vulkan.c
        winlator/vk/vk_dispatch.c
        winlator/vk/vk_image.c
        winlator/vk/vk_renderer.c
        winlator/vk/lsfg/lsfg_dll.c
        winlator/vk/lsfg/lsfg_dxbc.cpp
        winlator/vk/lsfg/lsfg_common.cpp
        winlator/vk/lsfg/lsfg_shaders.cpp
        winlator/vk/lsfg/lsfg_mipmaps.cpp
        winlator/vk/lsfg/lsfg_generate.cpp
        winlator/vk/lsfg/lsfg_alpha.cpp
        winlator/vk/lsfg/lsfg_beta.cpp
        winlator/vk/lsfg/lsfg_gamma.cpp
        winlator/vk/lsfg/lsfg_delta.cpp
        winlator/vk/lsfg/lsfg_chain.cpp
        winlator/vk/lsfg/lsfg_pacer.cpp
        winlator/vk/lsfg/vkr_lsfg.cpp
        winlator/vk/lsfg/lsfg_probe.c
        winlator/vk/lsfg/lsfg_jni.c
)

add_dependencies(winlator winlator_shaders)

target_compile_options(winlator PRIVATE -Wall -Wextra)
target_compile_definitions(winlator PRIVATE
        VK_USE_PLATFORM_ANDROID_KHR
)
target_include_directories(winlator PRIVATE
        ${xz_SOURCE_DIR}/src/liblzma/api
        winlator/vk
        ${CMAKE_CURRENT_BINARY_DIR}
)

target_compile_features(winlator PRIVATE cxx_std_17)

target_link_libraries(winlator
        log
        android
        jnigraphics
        vulkan
        adrenotools
        dxbc
        libzstd_static
        liblzma
        curl::curl
)

# Fake evdev input shim used by the pb_controller_fix controller path.
add_library(netshim SHARED
        winlator/netshim.c
)
target_compile_options(netshim PRIVATE -Wall -Wextra)
target_link_libraries(netshim log)

add_library(fakeinput SHARED
        winlator/fakeinput.cpp
)
target_compile_options(fakeinput PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_libraries(fakeinput log dl)

add_library(waudio SHARED
        wnaudiohook/wn_aaudio_shim.c
)
target_compile_options(waudio PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_libraries(waudio log dl)
