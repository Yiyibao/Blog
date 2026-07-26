import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { VitePWA } from 'vite-plugin-pwa'

export default defineConfig({
  plugins: [
    vue(),
    VitePWA({
      registerType: 'autoUpdate',
      // NF-6：预缓存只收代码与字体/矢量资源；位图全部退出预缓存改运行时缓存
      includeAssets: [],
      manifest: {
        name: '余白 · 个人博客',
        short_name: '余白',
        description: '记录代码、设计与日常生活的个人博客',
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
        // NF-6：图片改运行时 CacheFirst——首次安装不再预下载 ~6MB 位图
        runtimeCaching: [
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
    rollupOptions: {
      output: {
        // P1-7：编辑器/公式库单独分块，公开首屏 chunk 不被拖大；仅相关路由按需加载
        manualChunks(id: string) {
          if (id.includes('node_modules/katex')) return 'katex'
          if (id.includes('node_modules/@tiptap') || id.includes('node_modules/prosemirror')) return 'tiptap'
          return undefined
        },
      },
    },
  },
})
