import { fileURLToPath, URL } from 'node:url'

import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

const workspaceRoot = fileURLToPath(new URL('../..', import.meta.url))

export default defineConfig(({ mode }) => {
  // Load the repository .env for the development proxy only. Values without
  // the VITE_ prefix remain unavailable to browser code. A fresh checkout
  // falls back to .env.example, matching the local backend script.
  const localEnvironment = loadEnv(mode, workspaceRoot, '')
  const exampleEnvironment = loadEnv('example', workspaceRoot, '')
  const environmentValue = (name: string) =>
    process.env[name]?.trim() || localEnvironment[name]?.trim() || exampleEnvironment[name]?.trim()

  const configuredWebPort = Number(environmentValue('WEB_PORT') || '5173')
  if (!Number.isInteger(configuredWebPort) || configuredWebPort < 1024 || configuredWebPort > 65535) {
    throw new Error('WEB_PORT must be an integer between 1024 and 65535.')
  }

  return {
    envDir: workspaceRoot,
    plugins: [react()],
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
        },
      },
    },
  }
})
