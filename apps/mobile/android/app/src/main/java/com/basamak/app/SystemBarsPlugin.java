package com.basamak.app;

import android.app.Activity;
import android.content.res.Configuration;
import android.view.Window;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Status and navigation bars in the app's colors (same as the top app bar / bottom navigation): light bars with dark
 * icons in the light theme, dark bars with light icons in the dark theme. Follows the system theme by default; the
 * web layer calls setTheme when the member picks "Açık" or "Koyu" inside the app ("system" goes back to following).
 */
@CapacitorPlugin(name = "SystemBars")
public class SystemBarsPlugin extends Plugin {
    static final int LIGHT_BAR = 0xFFFFFFFF; // tokens.css color-surface (light)
    static final int DARK_BAR = 0xFF171A22;  // tokens.css color-surface (dark)
    private static String mode = "system";

    static void apply(Activity activity) {
        boolean systemDark = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        boolean dark = "dark".equals(mode) || ("system".equals(mode) && systemDark);
        Window w = activity.getWindow();
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            // no grey contrast scrim over our bars: the app paints them itself (light bar + dark icons or the reverse)
            w.setStatusBarContrastEnforced(false);
            w.setNavigationBarContrastEnforced(false);
        }
        w.setStatusBarColor(dark ? DARK_BAR : LIGHT_BAR);
        w.setNavigationBarColor(dark ? DARK_BAR : LIGHT_BAR);
        w.getDecorView().setBackgroundColor(dark ? DARK_BAR : LIGHT_BAR); // Android 15: the bar shows the window behind it
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
        c.setAppearanceLightStatusBars(!dark);
        c.setAppearanceLightNavigationBars(!dark);
    }

    @PluginMethod
    public void setTheme(PluginCall call) {
        String m = call.getString("mode", "system");
        mode = "light".equals(m) || "dark".equals(m) ? m : "system";
        getActivity().runOnUiThread(() -> {
            apply(getActivity());
            call.resolve();
        });
    }
}
