import type { CapacitorConfig } from "@capacitor/cli";

/**
 * The member web app is BUNDLED into the app (webDir) - nothing is loaded from a remote site.
 * androidScheme "http": the app runs on http://localhost so it can call the local dev API over http without
 * mixed-content errors. Cleartext traffic is limited to the dev hosts in network_security_config.xml; a release
 * build points VITE_API_BASE at an https API.
 */
const config: CapacitorConfig = {
  appId: "com.basamak.app",
  appName: "Basamak",
  webDir: "www",
  server: { androidScheme: "http" },
  // Android 15 edge-to-edge: the WebView gets margins for the system bars; the bars show the window color behind
  // them (SystemBarsPlugin keeps it equal to the app bar, light or dark) instead of opting out of edge-to-edge
  android: { backgroundColor: "#0f1117", adjustMarginsForEdgeToEdge: "force" },
  ios: { backgroundColor: "#0f1117" },
  plugins: {
    // brand splash (Android 12+ SplashScreen API); the app hides it as soon as the session check is done,
    // at most after 3 s so a failed start never leaves the user on the splash
    SplashScreen: { launchShowDuration: 3000, launchAutoHide: true, backgroundColor: "#3B30C9", showSpinner: false },
  },
};

export default config;
