/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the "User identity" and derived Home/Working-directory Userland
 * settings against the guest user/group database.
 *
 * arm64chroot's {@code --fake-id} accepts numeric {@code uid[:gid]} only, so a
 * named user or group in the setting must be translated to numbers here by
 * parsing the rootfs {@code /etc/passwd} and {@code /etc/group} before the value
 * reaches the emulator. All lookups tolerate a missing or unreadable database
 * (a freshly restored or gutted rootfs) by falling back to the root default
 * {@code 0:0} / no home, so a bad setting can never keep a session from
 * spawning.
 */
public final class UserlandIdentity {

    private UserlandIdentity() {}

    /** The root identity, used whenever the setting is empty/invalid/unresolvable. */
    private static final String DEFAULT_ID = "0:0";

    /** Cap on DB lines read, so a hostile/huge passwd can't exhaust memory. */
    private static final int MAX_LINES = 100_000;

    /**
     * Resolves a "User identity" setting to a numeric {@code "uid:gid"} suitable
     * for {@code --fake-id}. Accepts {@code user}, {@code user:group},
     * {@code uid} or {@code uid:gid}; named components are looked up in the
     * rootfs {@code /etc/passwd} / {@code /etc/group}, numeric ones are used
     * as-is. Returns {@code "0:0"} for an empty, malformed, or unresolvable
     * value.
     */
    public static String resolveFakeId(File rootfs, String identity) {
        if (identity == null) return DEFAULT_ID;
        String spec = identity.trim();
        if (spec.isEmpty()) return DEFAULT_ID;

        int colon = spec.indexOf(':');
        // "uid:gid" has exactly one colon; a second one is malformed.
        if (colon >= 0 && spec.indexOf(':', colon + 1) >= 0) return DEFAULT_ID;
        String userTok = colon < 0 ? spec : spec.substring(0, colon);
        String groupTok = colon < 0 ? null : spec.substring(colon + 1);
        if (userTok.isEmpty()) return DEFAULT_ID;

        long uid;
        Long primaryGid = null; // the user's passwd gid, used when no group given
        if (isAllDigits(userTok)) {
            try {
                uid = Long.parseLong(userTok);
            } catch (NumberFormatException e) {
                return DEFAULT_ID;
            }
        } else {
            Passwd pw = findPasswd(rootfs, userTok, null);
            if (pw == null) return DEFAULT_ID;
            uid = pw.uid;
            primaryGid = pw.gid;
        }

        long gid;
        if (groupTok != null) {
            if (groupTok.isEmpty()) return DEFAULT_ID;
            if (isAllDigits(groupTok)) {
                try {
                    gid = Long.parseLong(groupTok);
                } catch (NumberFormatException e) {
                    return DEFAULT_ID;
                }
            } else {
                Long g = findGroupGid(rootfs, groupTok);
                if (g == null) return DEFAULT_ID;
                gid = g;
            }
        } else if (primaryGid != null) {
            gid = primaryGid;       // named user: its primary group from passwd
        } else {
            gid = uid;              // numeric single value: uid == gid (native "N" semantics)
        }

        return uid + ":" + gid;
    }

    /**
     * The absolute home directory of the user named by {@code identity} (its
     * {@code /etc/passwd} home field), or {@code null} when the user has no
     * passwd entry or a non-absolute home. Used to populate the Home setting
     * when the identity is configured, and to derive the Home / Working-directory
     * defaults when their settings are empty.
     */
    public static String homeForIdentity(File rootfs, String identity) {
        long uid;
        try {
            uid = Long.parseLong(resolveFakeId(rootfs, identity).split(":")[0]);
        } catch (NumberFormatException e) {
            return null;
        }
        Passwd pw = findPasswd(rootfs, null, uid);
        if (pw != null && pw.home != null && pw.home.startsWith("/")) {
            return pw.home;
        }
        return null;
    }

    /**
     * The name to advertise as the guest {@code USER}, kept in sync with the
     * identity: the {@code /etc/passwd} name for the resolved uid when one
     * exists, otherwise the uid as a decimal string (so USER stays consistent
     * with {@code --fake-id} even for a uid with no passwd entry).
     */
    public static String userNameForIdentity(File rootfs, String identity) {
        long uid;
        try {
            uid = Long.parseLong(resolveFakeId(rootfs, identity).split(":")[0]);
        } catch (NumberFormatException e) {
            return "0";
        }
        Passwd pw = findPasswd(rootfs, null, uid);
        return pw != null ? pw.name : Long.toString(uid);
    }

    // --- /etc/passwd and /etc/group parsing ---

    /** A parsed /etc/passwd row (only the fields we use). */
    private static final class Passwd {
        final String name;
        final long uid;
        final long gid;
        final String home;

        Passwd(String name, long uid, long gid, String home) {
            this.name = name;
            this.uid = uid;
            this.gid = gid;
            this.home = home;
        }
    }

    /**
     * First {@code /etc/passwd} entry matching {@code name} (when non-null) or
     * {@code uid} (when non-null), or {@code null}. Lines are
     * {@code name:passwd:uid:gid:gecos:home:shell}.
     */
    private static Passwd findPasswd(File rootfs, String name, Long uid) {
        for (String line : readLines(new File(rootfs, "etc/passwd"))) {
            String[] f = line.split(":", -1);
            if (f.length < 6) continue; // need through the home field (index 5)
            if (name != null && !f[0].equals(name)) continue;
            Passwd pw = parsePasswd(f);
            if (pw == null) continue;
            if (uid != null && pw.uid != uid) continue;
            return pw;
        }
        return null;
    }

    private static Passwd parsePasswd(String[] f) {
        try {
            return new Passwd(f[0], Long.parseLong(f[2].trim()),
                    Long.parseLong(f[3].trim()), f[5]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The gid of the {@code /etc/group} entry named {@code name}, or {@code null}. */
    private static Long findGroupGid(File rootfs, String name) {
        for (String line : readLines(new File(rootfs, "etc/group"))) {
            String[] f = line.split(":", -1); // name:passwd:gid:members
            if (f.length < 3) continue;
            if (!f[0].equals(name)) continue;
            try {
                return Long.parseLong(f[2].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Reads all lines of a rootfs DB file, or an empty list when it is missing
     * or unreadable (a gutted/restored rootfs) — callers then fall back to
     * defaults. Capped at {@link #MAX_LINES}.
     */
    private static List<String> readLines(File file) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while (lines.size() < MAX_LINES && (line = r.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            // missing/unreadable — treat as no entries
        }
        return lines;
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }
}
