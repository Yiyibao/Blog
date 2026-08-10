import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { VitePWA } from 'vite-plugin-pwa';

export default defineConfig({
  plugins: [
    vue(),
    VitePWA({
      registerType: 'autoUpdate',
      includeManifestIcons: false,
      // NF-6：预缓存只收代码与字体/矢量资源；位图全部退出预缓存改运行时缓存
      includeAssets: [],
      manifest: {
        name: "日常拾光录 · hxnf's Memoir.",
        short_name: '日常拾光录',
        description: '拾起代码、阅读、料理与日常生活里的微光，记录思考，也珍藏时间。',
        theme_color: '#f7f3e9',
        background_color: '#fff9f8',
        display: 'standalone',
        // NF-6：真实方形图标（由 og 视觉中心裁切生成），不再用 2MB 非方形 og.png 冒充
        icons: [
          { src: 'icons/icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: 'icons/icon-512.png', sizes: '512x512', type: 'image/png' },
        ],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,woff,woff2,ttf,svg}'],
        globIgnores: ['assets/tiptap-*', 'assets/katex-*', 'assets/KaTeX_*', 'assets/code-highlight-*'],
        // NF-6：图片改运行时 CacheFirst——首次安装不再预下载 ~6MB 位图
        runtimeCaching: [
          {
            urlPattern: ({ url, request }) =>
              request.method === 'GET' &&
              /^\/api\/v1\/(posts|dishes|categories|dish-categories)(?:\/|$)/.test(url.pathname),
            handler: 'NetworkFirst',
            options: {
              cacheName: 'offline-reading-api',
              networkTimeoutSeconds: 3,
              expiration: { maxEntries: 120, maxAgeSeconds: 14 * 24 * 3600 },
              cacheableResponse: { statuses: [0, 200] },
            },
          },
          {
            urlPattern: ({ request }) => request.destination === 'image',
            handler: 'CacheFirst',
            options: {
              cacheName: 'images',
              expiration: { maxEntries: 60, maxAgeSeconds: 30 * 24 * 3600 },
            },
          },
        ],
        // 兜底：任何超 1MB 的产物都不得进入预缓存清单
        maximumFileSizeToCacheInBytes: 1024 * 1024,
      },
    }),
  ],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist/client',
    emptyOutDir: true,
    target: 'es2022',
    // L-3：map 照常生成但产物不引用（nginx 对 .map 返回 404）；线上排错时用本地 dist 的 map 对照
    sourcemap: 'hidden',
  },
});
