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
import java.util.List;

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
}
