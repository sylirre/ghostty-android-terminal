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
# The source list is derived from the submodule's own Makefile SRCS rather than
# duplicated here, so the two can't drift on a submodule bump. A file missing
# from this list is a link-time undefined-symbol error (e.g. the sys_ipc.c
# shm_* / sys_shm* symbols), not a compile error, so it slips past a local
# build that happens to skip the link. We read the Makefile, pull every
# `src/<file>.c` token out of its CORE/JIT/SRCS assignments (the only place
# such tokens appear), and rebase them onto ${A64_DIR}. CMAKE_CONFIGURE_DEPENDS
# re-runs configure whenever the Makefile changes so a bump is picked up
# without a manual clean.
#
# Its own target so the vendored third-party code compiles clear of
# libterm.so's -Wall -Werror.

set_property(DIRECTORY APPEND PROPERTY
    CMAKE_CONFIGURE_DEPENDS ${NATIVE_DIR}/arm64chroot/Makefile)
file(READ ${NATIVE_DIR}/arm64chroot/Makefile _a64_makefile)
string(REGEX MATCHALL "src/[A-Za-z0-9_/]+\\.c" _a64_srcs "${_a64_makefile}")
list(REMOVE_DUPLICATES _a64_srcs)
if(NOT _a64_srcs)
    message(FATAL_ERROR
        "arm64chroot: no sources parsed from native/arm64chroot/Makefile SRCS")
endif()
list(TRANSFORM _a64_srcs REPLACE "^src/" "${A64_DIR}/")

add_library(arm64chroot STATIC ${_a64_srcs})

target_include_directories(arm64chroot PRIVATE
    ${A64_DIR} ${A64_DIR}/core ${A64_DIR}/jit)
target_compile_definitions(arm64chroot PRIVATE
    _GNU_SOURCE _FILE_OFFSET_BITS=64 _TIME_BITS=64 ANDROID_JNI)
target_compile_options(arm64chroot PRIVATE -O2 -std=gnu11 -w)

# --- chroot-ng: the native AArch64 userland engine (seccomp/SIGSYS path
# translation + userland ELF loader), linked into libterm.so and entered via
# chroot_ng_main() (chroot_ng_embed.c) in the fork()ed PTY child. Unlike
# arm64chroot it runs guest code directly on the CPU, so it exists only on
# arm64-v8a; the x86_64 build carries no trace of it (HAVE_CHROOT_NG gates the
# JNI branch and Java hides the engine setting when the symbol is absent).
#
# Compiled exactly as its own Makefile builds it (freestanding: its SIGSYS
# handler runs with the guest's TPIDR_EL0, so it must never call Bionic) minus
# the standalone-only -static/-no-pie/linker-script — the NDK's default -fPIC
# replaces them, and ASLR already keeps libterm.so clear of the fixed load
# addresses the linker script existed to dodge. src/rt/start.S (the standalone
# _start) is excluded; the embed shim reconstructs its contract.
#
# rt.c defines mem/str helpers under their libc names so compiler-emitted
# calls resolve in a -nostdlib link. Inside libterm.so those definitions would
# win intra-.so binding for ghostty-vt and the JNI code (byte-at-a-time loops)
# and be exported, so rename them via the preprocessor — definitions, rt.h
# declarations, and every explicit chroot-ng call rename together; implicit
# compiler-emitted memcpy/memset calls still bind to Bionic's, which is safe
# (they are TLS-free) and faster.

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CNG_DIR ${NATIVE_DIR}/chroot-ng)
    file(GLOB_RECURSE _cng_srcs CONFIGURE_DEPENDS
        ${CNG_DIR}/src/*.c ${CNG_DIR}/src/*.S)
    list(FILTER _cng_srcs EXCLUDE REGEX "src/rt/start\\.S$")
    if(NOT _cng_srcs)
        message(FATAL_ERROR "chroot-ng: no sources under ${CNG_DIR}/src")
    endif()

    add_library(chroot-ng STATIC ${_cng_srcs})
    target_include_directories(chroot-ng PRIVATE ${CNG_DIR}/include)
    target_compile_definitions(chroot-ng PRIVATE
        memset=cng_embed_memset
        memcpy=cng_embed_memcpy
        memmove=cng_embed_memmove
        memcmp=cng_embed_memcmp
        strlen=cng_embed_strlen
        strcmp=cng_embed_strcmp
        strncmp=cng_embed_strncmp
        strchr=cng_embed_strchr
        strrchr=cng_embed_strrchr)
    target_compile_options(chroot-ng PRIVATE
        -O2 -std=gnu11 -ffreestanding -fno-builtin -fno-stack-protector
        -fno-asynchronous-unwind-tables -mno-outline-atomics -w)
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

# chroot-ng needs no whole-archive treatment: it has no weak cross-module
# hooks; everything is reached transitively from cng_main().
#
# --exclude-libs makes every chroot-ng symbol hidden in libterm.so. Besides
# not exporting them, this is what lets the asm link at all: tramp.S's
# trampoline template LDRs PC-relative literals named by global labels, and
# lld refuses a PC-relative relocation against a preemptible (visible) symbol
# in a shared object. Hidden ⇒ non-preemptible ⇒ resolved at link time.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_sources(term PRIVATE chroot_ng_embed.c)
    target_compile_definitions(term PRIVATE HAVE_CHROOT_NG)
    target_link_libraries(term chroot-ng)
    target_link_options(term PRIVATE -Wl,--exclude-libs,libchroot-ng.a)
endif()
