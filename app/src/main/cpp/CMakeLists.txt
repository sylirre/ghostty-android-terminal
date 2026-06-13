cmake_minimum_required(VERSION 3.22)
project(androidterm C ASM)

set(NATIVE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native)
set(GHOSTTY_VT_DIR ${NATIVE_DIR}/ghostty-vt)
set(PROOT_SRC ${NATIVE_DIR}/proot/src)
set(TALLOC_DIR ${NATIVE_DIR}/talloc)

add_library(ghostty-vt STATIC IMPORTED)
set_target_properties(ghostty-vt PROPERTIES
    IMPORTED_LOCATION ${GHOSTTY_VT_DIR}/prebuilt/${ANDROID_ABI}/libghostty-vt.a)

# --- talloc (PRoot's allocator; see native/talloc/replace.h) ---

add_library(talloc STATIC ${TALLOC_DIR}/talloc.c)
target_include_directories(talloc PUBLIC ${TALLOC_DIR})
target_compile_definitions(talloc PRIVATE _GNU_SOURCE)

# --- PRoot, linked into libterm.so and entered via proot_main() ---
# (see native/proot/ANDROID.md for the PROOT_JNI / unbundled-loader story)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(PROOT_LOADER_ADDRESS 0x2000000000)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(PROOT_LOADER_ADDRESS 0x600000000000)
else()
    message(FATAL_ERROR "No PRoot loader address for ABI ${ANDROID_ABI}")
endif()

add_library(proot STATIC
    ${PROOT_SRC}/cli/cli.c
    ${PROOT_SRC}/cli/proot.c
    ${PROOT_SRC}/cli/note.c
    ${PROOT_SRC}/execve/enter.c
    ${PROOT_SRC}/execve/exit.c
    ${PROOT_SRC}/execve/shebang.c
    ${PROOT_SRC}/execve/elf.c
    ${PROOT_SRC}/execve/ldso.c
    ${PROOT_SRC}/execve/auxv.c
    ${PROOT_SRC}/execve/aoxp.c
    ${PROOT_SRC}/path/binding.c
    ${PROOT_SRC}/path/glue.c
    ${PROOT_SRC}/path/canon.c
    ${PROOT_SRC}/path/f2fs-bug.c
    ${PROOT_SRC}/path/path.c
    ${PROOT_SRC}/path/proc.c
    ${PROOT_SRC}/path/temp.c
    ${PROOT_SRC}/syscall/seccomp.c
    ${PROOT_SRC}/syscall/syscall.c
    ${PROOT_SRC}/syscall/chain.c
    ${PROOT_SRC}/syscall/enter.c
    ${PROOT_SRC}/syscall/exit.c
    ${PROOT_SRC}/syscall/sysnum.c
    ${PROOT_SRC}/syscall/socket.c
    ${PROOT_SRC}/syscall/heap.c
    ${PROOT_SRC}/syscall/rlimit.c
    ${PROOT_SRC}/tracee/tracee.c
    ${PROOT_SRC}/tracee/mem.c
    ${PROOT_SRC}/tracee/reg.c
    ${PROOT_SRC}/tracee/event.c
    ${PROOT_SRC}/tracee/seccomp.c
    ${PROOT_SRC}/tracee/statx.c
    ${PROOT_SRC}/ptrace/ptrace.c
    ${PROOT_SRC}/ptrace/user.c
    ${PROOT_SRC}/ptrace/wait.c
    ${PROOT_SRC}/extension/extension.c
    ${PROOT_SRC}/extension/ashmem_memfd/ashmem_memfd.c
    ${PROOT_SRC}/extension/kompat/kompat.c
    ${PROOT_SRC}/extension/fake_id0/chown.c
    ${PROOT_SRC}/extension/fake_id0/chroot.c
    ${PROOT_SRC}/extension/fake_id0/getsockopt.c
    ${PROOT_SRC}/extension/fake_id0/sendmsg.c
    ${PROOT_SRC}/extension/fake_id0/socket.c
    ${PROOT_SRC}/extension/fake_id0/open.c
    ${PROOT_SRC}/extension/fake_id0/unlink.c
    ${PROOT_SRC}/extension/fake_id0/rename.c
    ${PROOT_SRC}/extension/fake_id0/chmod.c
    ${PROOT_SRC}/extension/fake_id0/utimensat.c
    ${PROOT_SRC}/extension/fake_id0/access.c
    ${PROOT_SRC}/extension/fake_id0/exec.c
    ${PROOT_SRC}/extension/fake_id0/link.c
    ${PROOT_SRC}/extension/fake_id0/symlink.c
    ${PROOT_SRC}/extension/fake_id0/mk.c
    ${PROOT_SRC}/extension/fake_id0/stat.c
    ${PROOT_SRC}/extension/fake_id0/helper_functions.c
    ${PROOT_SRC}/extension/fake_id0/fake_id0.c
    ${PROOT_SRC}/extension/hidden_files/hidden_files.c
    ${PROOT_SRC}/extension/mountinfo/mountinfo.c
    ${PROOT_SRC}/extension/port_switch/port_switch.c
    ${PROOT_SRC}/extension/sysvipc/sysvipc.c
    ${PROOT_SRC}/extension/sysvipc/sysvipc_msg.c
    ${PROOT_SRC}/extension/sysvipc/sysvipc_sem.c
    ${PROOT_SRC}/extension/sysvipc/sysvipc_shm.c
    ${PROOT_SRC}/extension/link2symlink/link2symlink.c
    ${PROOT_SRC}/extension/fix_symlink_size/fix_symlink_size.c)

target_include_directories(proot PRIVATE
    ${NATIVE_DIR}/proot/android   # pre-generated build.h
    ${PROOT_SRC})
target_compile_definitions(proot PRIVATE
    _FILE_OFFSET_BITS=64
    _GNU_SOURCE
    PROOT_JNI
    # Never used: PROOT_LOADER is always set at spawn time. Defining this
    # keeps the objcopy-embedded loader blob out of the build.
    PROOT_UNBUNDLE_LOADER="")
target_link_libraries(proot talloc)

# --- PRoot loader ---
# A freestanding static executable that the tracees execve() in place of
# guest programs; it then mmaps the real ELF. It must be a real file in an
# exec-allowed location, so it is named lib*.so to ride the APK's native
# library packaging into nativeLibraryDir.

add_executable(proot-loader
    ${PROOT_SRC}/loader/loader.c
    ${PROOT_SRC}/loader/assembly.S)
set_target_properties(proot-loader PROPERTIES
    OUTPUT_NAME libproot-loader.so
    RUNTIME_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY})
target_include_directories(proot-loader PRIVATE ${PROOT_SRC})
target_compile_definitions(proot-loader PRIVATE _FILE_OFFSET_BITS=64 _GNU_SOURCE)
target_compile_options(proot-loader PRIVATE -fPIC -ffreestanding)
# --image-base, not upstream's -Ttext: the NDK toolchain links with
# --no-rosegment, which folds the ELF headers (at the default low image
# base) and .text (at the loader address) into ONE load segment — lld then
# emits a file spanning the ~128GiB gap between them. --image-base instead
# places the whole image at the loader address: a ~2KiB file, and no
# loader mappings left at low addresses where non-PIE guest binaries load.
target_link_options(proot-loader PRIVATE
    -static -nostdlib
    -Wl,--build-id=none
    -Wl,--image-base=${PROOT_LOADER_ADDRESS}
    -Wl,-z,noexecstack)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # The arm64 PTRACE_POKEDATA workaround needs the offset of a loader
    # symbol, normally computed by GNUmakefile via readelf | awk.
    get_filename_component(TOOLCHAIN_BIN ${CMAKE_C_COMPILER} DIRECTORY)
    add_custom_command(
        OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/loader-info.c
        COMMAND sh ${CMAKE_CURRENT_SOURCE_DIR}/../../../../scripts/gen-loader-info.sh
            ${TOOLCHAIN_BIN}/llvm-readelf
            $<TARGET_FILE:proot-loader>
            ${CMAKE_CURRENT_BINARY_DIR}/loader-info.c
        DEPENDS proot-loader
            ${CMAKE_CURRENT_SOURCE_DIR}/../../../../scripts/gen-loader-info.sh)
    target_sources(proot PRIVATE ${CMAKE_CURRENT_BINARY_DIR}/loader-info.c)
endif()

# --- PNG decoder (vendored stb_image) for Kitty graphics ---
# Its own target so the third-party header compiles warnings-off, clear of
# libterm.so's -Wall -Werror.

add_library(pngdec STATIC png_decode.c)
target_include_directories(pngdec PRIVATE ${GHOSTTY_VT_DIR}/include)
target_compile_options(pngdec PRIVATE -w)

# --- libterm.so (JNI) ---

add_library(term SHARED
    pty_jni.c
    terminal_jni.c)

target_include_directories(term PRIVATE ${GHOSTTY_VT_DIR}/include)
target_compile_options(term PRIVATE -Wall -Werror)
target_link_libraries(term ghostty-vt proot pngdec log)
