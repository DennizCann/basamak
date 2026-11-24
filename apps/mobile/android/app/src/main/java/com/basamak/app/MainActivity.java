package com.basamak.app;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // app-local native plugins must be registered before the bridge starts
        registerPlugin(SecureStoragePlugin.class);
        registerPlugin(PrivacyScreenPlugin.class);
        registerPlugin(SystemBarsPlugin.class);
        super.onCreate(savedInstanceState);
    }

    // after the splash screen and on every return the window theme may be re-applied: keep the bars in app colors
    @Override
    public void onResume() {
        super.onResume();
        SystemBarsPlugin.apply(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) SystemBarsPlugin.apply(this);
    }
}
