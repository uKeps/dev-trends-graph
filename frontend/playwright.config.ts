import { defineConfig, devices } from "@playwright/test";

/**
 * Smoke + a11y suite for the dev trends UI. The tests start a local
 * frontend, stub the backend responses so the assertions don't depend on
 * Supabase being reachable, and run axe-core against the rendered page.
 */
export default defineConfig({
  testDir: "./tests",
  fullyParallel: true,
  retries: 0,
  reporter: process.env.CI ? "github" : "list",
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    trace: "retain-on-failure",
  },
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: "npm start",
        port: 3000,
        reuseExistingServer: !process.env.CI,
        timeout: 60_000,
      },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
});
