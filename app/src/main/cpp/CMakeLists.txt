cmake_minimum_required(VERSION 3.22)
project(terminal C ASM)

set(NATIVE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native)
set(GHOSTTY_VT_DIR ${NATIVE_DIR}/ghostty-vt)
set(A64_DIR ${NATIVE_DIR}/arm64chroot/src)

add_library(ghostty-vt STATIC IMPORTED)
set_target_properties(ghostty-vt PROPERTIES
    IMPORTED_LOCATION ${GHOSTTY_VT_DIR}/prebuilt/${ANDROID_ABI}/libghostty-vt.a)

# --- arm64chroot: the AArch64 user-space emulator, linked into libterm.so and
# entered via arm64chroot_main() in the fork()ed PTY child.
#
# Source list mirrors the Makefile's SRCS (native/arm64chroot/Makefile). Its
# own target so the vendored third-party code compiles clear of libterm.so's
# -Wall -Werror.

add_library(arm64chroot STATIC
    ${A64_DIR}/core/cpu.c
    ${A64_DIR}/core/decode.c
    ${A64_DIR}/core/exec_fpsimd.c
    ${A64_DIR}/core/sysreg.c
    ${A64_DIR}/jit/jit.c
    ${A64_DIR}/jit/frontend.c
    ${A64_DIR}/jit/backend_x86_64.c
    ${A64_DIR}/jit/backend_a64.c
    ${A64_DIR}/mem.c
    ${A64_DIR}/exception.c
    ${A64_DIR}/loop.c
    ${A64_DIR}/predecode.c
    ${A64_DIR}/elf.c
    ${A64_DIR}/path.c
    ${A64_DIR}/signal.c
    ${A64_DIR}/syscall.c
    ${A64_DIR}/strace.c
    ${A64_DIR}/sys_file.c
    ${A64_DIR}/sys_mm.c
    ${A64_DIR}/sys_proc.c
    ${A64_DIR}/sys_sig.c
    ${A64_DIR}/sys_time.c
    ${A64_DIR}/sys_misc.c
    ${A64_DIR}/sys_net.c
    ${A64_DIR}/sys_netlink.c
    ${A64_DIR}/sys_procfs.c
    ${A64_DIR}/sys_ptrace.c
    ${A64_DIR}/proctab.c
    ${A64_DIR}/ptracetab.c
    ${A64_DIR}/main.c)

target_include_directories(arm64chroot PRIVATE
    ${A64_DIR} ${A64_DIR}/core ${A64_DIR}/jit)
target_compile_definitions(arm64chroot PRIVATE
    _GNU_SOURCE _FILE_OFFSET_BITS=64 _TIME_BITS=64 ANDROID_JNI)
target_compile_options(arm64chroot PRIVATE -O2 -std=gnu11 -w)

# --- PNG decoder (vendored stb_image) for Kitty graphics ---
# Its own target so the third-party header compiles warnings-off, clear of
# libterm.so's -Wall -Werror.

add_library(pngdec STATIC png_decode.c)
target_include_directories(pngdec PRIVATE ${GHOSTTY_VT_DIR}/include)
target_compile_options(pngdec PRIVATE -w)

# --- libterm.so (JNI) ---

add_library(term SHARED
    pty_jni.c
    terminal_jni.c
    kitty_unicode.c)

target_include_directories(term PRIVATE ${GHOSTTY_VT_DIR}/include)
target_compile_options(term PRIVATE -Wall -Werror)
# arm64chroot's cross-module hooks (exec_fpsimd, sysreg_exec, sysreg_init,
# smccc_conduit) are declared weak in the M1 core so the emulator's own modular
# build links standalone. Pulled from a plain STATIC archive on demand, the
# objects that define those hooks are only ever weakly referenced, so the linker
# drops them and the weak refs resolve to NULL — turning every guest FP/SIMD and
# MRS/MSR into an undefined-instruction SIGILL (guest fatal signal 4). Force the
# whole archive in so each weak ref binds to its definition.
# atomic: wide (16-byte) host atomics the emulator uses for guest LSE/exclusives.
target_link_libraries(term
    ghostty-vt
    -Wl,--whole-archive arm64chroot -Wl,--no-whole-archive
    pngdec log m atomic)
