/*
 * Replaces the build.h that PRoot's GNUmakefile auto-generates (we build
 * with CMake/NDK instead). Both probed features exist on every Android
 * version we support: process_vm_readv/writev and seccomp filters have
 * been in bionic/the kernel since well before API 29.
 */
#ifndef BUILD_H
#define BUILD_H

#define VERSION "5.1.107-terminal"

#define HAVE_PROCESS_VM
#define HAVE_SECCOMP_FILTER

#endif /* BUILD_H */
