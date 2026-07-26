#!/bin/bash
# Build Debian rootfs using mmdebstrap
set -e

ROOTFS_STORAGE=$(dirname "$(realpath "$0")")/../UserlandRootfs
mkdir -p "$ROOTFS_STORAGE"

mmdebstrap --mode=unshare --architectures="arm64" --variant=minbase \
	--components="main,contrib" --include="ca-certificates,locales" \
	"trixie" "${ROOTFS_STORAGE}/debian_trixie_aarch64_rootfs.tar.xz"
