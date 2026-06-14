package sh.easycli.proot.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The source of truth for terminal theming: the read-only {@link ThemePresets}
 * plus any number of named user themes, and which one is currently selected.
 *
 * User themes and the selection are persisted in their own SharedPreferences
 * file (same approach as {@link AppSettings}) so they survive process death.
 * User themes are stored as a single JSON array of {name, colors} pairs, where
 * {@code colors} is {@link TerminalTheme#toCsv}. Names are unique across the
 * whole set (presets included); presets always win a name clash and cannot be
 * edited or deleted.
 */
public final class ThemeStore {

    private static final String FILE = "themes";
    private static final String KEY_SELECTED = "selected";
    private static final String KEY_USER_THEMES = "user_themes";

    private final SharedPreferences prefs;

    public ThemeStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Built-in presets (immutable). */
    public List<TerminalTheme> presets() {
        return ThemePresets.ALL;
    }

    /** User-created themes, in saved order. */
    public List<TerminalTheme> userThemes() {
        List<TerminalTheme> out = new ArrayList<>();
        String raw = prefs.getString(KEY_USER_THEMES, null);
        if (raw == null) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                try {
                    out.add(TerminalTheme.fromCsv(o.getString("name"),
                            o.getString("colors")));
                } catch (IllegalArgumentException malformed) {
                    // Skip a corrupt entry rather than losing every theme.
                }
            }
        } catch (JSONException ignored) {
            // Unreadable blob: treat as no user themes.
        }
        return out;
    }

    /** Presets followed by user themes — the full pick list. */
    public List<TerminalTheme> all() {
        List<TerminalTheme> out = new ArrayList<>(presets());
        out.addAll(userThemes());
        return out;
    }

    /** Name of the selected theme; defaults to the first preset. */
    public String selectedName() {
        return prefs.getString(KEY_SELECTED, ThemePresets.DEFAULT.name);
    }

    public void setSelected(String name) {
        prefs.edit().putString(KEY_SELECTED, name).apply();
    }

    /** The selected theme, resolved by name; falls back to the default preset. */
    public TerminalTheme current() {
        TerminalTheme t = findByName(selectedName());
        return t != null ? t : ThemePresets.DEFAULT;
    }

    /** The theme with this name (preset or user), or null if none. */
    public TerminalTheme findByName(String name) {
        for (TerminalTheme t : all()) {
            if (t.name.equals(name)) return t;
        }
        return null;
    }

    public boolean isPreset(String name) {
        return ThemePresets.isPreset(name);
    }

    /** True if any preset or user theme already uses this name. */
    public boolean nameExists(String name) {
        return findByName(name) != null;
    }

    /**
     * Inserts or replaces a user theme by name (a preset name is rejected, since
     * presets are immutable). Does not change the current selection.
     */
    public void saveUserTheme(TerminalTheme theme) {
        if (isPreset(theme.name)) return;
        List<TerminalTheme> themes = userThemes();
        boolean replaced = false;
        for (int i = 0; i < themes.size(); i++) {
            if (themes.get(i).name.equals(theme.name)) {
                themes.set(i, theme);
                replaced = true;
                break;
            }
        }
        if (!replaced) themes.add(theme);
        persist(themes);
    }

    /**
     * Renames a user theme, carrying the selection along if it pointed at it.
     * No-op if {@code oldName} isn't a user theme or {@code newName} is taken.
     */
    public void renameUserTheme(String oldName, String newName) {
        if (isPreset(oldName) || nameExists(newName)) return;
        List<TerminalTheme> themes = userThemes();
        for (int i = 0; i < themes.size(); i++) {
            if (themes.get(i).name.equals(oldName)) {
                themes.set(i, themes.get(i).withName(newName));
                persist(themes);
                if (selectedName().equals(oldName)) setSelected(newName);
                return;
            }
        }
    }

    /**
     * Deletes a user theme; if it was selected, selection reverts to the
     * default preset. No-op for presets.
     */
    public void deleteUserTheme(String name) {
        if (isPreset(name)) return;
        List<TerminalTheme> themes = userThemes();
        boolean removed = themes.removeIf(t -> t.name.equals(name));
        if (!removed) return;
        persist(themes);
        if (selectedName().equals(name)) setSelected(ThemePresets.DEFAULT.name);
    }

    /** A name not used by any preset or user theme, e.g. "Custom 2". */
    public String suggestName(String base) {
        if (!nameExists(base)) return base;
        for (int i = 2; ; i++) {
            String candidate = base + " " + i;
            if (!nameExists(candidate)) return candidate;
        }
    }

    private void persist(List<TerminalTheme> themes) {
        JSONArray arr = new JSONArray();
        for (TerminalTheme t : themes) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", t.name);
                o.put("colors", t.toCsv());
                arr.put(o);
            } catch (JSONException ignored) {
                // A name/colors pair that won't serialize is dropped.
            }
        }
        prefs.edit().putString(KEY_USER_THEMES, arr.toString()).apply();
    }
}
