import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { VitePWA } from 'vite-plugin-pwa'

export default defineConfig({
  plugins: [
    vue(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['og.png'],
      manifest: {
        name: '余白 · 个人博客',
        short_name: '余白',
        description: '记录代码、设计与日常生活的个人博客',
        theme_color: '#f7f3e9',
        background_color: '#fff9f8',
        display: 'standalone',
        icons: [
          { src: 'og.png', sizes: '192x192', type: 'image/png' },
          { src: 'og.png', sizes: '512x512', type: 'image/png' },
        ],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,woff,woff2,ttf,svg,png,jpg,jpeg,gif}'],
        globIgnores: ['**/og.png'],
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
    sourcemap: true,
  },
})
