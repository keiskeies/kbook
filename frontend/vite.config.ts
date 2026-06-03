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
        description: 'KBook 智能阅读平台',
        theme_color: '#6366f1',
        background_color: '#ffffff',
        display: 'standalone',
        orientation: 'any',
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
          // 所有后端接口均不通过 SW 缓存，保证数据实时性
          // 接口分为以下几类，全部走 NetworkOnly：
          // 1. 认证接口 /api/auth/* —— 含 Token/密码，不能缓存
          // 2. 用户私密数据 /api/user/*, /api/bookshelf/*, /api/progress/* —— 用户私有，不能缓存
          // 3. 私信聊天 /api/chat/* —— 私密数据，不能缓存
          // 4. 通知 /api/user/notifications/* —— 用户私有，不能缓存
          // 5. AI 对话 /api/ai/* —— 含 SSE 流式和用户历史，不能缓存
          // 6. 推荐 /api/recommend/* —— 用户个性化数据，不能缓存
          // 7. 图书文件 /api/books/{id}/file, /api/books/{id}/text-info —— Range 请求，后端已处理
          // 8. 验证码 /api/captcha/* —— 一次性数据，不能缓存
          // 9. 公开图书数据（封面、详情、排行榜等）—— 浏览器原生缓存足矣，SW 不重复缓存
          {
            urlPattern: /^https?:\/\/.*\/api\/.*/i,
            handler: 'NetworkOnly',
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
