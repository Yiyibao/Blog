import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  retries: process.env.CI ? 1 : 0,
  use: { baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:4173', trace: 'retain-on-failure' },
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: 'npm run preview -- --port 4173',
        url: 'http://127.0.0.1:4173',
        reuseExistingServer: !process.env.CI,
      },
  projects: [
    {
      name: 'offline-chromium',
      testMatch: /(?:public-site|pwa-offline)\.spec\.ts/,
      use: { ...devices['Desktop Chrome'], channel: process.env.CI ? undefined : 'chrome' },
    },
    {
      name: 'online-chromium',
      testMatch: /(?:online-contract|full-stack)\.spec\.ts/,
      use: { ...devices['Desktop Chrome'], channel: process.env.CI ? undefined : 'chrome' },
    },
    {
      name: 'online-firefox',
      testMatch: /online-contract\.spec\.ts/,
      use: { ...devices['Desktop Firefox'] },
    },
    {
      name: 'online-mobile-chromium',
      testMatch: /online-contract\.spec\.ts/,
      use: { ...devices['Pixel 7'] },
    },
  ],
});
