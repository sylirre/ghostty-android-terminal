package sh.easycli.proot.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.KeyEvent;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import sh.easycli.proot.term.TerminalNative;

/**
 * Round-trips the extra-keys configuration model through real SharedPreferences.
 * Lives in {@code sh.easycli.proot.ui} to reach the package-private catalog API.
 */
@RunWith(AndroidJUnit4.class)
public class ExtraKeysConfigTest {

    private Context context;
    private ExtraKeysConfig config;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        config = new ExtraKeysConfig(context);
        config.reset(); // start from a known (default) state
    }

    @After
    public void tearDown() {
        config.reset(); // don't leak edits into other tests / the app
    }

    @Test
    public void defaultsWhenUnset() {
        assertEquals(ExtraKeysConfig.DEFAULT_IDS, config.order());
    }

    @Test
    public void setOrderRoundTrips() {
        List<String> custom = Arrays.asList("ctrl", "alt", "esc");
        config.setOrder(custom);
        assertEquals(custom, config.order());
        // A fresh instance reads the same persisted value.
        assertEquals(custom, new ExtraKeysConfig(context).order());
    }

    @Test
    public void emptyOrderIsDistinctFromUnset() {
        config.setOrder(Arrays.asList());
        assertTrue("empty list must persist, not fall back to defaults",
                config.order().isEmpty());
    }

    @Test
    public void resetRestoresDefaults() {
        config.setOrder(Arrays.asList("esc"));
        config.reset();
        assertEquals(ExtraKeysConfig.DEFAULT_IDS, config.order());
    }

    @Test
    public void resolveBuiltinKey() {
        ExtraKey esc = ExtraKeysConfig.resolve(context, "esc");
        assertNotNull(esc);
        assertEquals(ExtraKey.Kind.KEY, esc.kind);
        assertEquals(KeyEvent.KEYCODE_ESCAPE, esc.keyCode);
    }

    @Test
    public void resolveCustomLiteral() {
        String id = ExtraKeysConfig.literalId("~");
        ExtraKey k = ExtraKeysConfig.resolve(context, id);
        assertNotNull(k);
        assertEquals(ExtraKey.Kind.TEXT, k.kind);
        assertEquals("~", k.text);
        assertEquals("~", k.label);
    }

    @Test
    public void resolveUnknownIsNull() {
        assertNull(ExtraKeysConfig.resolve(context, "no-such-key"));
        assertNull(ExtraKeysConfig.resolve(context, ExtraKeysConfig.CUSTOM_PREFIX)); // empty literal
    }

    @Test
    public void comboIdIsCanonical() {
        // Fixed Ctrl/Alt/Shift letter order regardless of how the bits combine.
        assertEquals("combo:C:c", ExtraKeysConfig.comboId(TerminalNative.MOD_CTRL, "c"));
        assertEquals("combo:CA:x", ExtraKeysConfig.comboId(
                TerminalNative.MOD_ALT | TerminalNative.MOD_CTRL, "x"));
    }

    @Test
    public void resolveCharCombo() {
        // Ctrl-C: a printable base routes through the TEXT (dispatchText) path.
        ExtraKey k = ExtraKeysConfig.resolve(context, "combo:C:c");
        assertNotNull(k);
        assertEquals(ExtraKey.Kind.TEXT, k.kind);
        assertEquals("c", k.text);
        assertEquals(TerminalNative.MOD_CTRL, k.mods);
        assertEquals("CTRL C", k.label);
    }

    @Test
    public void resolveSpecialCombo() {
        // Ctrl-Right: a special base routes through the mode-aware KEY path.
        ExtraKey k = ExtraKeysConfig.resolve(context, "combo:C:right");
        assertNotNull(k);
        assertEquals(ExtraKey.Kind.KEY, k.kind);
        assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, k.keyCode);
        assertEquals(TerminalNative.MOD_CTRL, k.mods);
    }

    @Test
    public void comboLabelSpellsOutModifiers() {
        // Modifiers are spelled out as text, joined by "-", then a space before
        // the base label: "SHIFT TAB", "CTRL-ALT C".
        ExtraKey shiftTab = ExtraKeysConfig.resolve(context, "combo:S:tab");
        assertNotNull(shiftTab);
        assertEquals("SHIFT TAB", shiftTab.label);

        ExtraKey ctrlAltC = ExtraKeysConfig.resolve(context, "combo:CA:c");
        assertNotNull(ctrlAltC);
        assertEquals("CTRL-ALT C", ctrlAltC.label);
    }

    @Test
    public void comboWithoutModifierIsRejected() {
        assertNull(ExtraKeysConfig.resolve(context, "combo::c"));
    }

    @Test
    public void presetsAreOfferedInPalette() {
        boolean ctrlCListed = false;
        for (ExtraKey k : config.availableBuiltins(context)) {
            if ("combo:C:c".equals(k.id)) ctrlCListed = true;
        }
        assertTrue("Ctrl-C preset should appear in the add palette", ctrlCListed);
    }

    @Test
    public void enabledKeysSkipUnknownIds() {
        config.setOrder(Arrays.asList("esc", "bogus", ExtraKeysConfig.literalId("|")));
        List<ExtraKey> keys = config.enabledKeys(context);
        assertEquals(2, keys.size());
        assertEquals("esc", keys.get(0).id);
        assertEquals("|", keys.get(1).text);
    }

    @Test
    public void availableExcludesEnabled() {
        config.setOrder(Arrays.asList("esc"));
        boolean escListed = false, tabListed = false;
        for (ExtraKey k : config.availableBuiltins(context)) {
            if ("esc".equals(k.id)) escListed = true;
            if ("tab".equals(k.id)) tabListed = true;
        }
        assertFalse("enabled key must not appear in the add palette", escListed);
        assertTrue("a disabled built-in should be offered", tabListed);
    }

    // --- Multi-row layout ---

    @Test
    public void defaultRowsAreStackedButFlattenToDefaultIds() {
        // The default is stacked across rows, but its keys — in order — are
        // exactly DEFAULT_IDS, so order() and legacy migration are unchanged.
        assertTrue("default should be stacked into multiple rows",
                ExtraKeysConfig.DEFAULT_ROWS.size() > 1);
        List<String> flat = new java.util.ArrayList<>();
        for (List<String> row : ExtraKeysConfig.DEFAULT_ROWS) flat.addAll(row);
        assertEquals(ExtraKeysConfig.DEFAULT_IDS, flat);
        // Unset config falls back to that default.
        assertEquals(ExtraKeysConfig.DEFAULT_ROWS, config.rows());
        assertEquals(ExtraKeysConfig.DEFAULT_IDS, config.order());
    }

    @Test
    public void legacyFlatOrderReadsAsSingleRow() {
        // setOrder() persists the old flat format; rows() must read it as one row.
        config.setOrder(Arrays.asList("esc", "ctrl", "tab"));
        assertEquals(Collections.singletonList(Arrays.asList("esc", "ctrl", "tab")),
                config.rows());
    }

    @Test
    public void setRowsRoundTrips() {
        List<List<String>> rows = Arrays.asList(
                Arrays.asList("esc", "ctrl"), Arrays.asList("tab", "up"));
        config.setRows(rows);
        assertEquals(rows, config.rows());
        // A fresh instance reads the same persisted value.
        assertEquals(rows, new ExtraKeysConfig(context).rows());
        // order() flattens across rows.
        assertEquals(Arrays.asList("esc", "ctrl", "tab", "up"), config.order());
    }

    @Test
    public void setRowsDropsEmptyRows() {
        config.setRows(Arrays.asList(
                Arrays.asList("esc"), Arrays.asList(), Arrays.asList("tab")));
        assertEquals(Arrays.asList(Arrays.asList("esc"), Arrays.asList("tab")),
                config.rows());
    }

    @Test
    public void rowsCappedAtMax() {
        config.setRows(Arrays.asList(
                Arrays.asList("esc"), Arrays.asList("tab"),
                Arrays.asList("up"), Arrays.asList("down")));
        assertEquals(ExtraKeysConfig.MAX_ROWS, config.rows().size());
    }

    @Test
    public void enabledRowsSkipUnknownIdsAndEmptyRows() {
        config.setRows(Arrays.asList(
                Arrays.asList("esc", "bogus"),  // unknown id dropped
                Arrays.asList("nope"),          // whole row resolves empty → dropped
                Arrays.asList("tab")));
        List<List<ExtraKey>> rows = config.enabledRows(context);
        assertEquals(2, rows.size());
        assertEquals(1, rows.get(0).size());
        assertEquals("esc", rows.get(0).get(0).id);
        assertEquals("tab", rows.get(1).get(0).id);
    }

    // --- Width & secondary placements ---

    @Test
    public void keySpecWidthAndSecondaryRoundTrip() {
        List<List<ExtraKeysConfig.KeySpec>> rows = Collections.singletonList(Arrays.asList(
                new ExtraKeysConfig.KeySpec("esc"),
                new ExtraKeysConfig.KeySpec("tab", ExtraKeysConfig.WIDTH_2, null),
                new ExtraKeysConfig.KeySpec("slash", ExtraKeysConfig.WIDTH_1, "lit:\\")));
        config.setActiveRows(rows);

        // A fresh instance reads the width and secondary back.
        List<List<ExtraKeysConfig.KeySpec>> back = new ExtraKeysConfig(context).activeRows();
        assertEquals(1, back.size());
        assertEquals(3, back.get(0).size());
        assertEquals(ExtraKeysConfig.WIDTH_2, back.get(0).get(1).width, 0.001f);
        assertEquals("lit:\\", back.get(0).get(2).secondaryId);

        // Resolved keys carry the width and secondary through to ExtraKey.
        List<List<ExtraKey>> resolved = config.enabledRows(context);
        ExtraKey tab = resolved.get(0).get(1);
        assertEquals(ExtraKeysConfig.WIDTH_2, tab.width, 0.001f);
        ExtraKey slash = resolved.get(0).get(2);
        assertTrue(slash.hasSecondary());
        assertEquals("lit:\\", slash.secondaryId);
    }

    @Test
    public void widthSnapsToSupportedMultiplier() {
        assertEquals(ExtraKeysConfig.WIDTH_1, ExtraKeysConfig.clampWidth(1.1f), 0.001f);
        assertEquals(ExtraKeysConfig.WIDTH_1_5, ExtraKeysConfig.clampWidth(1.4f), 0.001f);
        assertEquals(ExtraKeysConfig.WIDTH_2, ExtraKeysConfig.clampWidth(3.0f), 0.001f);
    }

    // --- Migration from the legacy formats ---

    @Test
    public void migratesLegacyFlatArray() {
        config.seedRawForTest("[\"esc\",\"ctrl\",\"tab\"]");
        assertEquals(1, config.profileCount());
        assertEquals(Collections.singletonList(Arrays.asList("esc", "ctrl", "tab")),
                config.rows());
    }

    @Test
    public void migratesLegacyRowsArray() {
        config.seedRawForTest("[[\"esc\",\"ctrl\"],[\"tab\"]]");
        assertEquals(Arrays.asList(Arrays.asList("esc", "ctrl"), Arrays.asList("tab")),
                config.rows());
    }

    @Test
    public void readsV2WithWidthAndSecondary() {
        config.seedRawForTest(
                "{\"v\":2,\"active\":0,\"profiles\":[{\"name\":\"Default\","
                + "\"rows\":[[{\"k\":\"tab\",\"w\":1.5,\"s\":\"lit:x\"}]]}]}");
        List<List<ExtraKeysConfig.KeySpec>> rows = config.activeRows();
        assertEquals(ExtraKeysConfig.WIDTH_1_5, rows.get(0).get(0).width, 0.001f);
        assertEquals("lit:x", rows.get(0).get(0).secondaryId);
    }

    // --- Profiles ---

    @Test
    public void addRenameDuplicateRemoveProfile() {
        assertEquals(1, config.profileCount());
        int vim = config.addProfile("Vim");
        assertEquals(2, config.profileCount());
        assertEquals(vim, config.activeIndex());        // add switches to the new profile
        assertEquals("Vim", config.activeProfileName());

        config.renameProfile(vim, "Neovim");
        assertEquals("Neovim", config.profiles().get(vim).name);

        int dup = config.duplicateProfile(vim);
        assertEquals(3, config.profileCount());
        assertEquals("Neovim 2", config.profiles().get(dup).name);  // de-duplicated name

        config.removeProfile(dup);
        assertEquals(2, config.profileCount());
    }

    @Test
    public void removeProfileKeepsAtLeastOne() {
        config.removeProfile(0);
        assertEquals(1, config.profileCount());
    }

    @Test
    public void activeIndexClampedAndPersisted() {
        config.addProfile("Vim");     // active → 1
        config.setActiveIndex(0);
        assertEquals(0, new ExtraKeysConfig(context).activeIndex());
        config.setActiveIndex(99);    // out of range → ignored
        assertEquals(0, config.activeIndex());
    }

    @Test
    public void profilesAreIndependent() {
        // Editing the active profile must not touch another.
        config.setOrder(Arrays.asList("esc"));
        int vim = config.addProfile("Vim");            // active → Vim (default rows)
        config.setOrder(Arrays.asList("tab"));         // edits Vim only
        config.setActiveIndex(0);
        assertEquals(Arrays.asList("esc"), config.order());
        config.setActiveIndex(vim);
        assertEquals(Arrays.asList("tab"), config.order());
    }
}
