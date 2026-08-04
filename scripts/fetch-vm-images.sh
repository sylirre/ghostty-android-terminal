#!/bin/bash
# Fetch the two images a guest machine boots from, as app assets.
#
# The VM session type runs a whole aarch64 machine under arm64emu, which needs
# firmware and something to boot:
#
#   vm_firmware.fd        EDK2 ArmVirtQemu — the guest's UEFI. Taken from the
#                         host distribution's qemu-efi-aarch64 package if it is
#                         installed, else downloaded from Debian.
#   vm_alpine-<ver>.iso   the Alpine Linux aarch64 "virt" ISO, verified against
#                         the release checksum. Attached read-only and booted
#                         through its own GRUB, unmodified — which is why the
#                         guest console stays on ttyAMA0 (see VmMachine).
#
# Both land in VmImages/ at the repo root, which app/build.gradle bundles as
# assets when present. Never committed: the ISO alone is ~90 MB, and a build
# without them simply has no VM session type.
#
# Needs curl and sha256sum; no root.
set -e

ALPINE_BRANCH=latest-stable
MIRROR="https://dl-cdn.alpinelinux.org/alpine/${ALPINE_BRANCH}/releases/aarch64"
# Debian's package, used only when the host has no local copy.
FIRMWARE_URL="https://deb.debian.org/debian/pool/main/e/edk2/qemu-efi-aarch64_2024.11-6_all.deb"

VM_STORAGE=$(dirname "$(realpath "$0")")/../VmImages
mkdir -p "$VM_STORAGE"

WORKDIR=$(mktemp -d)
trap 'rm -rf "$WORKDIR"' EXIT

# --- firmware ---------------------------------------------------------------

FW_OUT="${VM_STORAGE}/vm_firmware.fd"
if [ -s "$FW_OUT" ]; then
	echo "Firmware already present: ${FW_OUT}"
else
	LOCAL_FW=""
	for candidate in \
		/usr/share/qemu-efi-aarch64/QEMU_EFI.fd \
		/usr/share/AAVMF/AAVMF_CODE.no-secboot.fd \
		/usr/share/edk2/aarch64/QEMU_EFI.fd
	do
		[ -r "$candidate" ] && { LOCAL_FW="$candidate"; break; }
	done

	if [ -n "$LOCAL_FW" ]; then
		echo "Using host firmware ${LOCAL_FW}..."
		cp "$LOCAL_FW" "${FW_OUT}.tmp"
	else
		echo "Fetching firmware from Debian..."
		curl -fL -o "${WORKDIR}/edk2.deb" "$FIRMWARE_URL"
		# ar/tar are enough to pull one file out of a .deb.
		(cd "$WORKDIR" && ar x edk2.deb && tar xf data.tar.* \
			./usr/share/qemu-efi-aarch64/QEMU_EFI.fd)
		cp "${WORKDIR}/usr/share/qemu-efi-aarch64/QEMU_EFI.fd" "${FW_OUT}.tmp"
	fi
	mv "${FW_OUT}.tmp" "$FW_OUT"
	echo "Firmware: ${FW_OUT}"
fi

# --- guest image ------------------------------------------------------------

# Discover the exact ISO name (it carries the version). The "virt" flavour is
# the one built for virtual machines: no firmware blobs, virtio drivers in the
# initramfs, and it is by far the smallest bootable Alpine.
FILE=$(curl -fsSL "${MIRROR}/latest-releases.yaml" \
	| grep -o 'alpine-virt-[0-9][0-9.]*-aarch64\.iso' | head -n1)
if [ -z "$FILE" ]; then
	echo "error: no alpine-virt entry found in ${MIRROR}/latest-releases.yaml" >&2
	exit 1
fi
VERSION=${FILE#alpine-virt-}
VERSION=${VERSION%-aarch64.iso}

OUT="${VM_STORAGE}/vm_alpine-${VERSION}.iso"
if [ -s "$OUT" ]; then
	echo "Guest image already present: ${OUT}"
else
	echo "Fetching ${FILE} (Alpine ${VERSION})..."
	curl -fL -o "${WORKDIR}/${FILE}" "${MIRROR}/${FILE}"
	curl -fsSL -o "${WORKDIR}/${FILE}.sha256" "${MIRROR}/${FILE}.sha256"
	(cd "$WORKDIR" && sha256sum -c "${FILE}.sha256")
	# Only one ISO may be bundled: VmImages.bundledImageAsset takes the first
	# it finds, so an older one left behind would make the choice arbitrary.
	rm -f "${VM_STORAGE}"/vm_*.iso
	mv "${WORKDIR}/${FILE}" "$OUT"
	echo "Guest image: ${OUT}"
fi

echo "Done. Rebuild the APK to bundle them."
