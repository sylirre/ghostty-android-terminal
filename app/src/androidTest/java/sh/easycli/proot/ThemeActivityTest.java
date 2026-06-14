package sh.easycli.proot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.widget.GridLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import sh.easycli.proot.ui.ThemeActivity;

/**
 * Smoke test for the theme editor: it must inflate, build its swatch grid, and
 * survive recreation without crashing. The rest of the theming logic is
 * covered by {@link ThemeModelTest} (model/store) and {@link EmulatorVtTest}
 * (the native color pipeline).
 */
@RunWith(AndroidJUnit4.class)
public class ThemeActivityTest {

    @Test
    public void launchesAndBuildsSwatchGrid() {
        try (ActivityScenario<ThemeActivity> scenario =
                     ActivityScenario.launch(ThemeActivity.class)) {
            scenario.onActivity(a -> {
                GridLayout grid = a.findViewById(R.id.theme_swatches);
                assertNotNull(grid);
                // foreground + background + cursor + 16 ANSI colors.
                assertEquals(19, grid.getChildCount());
            });
            scenario.recreate();
            scenario.onActivity(a ->
                    assertEquals(19, ((GridLayout) a.findViewById(R.id.theme_swatches))
                            .getChildCount()));
        }
    }
}
