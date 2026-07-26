/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Asset-name parsing behind the onboarding distro chooser. */
@RunWith(AndroidJUnit4.class)
public class UserlandDistroTest {

    @Test
    public void parsesDistroAndVersion() {
        UserlandDistro d = UserlandDistro.fromAssetName(
                "debian_trixie_aarch64_rootfs.tar.xz", 42);
        assertNotNull(d);
        assertEquals("debian", d.id);
        assertEquals("trixie", d.version);
        assertEquals("debian_trixie_aarch64_rootfs.tar.xz", d.assetName);
        assertEquals(42, d.sizeBytes);

        d = UserlandDistro.fromAssetName("alpine_3.24.1_aarch64_rootfs.tar.xz", 0);
        assertNotNull(d);
        assertEquals("alpine", d.id);
        assertEquals("3.24.1", d.version);
    }

    @Test
    public void parsesVersionlessName() {
        UserlandDistro d = UserlandDistro.fromAssetName("gentoo_aarch64_rootfs.tar.xz", 0);
        assertNotNull(d);
        assertEquals("gentoo", d.id);
        assertEquals("", d.version);
    }

    @Test
    public void rejectsForeignAssetNames() {
        // Wrong arch: only aarch64 rootfs are ever packaged.
        assertNull(UserlandDistro.fromAssetName(
                "debian_trixie_x86_64_rootfs.tar.xz", 0));
        // Empty distro id.
        assertNull(UserlandDistro.fromAssetName("_aarch64_rootfs.tar.xz", 0));
        // Unrelated assets that share the assets/ root.
        assertNull(UserlandDistro.fromAssetName("fonts.xml", 0));
        assertNull(UserlandDistro.fromAssetName(null, 0));
    }
}
