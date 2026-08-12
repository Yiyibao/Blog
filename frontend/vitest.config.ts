import { defineConfig } from 'vitest/config';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    setupFiles: ['src/test/setup.ts'],
    globals: true,
    exclude: ['e2e/**', 'node_modules/**', 'dist/**'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'lcov'],
      include: ['src/**/*.{ts,vue}'],
      exclude: ['src/main.ts', 'src/**/*.d.ts', 'src/test/**'],
      // M4 baseline: 2026-08-13 local run (61.81/55.49/50.71/64.44).
      // Keep the floor above the pre-M4 45/45/45/35 gate; future work may only raise it.
      thresholds: { lines: 64, functions: 50, statements: 61, branches: 55 },
    },
  },
});
