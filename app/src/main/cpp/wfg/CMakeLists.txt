# WFG (WinNative/Winlator Frame Generator, MIT - see LICENSE here): Dense Inverse Search (DIS)
# optical flow and the frame interpolator built on it, as one static library. libwinlator (X11
# compositor, standalone presenter) and libwnwayland (Wayland compositor) both link it; each keeps
# its own VkDispatch, which the objects here resolve against at link time.
#
# Layout:
#   include/vkr_dis.h      public API
#   include/shaders/*.comp GLSL compute shaders, compiled to SPIR-V headers at build time
#   src/                   implementation
#
# Uses GLSLC and BIN2C_SCRIPT from the parent CMakeLists.

set(DIS_SHADER_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/include/shaders")
set(DIS_SHADER_OUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/shaders")
file(MAKE_DIRECTORY "${DIS_SHADER_OUT_DIR}")

set(DIS_SHADERS
    dis_luma_r16
    dis_luma_r32
    dis_gradient
    dis_inverse_search
    dis_propagate
    dis_densify
    dis_interpolate
    dis_hist
    dis_side
    dis_flow_pack
    dis_splat
    dis_fill
    dis_vr_prep
    dis_vr_d1
    dis_vr_d2
    dis_vr_w
    dis_vr_coef
    dis_vr_sor
    dis_vr_add
    dis_fb_poly
    dis_fb_hsum
    dis_fb_vsolve
)

set(DIS_SHADER_HEADERS "")
foreach(base ${DIS_SHADERS})
    set(var   "${base}_comp")
    set(input "${DIS_SHADER_SRC_DIR}/${base}.comp")
    set(spv   "${DIS_SHADER_OUT_DIR}/${var}.spv")
    set(hdr   "${DIS_SHADER_OUT_DIR}/${var}.spv.h")
    add_custom_command(
        OUTPUT  "${hdr}"
        COMMAND "${GLSLC}" --target-env=vulkan1.1 -O "${input}" -o "${spv}"
        COMMAND "${CMAKE_COMMAND}"
            -DINPUT_FILE=${spv}
            -DOUTPUT_FILE=${hdr}
            -DVAR_NAME=${var}
            -P "${BIN2C_SCRIPT}"
        DEPENDS "${input}" "${BIN2C_SCRIPT}"
        COMMENT "Compiling WFG shader ${base}.comp -> ${var}.spv.h"
        VERBATIM
    )
    list(APPEND DIS_SHADER_HEADERS "${hdr}")
endforeach()

add_custom_target(wfg_shaders DEPENDS ${DIS_SHADER_HEADERS})

add_library(wfg STATIC
        src/vkr_dis.c
)
add_dependencies(wfg wfg_shaders)

set_target_properties(wfg PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_options(wfg PRIVATE -Wall -Wextra -fvisibility=hidden)
target_compile_definitions(wfg PUBLIC VK_USE_PLATFORM_ANDROID_KHR)
target_include_directories(wfg
        PUBLIC
            ${CMAKE_CURRENT_SOURCE_DIR}/include
            # vk_dispatch.h: the public header takes its Vulkan types from there.
            ${CMAKE_CURRENT_SOURCE_DIR}/../winlator/vk
        PRIVATE
            ${CMAKE_CURRENT_BINARY_DIR}
)
target_link_libraries(wfg PRIVATE log)
