import { defineConfig } from "@playwright/test";

/**
 * End-to-end tests against the real stack (backend :8080 with Postgres + Mailpit, web :5173, CMS :5174).
 * Start the backend first (scripts/dev.ps1 or `java -jar backend/target/*.jar`); Vite servers are started here
 * if they are not already running. Locally PW_CHANNEL=msedge uses the installed Edge instead of a downloaded Chromium.
 */
export default defineConfig({
  testDir: ".",
  timeout: 60_000,
  retries: 0,
  reporter: [["list"], ["html", { outputFolder: "../playwright-report", open: "never" }]],
  use: {
    channel: process.env.PW_CHANNEL || undefined,
    trace: "retain-on-failure",
    locale: "tr-TR",
    timezoneId: "Europe/Istanbul",
  },
  webServer: [
    { command: "npm run dev:web", cwd: "..", url: "http://localhost:5173", reuseExistingServer: true, timeout: 60_000 },
    { command: "npm run dev:cms", cwd: "..", url: "http://localhost:5174", reuseExistingServer: true, timeout: 60_000 },
  ],
});
