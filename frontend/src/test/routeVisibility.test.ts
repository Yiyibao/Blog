import router from '../router';
import { describe, expect, it } from 'vitest';

describe('M4 public route visibility contract', () => {
  it('marks sitemap-facing pages as public routes', () => {
    for (const path of ['/about', '/categories', '/categories/:slug', '/series', '/archive', '/tags/:tag']) {
      const route = router.getRoutes().find((candidate) => candidate.path === path);
      expect(route?.meta.visibility, path).toBe('public');
    }
  });

  it('keeps private entry points protected', () => {
    expect(router.getRoutes().find((route) => route.path === '/notes')?.meta.requiresAuth).toBe(true);
    expect(router.getRoutes().find((route) => route.path === '/admin')?.meta.requiresAuth).toBe(true);
    expect(router.getRoutes().find((route) => route.path === '/:pathMatch(.*)*')?.meta.visibility).toBe(
      'public',
    );
  });
});
