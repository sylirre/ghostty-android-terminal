#!/bin/bash
# Package the official Alpine Linux aarch64 minirootfs as an app asset.
#
# Downloads the current minirootfs release from the pinned branch, verifies
# it against the CDN's .sha256 sidecar, and repackages it from tar.gz to
# tar.xz under the asset naming scheme the app discovers
# (<id>_<version>_aarch64_rootfs.tar.xz — see UserlandDistro.java). The
# image content is left untouched: DNS/hosts defaults are written by the
# in-app installer, and everything else (apk, BusyBox, CA bundle) ships in
# the minirootfs already. Only the aarch64 image is ever packaged —
# arm64chroot emulates an AArch64 guest on every host ABI.
#
# Needs curl, sha256sum and xz; no root.
set -e

ALPINE_BRANCH=latest-stable
MIRROR="https://dl-cdn.alpinelinux.org/alpine/${ALPINE_BRANCH}/releases/aarch64"

ROOTFS_STORAGE=$(dirname "$(realpath "$0")")/../UserlandRootfs
mkdir -p "$ROOTFS_STORAGE"

# Discover the exact minirootfs file name (it carries the version).
FILE=$(curl -fsSL "${MIRROR}/latest-releases.yaml" \
	| grep -o 'alpine-minirootfs-[0-9][0-9.]*-aarch64\.tar\.gz' | head -n1)
if [ -z "$FILE" ]; then
	echo "error: no minirootfs entry found in ${MIRROR}/latest-releases.yaml" >&2
	exit 1
fi
VERSION=${FILE#alpine-minirootfs-}
VERSION=${VERSION%-aarch64.tar.gz}

WORKDIR=$(mktemp -d)
trap 'rm -rf "$WORKDIR"' EXIT

echo "Fetching ${FILE} (Alpine ${VERSION})..."
curl -fL -o "${WORKDIR}/${FILE}" "${MIRROR}/${FILE}"
curl -fsSL -o "${WORKDIR}/${FILE}.sha256" "${MIRROR}/${FILE}.sha256"
(cd "$WORKDIR" && sha256sum -c "${FILE}.sha256")

OUT="${ROOTFS_STORAGE}/alpine_${VERSION}_aarch64_rootfs.tar.xz"
echo "Repacking to ${OUT}..."
zcat "${WORKDIR}/${FILE}" | xz -9 -T0 > "${OUT}.tmp"
mv "${OUT}.tmp" "$OUT"
echo "Done."
