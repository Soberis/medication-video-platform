import { fileURLToPath, URL } from 'node:url'

import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'jsdom',
    exclude: ['**/node_modules/**', '**/dist/**', '**/.git/**', 'e2e/**'],
    maxWorkers: 2,
    root: fileURLToPath(new URL('.', import.meta.url)),
    setupFiles: ['./src/test/setup.ts'],
    testTimeout: 20_000,
  },
})
