cmake_minimum_required(VERSION 3.22.1)

project(PRoot C ASM)

# termux/proot 4dba3afb with the patches in patches/ applied (see PATCHES.md), built the way its
# GNUmakefile builds it. The loader is not embedded: it ships beside proot as libproot-loader.so
# and LinuxRuntime names it in PROOT_LOADER.
set(PROOT_DEFINES _FILE_OFFSET_BITS=64 _GNU_SOURCE ARG_MAX=131072 PROOT_UNBUNDLE_LOADER="/nonexistent")
set(PROOT_CFLAGS -O2 -Wall -Wextra -Wno-unused-parameter -Wno-sign-compare -Wno-missing-field-initializers
                 -Wno-unused-function -Wno-error=implicit-function-declaration)

add_library(talloc STATIC talloc/talloc.c)
target_compile_options(talloc PRIVATE -O2)

# The loader is a freestanding static program proot runs in place of every guest program, linked
# at LOADER_ADDRESS (arch.h); it is named like a library so the installer places it beside proot.
add_executable(libproot-loader.so
               src/loader/loader.c
               src/loader/assembly.S)
target_include_directories(libproot-loader.so PRIVATE src)
target_compile_definitions(libproot-loader.so PRIVATE ${PROOT_DEFINES})
target_compile_options(libproot-loader.so PRIVATE ${PROOT_CFLAGS} -fPIC -ffreestanding -fno-stack-protector)
target_link_options(libproot-loader.so PRIVATE -static -nostdlib -no-pie
                    -Wl,--build-id=none,-Ttext=0x2000000000,--rosegment,-z,noexecstack)

# tracee/mem.c finds the loader's pokedata_workaround stub by its offset from _start.
set(LOADER_INFO ${CMAKE_CURRENT_BINARY_DIR}/loader-info.c)
find_program(PROOT_READELF llvm-readelf HINTS "${ANDROID_TOOLCHAIN_ROOT}/bin" REQUIRED)
add_custom_command(OUTPUT ${LOADER_INFO}
                   COMMAND ${CMAKE_COMMAND} -DREADELF=${PROOT_READELF}
                           -DLOADER=$<TARGET_FILE:libproot-loader.so> -DOUTPUT=${LOADER_INFO}
                           -P ${CMAKE_CURRENT_SOURCE_DIR}/loader-info.cmake
                   DEPENDS libproot-loader.so ${CMAKE_CURRENT_SOURCE_DIR}/loader-info.cmake
                   VERBATIM)

add_executable(libproot.so
               src/cli/cli.c
               src/cli/proot.c
               src/cli/note.c
               src/execve/enter.c
               src/execve/exit.c
               src/execve/shebang.c
               src/execve/elf.c
               src/execve/ldso.c
               src/execve/auxv.c
               src/execve/aoxp.c
               src/path/binding.c
               src/path/glue.c
               src/path/canon.c
               src/path/f2fs-bug.c
               src/path/path.c
               src/path/proc.c
               src/path/temp.c
               src/syscall/seccomp.c
               src/syscall/syscall.c
               src/syscall/chain.c
               src/syscall/enter.c
               src/syscall/exit.c
               src/syscall/sysnum.c
               src/syscall/socket.c
               src/syscall/heap.c
               src/syscall/rlimit.c
               src/tracee/tracee.c
               src/tracee/mem.c
               src/tracee/reg.c
               src/tracee/event.c
               src/tracee/seccomp.c
               src/tracee/statx.c
               src/ptrace/ptrace.c
               src/ptrace/user.c
               src/ptrace/wait.c
               src/extension/extension.c
               src/extension/ashmem_memfd/ashmem_memfd.c
               src/extension/kompat/kompat.c
               src/extension/fake_id0/chown.c
               src/extension/fake_id0/chroot.c
               src/extension/fake_id0/getsockopt.c
               src/extension/fake_id0/sendmsg.c
               src/extension/fake_id0/socket.c
               src/extension/fake_id0/open.c
               src/extension/fake_id0/unlink.c
               src/extension/fake_id0/rename.c
               src/extension/fake_id0/chmod.c
               src/extension/fake_id0/utimensat.c
               src/extension/fake_id0/access.c
               src/extension/fake_id0/exec.c
               src/extension/fake_id0/link.c
               src/extension/fake_id0/symlink.c
               src/extension/fake_id0/mk.c
               src/extension/fake_id0/stat.c
               src/extension/fake_id0/helper_functions.c
               src/extension/fake_id0/fake_id0.c
               src/extension/hidden_files/hidden_files.c
               src/extension/mountinfo/mountinfo.c
               src/extension/port_switch/port_switch.c
               src/extension/sysvipc/sysvipc.c
               src/extension/sysvipc/sysvipc_msg.c
               src/extension/sysvipc/sysvipc_sem.c
               src/extension/sysvipc/sysvipc_shm.c
               src/extension/link2symlink/link2symlink.c
               src/extension/fix_symlink_size/fix_symlink_size.c
               ${LOADER_INFO})
target_include_directories(libproot.so PRIVATE src talloc)
target_compile_definitions(libproot.so PRIVATE ${PROOT_DEFINES})
target_compile_options(libproot.so PRIVATE ${PROOT_CFLAGS})
target_link_libraries(libproot.so PRIVATE talloc)
target_link_options(libproot.so PRIVATE -Wl,-z,noexecstack)
