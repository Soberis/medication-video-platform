import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

const configuredWebPort = Number(process.env.WEB_PORT?.trim() || '5173')
if (!Number.isInteger(configuredWebPort) || configuredWebPort < 1024 || configuredWebPort > 65535) {
  throw new Error('WEB_PORT must be an integer between 1024 and 65535.')
}

// This value is read only by the Vite development proxy. It is never exposed to browser code.
const devBearerToken =
  process.env.DEV_WEB_BEARER_TOKEN?.trim() || process.env.DEV_BEARER_TOKEN?.trim()

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
        ...(devBearerToken
          ? {
              headers: {
                Authorization: `Bearer ${devBearerToken}`,
              },
            }
          : {}),
      },
    },
  },
})
