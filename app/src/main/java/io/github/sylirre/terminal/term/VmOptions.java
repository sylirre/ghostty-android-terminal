/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

import java.io.File;

/**
 * What to boot, and how, when starting the guest machine.
 *
 * Grouped into one object for the same reason {@link UserlandOptions} is: the
 * settings feeding a machine only grow, and threading them through
 * {@link VmMachine#start} one scalar at a time would not scale.
 */
public final class VmOptions {

    /** Guest terminals beyond {@code ttyAMA0}, i.e. {@code hvc0..hvc(N-1)}. */
    public static final int DEFAULT_HVC = 4;
    /** Guest RAM. Small enough for a mid-range phone, ample for a console Alpine. */
    public static final int DEFAULT_MEMORY_MB = 512;
    /** The emulator's ceiling on virtio-console devices. */
    public static final int MAX_HVC = 8;

    /** EDK2 firmware image (ArmVirtQemu), loaded into the guest's NOR flash. */
    public final File firmware;
    /** Bootable guest image, attached as a read-only virtio-blk disk. */
    public final File image;
    /** Guest RAM in MiB. The device tree is sized to match by the emulator. */
    public final int memoryMb;
    /**
     * Extra guest terminals. Tab 0 is always {@code ttyAMA0} — the console the
     * stock Alpine ISO already respawns a getty on — and these are the
     * virtio-consoles that back every further tab.
     */
    public final int hvcCount;
    /** Translate guest code to native instead of interpreting it. */
    public final boolean jit;

    public VmOptions(File firmware, File image, int memoryMb, int hvcCount,
            boolean jit) {
        this.firmware = firmware;
        this.image = image;
        this.memoryMb = memoryMb > 0 ? memoryMb : DEFAULT_MEMORY_MB;
        this.hvcCount = Math.max(0, Math.min(MAX_HVC, hvcCount));
        this.jit = jit;
    }

    /** Defaults for a bundled image: 512 MiB, four extra terminals, JIT on. */
    public static VmOptions defaults(File firmware, File image) {
        return new VmOptions(firmware, image, DEFAULT_MEMORY_MB, DEFAULT_HVC, true);
    }
}
