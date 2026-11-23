import { defineConfig } from "@playwright/test";

/**
 * End-to-end tests run against their OWN isolated stack, never the developer's data:
 *   backend :8081 (Spring profile `e2e`, database rebuilt from scratch on start), web :5183, CMS :5184.
 * Prerequisites: PostgreSQL and Mailpit running, backend jar built (`cd backend; .\mvnw.cmd -DskipTests package`).
 * Locally PW_CHANNEL=msedge uses the installed Edge instead of a downloaded Chromium.
 */
const api = { API_TARGET: "http://localhost:8081" };

export default defineConfig({
  testDir: ".",
  timeout: 60_000,
  retries: 0,
  workers: 1, // one shared, freshly seeded database: keep the run deterministic
  reporter: [["list"], ["html", { outputFolder: "../playwright-report", open: "never" }]],
  use: {
    channel: process.env.PW_CHANNEL || undefined,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    locale: "tr-TR",
    timezoneId: "Europe/Istanbul",
  },
  webServer: [
    // fake external API for tools (JDK only); reused when a developer already runs it on :8090
    { command: "java mock-api/MockApi.java", cwd: "..", url: "http://localhost:8090/health", reuseExistingServer: true, timeout: 60_000 },
    {
      command: "java -jar backend/target/basamak-backend-0.1.0.jar --spring.profiles.active=e2e",
      cwd: "..",
      url: "http://localhost:8081/actuator/health",
      reuseExistingServer: false,
      timeout: 120_000,
    },
    { command: "npm run dev:e2e -w apps/web", cwd: "..", url: "http://localhost:5183", reuseExistingServer: false, env: api, timeout: 60_000 },
    { command: "npm run dev:e2e -w apps/cms", cwd: "..", url: "http://localhost:5184", reuseExistingServer: false, env: api, timeout: 60_000 },
  ],
});
