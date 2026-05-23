import path from "path"
import fs from "fs"
import react from "@vitejs/plugin-react"
import { VitePWA } from "vite-plugin-pwa"
import { defineConfig, type Plugin } from "vite"

function removeCrossoriginPlugin(): Plugin {
  return {
    name: 'remove-crossorigin',
    enforce: 'post',
    closeBundle() {
      const htmlPath = path.resolve(__dirname, 'dist', 'index.html')
      const html = fs.readFileSync(htmlPath, 'utf-8')
      const cleaned = html.replace(/ crossorigin(?:="?(?:anonymous|use-credentials)?"?)?/gi, '')
      if (html !== cleaned) {
        fs.writeFileSync(htmlPath, cleaned, 'utf-8')
      }
    },
  }
}

export default defineConfig({
  base: '/',
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['favicon.svg', 'apple-touch-icon.png'],
      manifest: {
        name: 'KBook - 智能阅读平台',
        short_name: 'KBook',
        description: 'KBook 智能移动阅读平台',
        theme_color: '#6366f1',
        background_color: '#ffffff',
        display: 'standalone',
        orientation: 'portrait',
        start_url: '/',
        icons: [
          {
            src: '/icon-192.png',
            sizes: '192x192',
            type: 'image/png',
          },
          {
            src: '/icon-512.png',
            sizes: '512x512',
            type: 'image/png',
          },
          {
            src: '/icon-512.png',
            sizes: '512x512',
            type: 'image/png',
            purpose: 'maskable',
          },
        ],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,ico,png,svg,woff2}'],
        navigateFallback: '/index.html',
        navigationPreload: true,
        runtimeCaching: [
          {
          //   // 静态文件（头像、聊天图片、缩略图）不缓存，直连后端
          //   urlPattern: /^https:\/\/.*\/api\/uploads\/.*/i,
          //   handler: 'NetworkOnly',
          // },
          // {
            urlPattern: /^https:\/\/.*\/api\/.*/i,
            handler: 'NetworkFirst',
            options: {
              cacheName: 'api-cache',
              expiration: {
                maxEntries: 100,
                maxAgeSeconds: 60 * 5,
              },
              cacheableResponse: {
                statuses: [0, 200],
              },
            },
          },
        ],
      },
    }),
    removeCrossoriginPlugin(),
  ],
  define: {
    global: 'window',
  },
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  server: {
    host: '::',
    port: 15173,
    allowedHosts: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8181',
        changeOrigin: true,
        ws: true,
        // 开发环境超时配置（支持 AI 流式输出）
        timeout: 3600000,  // 3600 秒
        proxyTimeout: 3600000,
      },
    },
  },
})
