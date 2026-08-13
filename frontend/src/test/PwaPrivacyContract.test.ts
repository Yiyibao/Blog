import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';

describe('M11 PWA private response cache boundary', () => {
  it('caches only public content API routes and excludes private kitchen/admin/AI data', () => {
    const source = readFileSync('vite.config.ts', 'utf8');
    const runtimeCaching = source.slice(
      source.indexOf('runtimeCaching'),
      source.indexOf('maximumFileSizeToCacheInBytes'),
    );
    expect(runtimeCaching).toContain('(posts|dishes|categories|dish-categories)');
    expect(runtimeCaching).not.toMatch(/(?:auth|admin|notes|kitchen|ai)\b/i);
    expect(runtimeCaching).toContain("request.method === 'GET'");
  });
});
