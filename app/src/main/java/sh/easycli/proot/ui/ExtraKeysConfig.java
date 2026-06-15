package sh.easycli.proot.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import sh.easycli.proot.R;
import sh.easycli.proot.term.TerminalNative;

/**
 * The source of truth for the extra-keys toolbar layout: the built-in key
 * {@linkplain #catalog catalog} plus the user's ordered list of enabled key ids.
 *
 * Same approach as {@link ThemeStore}: the order is persisted in its own
 * {@link SharedPreferences} file as a JSON array of ids, so it survives process
 * death. An id is either a catalog key (e.g. {@code "esc"}) or {@code "lit:"} +
 * an arbitrary literal for a user-defined text key.
 *
 * The catalog is intentionally limited to keys the native {@code map_keycode}
 * (terminal_jni.c) actually encodes — offering a key that produces nothing would
 * be a dead button.
 */
public final class ExtraKeysConfig {

    private static final String FILE = "extrakeys";
    private static final String KEY_ORDER = "order";

    /** Prefix marking a user-defined literal text key; the rest is the text. */
    static final String CUSTOM_PREFIX = "lit:";

    /**
     * The default toolbar — the original hardcoded order. Kept byte-for-byte so
     * a fresh install (and the UI tests asserting ESC/CTRL/"/"/"─") behave as
     * before.
     */
    static final List<String> DEFAULT_IDS = Collections.unmodifiableList(Arrays.asList(
            "esc", "ctrl", "alt", "tab", "up", "down", "left", "right",
            "home", "end", "pgup", "pgdn", "dash", "slash", "pipe"));

    private final SharedPreferences prefs;

    public ExtraKeysConfig(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /**
     * Every built-in key, keyed by id, in catalog (display) order. Labels come
     * from resources where one exists; F-keys and punctuation are inlined since
     * their glyphs are universal.
     */
    static LinkedHashMap<String, ExtraKey> catalog(Context c) {
        LinkedHashMap<String, ExtraKey> m = new LinkedHashMap<>();
        // Sticky modifiers.
        put(m, ExtraKey.modifier("ctrl", c.getString(R.string.key_ctrl), TerminalNative.MOD_CTRL));
        put(m, ExtraKey.modifier("alt", c.getString(R.string.key_alt), TerminalNative.MOD_ALT));
        // Non-printable keys (sent through the VT encoder).
        put(m, ExtraKey.key("esc", c.getString(R.string.key_esc), KeyEvent.KEYCODE_ESCAPE));
        put(m, ExtraKey.key("tab", c.getString(R.string.key_tab), KeyEvent.KEYCODE_TAB));
        put(m, ExtraKey.key("enter", c.getString(R.string.key_enter), KeyEvent.KEYCODE_ENTER));
        put(m, ExtraKey.key("bksp", c.getString(R.string.key_bksp), KeyEvent.KEYCODE_DEL));
        put(m, ExtraKey.key("del", c.getString(R.string.key_del), KeyEvent.KEYCODE_FORWARD_DEL));
        put(m, ExtraKey.key("ins", c.getString(R.string.key_ins), KeyEvent.KEYCODE_INSERT));
        put(m, ExtraKey.key("up", c.getString(R.string.key_up), KeyEvent.KEYCODE_DPAD_UP));
        put(m, ExtraKey.key("down", c.getString(R.string.key_down), KeyEvent.KEYCODE_DPAD_DOWN));
        put(m, ExtraKey.key("left", c.getString(R.string.key_left), KeyEvent.KEYCODE_DPAD_LEFT));
        put(m, ExtraKey.key("right", c.getString(R.string.key_right), KeyEvent.KEYCODE_DPAD_RIGHT));
        put(m, ExtraKey.key("home", c.getString(R.string.key_home), KeyEvent.KEYCODE_MOVE_HOME));
        put(m, ExtraKey.key("end", c.getString(R.string.key_end), KeyEvent.KEYCODE_MOVE_END));
        put(m, ExtraKey.key("pgup", c.getString(R.string.key_pgup), KeyEvent.KEYCODE_PAGE_UP));
        put(m, ExtraKey.key("pgdn", c.getString(R.string.key_pgdn), KeyEvent.KEYCODE_PAGE_DOWN));
        for (int i = 0; i < 12; i++) {
            put(m, ExtraKey.key("f" + (i + 1), "F" + (i + 1), KeyEvent.KEYCODE_F1 + i));
        }
        // Literal text keys. "dash" shows "─" but sends "-" (legacy label).
        put(m, ExtraKey.text("dash", c.getString(R.string.key_dash), "-"));
        put(m, ExtraKey.text("slash", c.getString(R.string.key_slash), "/"));
        put(m, ExtraKey.text("pipe", c.getString(R.string.key_pipe), "|"));
        for (String s : new String[]{"~", "`", "*", "=", "+", "#", ":", ";", "<", ">", "&", "?"}) {
            put(m, ExtraKey.text(literalId(s), s, s));
        }
        return m;
    }

    private static void put(LinkedHashMap<String, ExtraKey> m, ExtraKey k) {
        m.put(k.id, k);
    }

    /** The persistence id for a custom/literal text key. */
    static String literalId(String text) {
        return CUSTOM_PREFIX + text;
    }

    /** Resolves an id to its key (catalog entry or custom literal); null if unknown. */
    static ExtraKey resolve(Context c, String id) {
        ExtraKey k = catalog(c).get(id);
        if (k != null) return k;
        if (id.startsWith(CUSTOM_PREFIX)) {
            String t = id.substring(CUSTOM_PREFIX.length());
            if (!t.isEmpty()) {
                String label = t.replaceAll("\n+$", "");
                if (label.isEmpty()) label = "↵";
                return ExtraKey.text(id, label, t);
            }
        }
        return null;
    }

    /** The persisted enabled-id order, or the defaults if never set. */
    public List<String> order() {
        String raw = prefs.getString(KEY_ORDER, null);
        if (raw == null) return new ArrayList<>(DEFAULT_IDS);
        try {
            JSONArray arr = new JSONArray(raw);
            List<String> out = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
            return out;
        } catch (JSONException malformed) {
            return new ArrayList<>(DEFAULT_IDS);
        }
    }

    public void setOrder(List<String> ids) {
        JSONArray arr = new JSONArray();
        for (String id : ids) arr.put(id);
        prefs.edit().putString(KEY_ORDER, arr.toString()).apply();
    }

    /** Drops the saved order so {@link #order()} returns the defaults again. */
    public void reset() {
        prefs.edit().remove(KEY_ORDER).apply();
    }

    /** The enabled keys in order, skipping any id that no longer resolves. */
    public List<ExtraKey> enabledKeys(Context c) {
        List<ExtraKey> out = new ArrayList<>();
        for (String id : order()) {
            ExtraKey k = resolve(c, id);
            if (k != null) out.add(k);
        }
        return out;
    }

    /** Catalog keys not currently enabled — the "add" palette. */
    public List<ExtraKey> availableBuiltins(Context c) {
        Set<String> enabled = new HashSet<>(order());
        List<ExtraKey> out = new ArrayList<>();
        for (ExtraKey k : catalog(c).values()) {
            if (!enabled.contains(k.id)) out.add(k);
        }
        return out;
    }
}
