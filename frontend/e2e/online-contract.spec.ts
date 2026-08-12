import { expect, test } from '@playwright/test';

test('online API returns a real envelope and the public page renders from it', async ({ page, request }) => {
  const response = await request.get('/api/v1/posts?page=0&size=1');
  expect(response.ok()).toBe(true);
  const envelope = await response.json();
  expect(envelope.code).toBe(200);
  expect(envelope.data).toHaveProperty('items');

  await page.goto('/articles');
  await expect(page.locator('.offline-reading-banner')).toHaveCount(0);
  await expect(page.locator('.content-unavailable')).toHaveCount(0);
});

test('online API failure is visible and never becomes bundled demo content', async ({ page }) => {
  await page.route('**/api/v1/posts**', (route) => route.fulfill({ status: 503, body: 'unavailable' }));
  await page.route('**/api/v1/categories**', (route) => route.fulfill({ status: 503, body: 'unavailable' }));

  await page.goto('/articles');
  await expect(page.locator('.content-unavailable')).toBeVisible();
  await expect(page.locator('text=clarity-by-design')).toHaveCount(0);
});
