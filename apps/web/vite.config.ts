import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

const configuredWebPort = Number(process.env.WEB_PORT?.trim() || '5173')
if (!Number.isInteger(configuredWebPort) || configuredWebPort < 1024 || configuredWebPort > 65535) {
  throw new Error('WEB_PORT must be an integer between 1024 and 65535.')
}

// Tokens remain in the development proxy. The browser can select only a synthetic role cookie.
const memberBearerToken = process.env.DEV_BEARER_TOKEN?.trim()
const adminBearerToken = process.env.DEV_ADMIN_BEARER_TOKEN?.trim()

function selectedDevelopmentRole(cookieHeader: string | undefined) {
  return cookieHeader?.split(';').some((value) => value.trim() === 'mvp_dev_role=admin')
    ? 'admin'
    : 'member'
}

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: '127.0.0.1',
    port: configuredWebPort,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyRequest, request) => {
            const role = selectedDevelopmentRole(request.headers.cookie)
            const token = role === 'admin' ? adminBearerToken : memberBearerToken
            if (token) proxyRequest.setHeader('Authorization', `Bearer ${token}`)
          })
        },
      },
    },
  },
})
