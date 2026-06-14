package sh.easycli.proot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import sh.easycli.proot.ui.TerminalTheme;
import sh.easycli.proot.ui.ThemeStore;

/**
 * The pure-Java theme model and its persistence. Instrumented (the project has
 * no JVM unit tests), but touches no native code or shell. The store test uses
 * the real app SharedPreferences and cleans up after itself.
 */
@RunWith(AndroidJUnit4.class)
public class ThemeModelTest {

    private TerminalTheme sampleTheme(String name) {
        int[] ansi = new int[TerminalTheme.ANSI_COUNT];
        for (int i = 0; i < ansi.length; i++) ansi[i] = 0xFF000000 | (i * 0x101010);
        return new TerminalTheme(name, 0xFFEEEEEE, 0xFF111111, 0xFF00FF00, ansi);
    }

    @Test
    public void palette256GeneratesCubeAndGrayscale() {
        TerminalTheme t = sampleTheme("Sample");
        int[] pal = t.toPalette256();
        assertEquals(256, pal.length);
        // 0–15 are the theme's ANSI colors verbatim.
        assertEquals(t.ansi[0], pal[0]);
        assertEquals(t.ansi[15], pal[15]);
        // 16–231: the 6×6×6 cube. 16 = (0,0,0), 231 = (255,255,255),
        // 196 = pure red (255,0,0).
        assertEquals(0xFF000000, pal[16]);
        assertEquals(0xFFFFFFFF, pal[231]);
        assertEquals(0xFFFF0000, pal[196]);
        // 232–255: grayscale ramp 8..238.
        assertEquals(0xFF080808, pal[232]);
        assertEquals(0xFFEEEEEE, pal[255]);
    }

    @Test
    public void csvRoundTrip() {
        TerminalTheme t = sampleTheme("Round");
        TerminalTheme back = TerminalTheme.fromCsv("Round", t.toCsv());
        assertTrue(t.sameColors(back));
        assertEquals("Round", back.name);
    }

    @Test
    public void storeSaveRenameDelete() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ThemeStore store = new ThemeStore(ctx);
        String original = store.selectedName();
        String n1 = "JUnitTheme_" + System.nanoTime();
        String n2 = n1 + "_renamed";
        try {
            store.saveUserTheme(sampleTheme(n1));
            assertNotNull(store.findByName(n1));
            assertFalse("user theme must not read as a preset", store.isPreset(n1));

            store.setSelected(n1);
            store.renameUserTheme(n1, n2);
            assertNull(store.findByName(n1));
            assertNotNull(store.findByName(n2));
            // The selection follows a rename of the selected theme.
            assertEquals(n2, store.selectedName());

            store.deleteUserTheme(n2);
            assertNull(store.findByName(n2));
        } finally {
            store.deleteUserTheme(n1);
            store.deleteUserTheme(n2);
            store.setSelected(original);
        }
    }

    @Test
    public void presetsArePresentAndResolvable() {
        ThemeStore store = new ThemeStore(ApplicationProvider.getApplicationContext());
        assertFalse(store.presets().isEmpty());
        TerminalTheme first = store.presets().get(0);
        assertTrue(store.isPreset(first.name));
        // current() always resolves to a real theme.
        assertNotNull(store.current());
    }
}
