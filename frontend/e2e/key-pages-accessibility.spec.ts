import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

const keyRoutes = [
  '/',
  '/articles',
  '/recipes',
  '/search',
  '/categories',
  '/archive',
  '/about',
  '/login',
  '/admin/login',
];

test.describe('key page WCAG 2.2 AA audit', () => {
  for (const route of keyRoutes) {
    test(`${route} has no serious or critical axe violations`, async ({ page }) => {
      await page.goto(route, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(250);

      const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze();
      const severe = results.violations.filter((violation) =>
        ['serious', 'critical'].includes(violation.impact ?? ''),
      );
      expect(severe, `${route}: ${JSON.stringify(severe)}`).toEqual([]);

      await page.keyboard.press('Tab');
      await expect(page.locator(':focus')).toHaveCount(1);
    });
  }
});
