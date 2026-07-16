import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

const devBearerToken = process.env.DEV_BEARER_TOKEN?.trim()

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: '127.0.0.1',
    port: 4173,
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
