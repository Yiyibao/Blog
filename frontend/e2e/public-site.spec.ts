import AxeBuilder from '@axe-core/playwright';
import { devices, expect, test } from '@playwright/test';

test('public home supports keyboard navigation and has no serious accessibility violations', async ({
  page,
}) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto('/');
  await expect(page).toHaveTitle(/余白|日常|拾光/);
  await page.keyboard.press('Tab');
  await expect(page.locator(':focus')).not.toHaveCount(0);
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze();
  expect(
    results.violations.filter((violation) => ['serious', 'critical'].includes(violation.impact ?? '')),
  ).toEqual([]);
});

test('global search opens and closes from the keyboard', async ({ page }) => {
  await page.goto('/');
  await page.keyboard.press('Control+k');
  const search = page.getByRole('dialog', { name: '全站搜索' });
  await expect(search).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(search).toBeHidden();
});

test.describe('mobile performance budget', () => {
  test.use({
    viewport: devices['Pixel 7'].viewport,
    userAgent: devices['Pixel 7'].userAgent,
    deviceScaleFactor: devices['Pixel 7'].deviceScaleFactor,
    isMobile: true,
    hasTouch: true,
  });

  test('home LCP stays below 2.5s and excludes admin-only bundles', async ({ page }) => {
    await page.addInitScript(() => {
      (window as typeof window & { __lcp?: number }).__lcp = 0;
      new PerformanceObserver((list) => {
        const entries = list.getEntries();
        const last = entries.at(-1) as PerformanceEntry & { renderTime?: number; loadTime?: number };
        (window as typeof window & { __lcp?: number }).__lcp = last?.renderTime || last?.loadTime || 0;
      }).observe({ type: 'largest-contentful-paint', buffered: true });
    });

    await page.goto('/');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(500);
    const lcp = await page.evaluate(() => (window as typeof window & { __lcp?: number }).__lcp ?? 0);
    const resources = await page.evaluate(() =>
      performance.getEntriesByType('resource').map((entry) => entry.name.toLowerCase()),
    );

    expect(lcp).toBeGreaterThan(0);
    expect(lcp).toBeLessThan(2500);
    expect(resources.some((name) => name.includes('tiptap') || name.includes('katex'))).toBe(false);
  });
});
