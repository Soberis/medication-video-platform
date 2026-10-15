import { existsSync } from 'node:fs'
import { createServer } from 'node:net'
import { dirname, join } from 'node:path'
import { fileURLToPath, URL } from 'node:url'

import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

function findWorkspaceRoot(start: string): string {
  let cur = start
  while (cur) {
    if (existsSync(join(cur, 'pnpm-workspace.yaml'))) return cur
    const parent = dirname(cur)
    if (parent === cur) break
    cur = parent
  }
  return start
}

const workspaceRoot = findWorkspaceRoot(fileURLToPath(import.meta.url))

async function isPortAvailable(port: number, host = '127.0.0.1'): Promise<boolean> {
  return new Promise((resolve) => {
    const server = createServer()
    server.once('error', () => resolve(false))
    server.once('listening', () => {
      server.close(() => resolve(true))
    })
    server.listen(port, host)
  })
}

async function resolveAvailableWebPort(preferredPort: number, host = '127.0.0.1'): Promise<number> {
  if (await isPortAvailable(preferredPort, host)) {
    return preferredPort
  }
  const candidatePorts = [3000, 3001, 5174, 5175, 8081, 8082]
  for (const candidate of candidatePorts) {
    if (candidate !== preferredPort && (await isPortAvailable(candidate, host))) {
      console.warn(
        `[vite] Port ${preferredPort} is unavailable on ${host} (likely reserved by Windows Hyper-V / EACCES or in use). Automatically falling back to port ${candidate}.`,
      )
      return candidate
    }
  }
  return preferredPort
}

export default defineConfig(async ({ mode }) => {
  // Load the repository .env for the development proxy only. Values without
  // the VITE_ prefix remain unavailable to browser code. A fresh checkout
  // falls back to .env.example, matching the local backend script.
  const localEnvironment = loadEnv(mode, workspaceRoot, '')
  const exampleEnvironment = loadEnv('example', workspaceRoot, '')
  const environmentValue = (name: string) =>
    process.env[name]?.trim() || localEnvironment[name]?.trim() || exampleEnvironment[name]?.trim()

  const configuredWebPort = Number(environmentValue('WEB_PORT') || '5173')
  if (
    !Number.isInteger(configuredWebPort) ||
    configuredWebPort < 1024 ||
    configuredWebPort > 65535
  ) {
    throw new Error('WEB_PORT must be an integer between 1024 and 65535.')
  }
  const effectiveWebPort = await resolveAvailableWebPort(configuredWebPort, '127.0.0.1')
  const configuredApiPort = Number(environmentValue('PLATFORM_API_PORT') || '8080')
  if (
    !Number.isInteger(configuredApiPort) ||
    configuredApiPort < 1024 ||
    configuredApiPort > 65535
  ) {
    throw new Error('PLATFORM_API_PORT must be an integer between 1024 and 65535.')
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
      port: effectiveWebPort,
      proxy: {
        '/api': {
          target: `http://127.0.0.1:${configuredApiPort}`,
          changeOrigin: true,
        },
      },
    },
  }
})
