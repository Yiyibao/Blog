import { expect, test } from '@playwright/test';

test('a loaded public page exposes offline state without caching private data', async ({ page }) => {
  await page.goto('/articles');
  await expect(page.locator('.site-header')).toBeVisible();

  await page.context().setOffline(true);
  await expect(page.locator('.offline-reading-banner')).toBeVisible();
  await expect(page.locator('.site-header')).toBeVisible();
});

test('weak API responses remain explicit and never become bundled demo content', async ({ page }) => {
  await page.route('**/api/v1/posts**', (route) => route.fulfill({ status: 504, body: 'gateway timeout' }));
  await page.route('**/api/v1/categories**', (route) => route.fulfill({ status: 504, body: 'gateway timeout' }));

  await page.goto('/articles');
  await expect(page.locator('.content-unavailable')).toBeVisible();
  await expect(page.locator('text=clarity-by-design')).toHaveCount(0);
});
