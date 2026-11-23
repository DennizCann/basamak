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
  android: { backgroundColor: "#0f1117" },
  ios: { backgroundColor: "#0f1117" },
};

export default config;
