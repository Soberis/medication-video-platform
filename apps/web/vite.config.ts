import { existsSync } from 'node:fs'
import type { ServerResponse } from 'node:http'
import { createServer } from 'node:net'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL, URL } from 'node:url'

import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv, type Connect, type ViteDevServer } from 'vite'

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
    plugins: [
      react(),
      {
        name: 'dev-clean-video-api',
        configureServer(server: ViteDevServer) {
          const toolModule = pathToFileURL(resolve(workspaceRoot, 'tools/clean-invalid-videos.mjs')).href

          function readJson<T = unknown>(req: Connect.IncomingMessage): Promise<T> {
            return new Promise((resolve, reject) => {
              let body = ''
              req.on('data', (chunk: Buffer | string) => {
                body += chunk
              })
              req.on('end', () => {
                try {
                  resolve(body ? JSON.parse(body) : ({} as T))
                } catch (err) {
                  reject(err)
                }
              })
              req.on('error', reject)
            })
          }

          function sendJson(res: ServerResponse, data: unknown, status = 200) {
            res.statusCode = status
            res.setHeader('Content-Type', 'application/json')
            res.end(JSON.stringify(data))
          }

          server.middlewares.use('/api/dev/pipeline-status', async (req: Connect.IncomingMessage, res: ServerResponse) => {
            if (req.method !== 'GET') {
              res.statusCode = 405
              res.end()
              return
            }
            try {
              const url = new URL(req.url ?? '', `http://${req.headers.host || 'localhost'}`)
              const projectId = url.searchParams.get('projectId') || undefined
              const { getCaptureGroupsPipelineStatus } = await import(/* @vite-ignore */ toolModule)
              sendJson(res, { ok: true, ...getCaptureGroupsPipelineStatus(projectId) })
            } catch (err) {
              sendJson(res, { ok: false, error: String(err) }, 500)
            }
          })

          server.middlewares.use('/api/dev/clean-invalid-videos', async (req: Connect.IncomingMessage, res: ServerResponse) => {
            if (req.method !== 'POST') {
              res.statusCode = 405
              res.end()
              return
            }
            try {
              const parsed = await readJson<Record<string, unknown>>(req)
              const { cleanInvalidCaptureGroups } = await import(/* @vite-ignore */ toolModule)
              sendJson(res, { ok: true, ...cleanInvalidCaptureGroups(parsed) })
            } catch (err) {
              sendJson(res, { ok: false, error: String(err) }, 500)
            }
          })

          server.middlewares.use('/api/dev/clean-capture-group', async (req: Connect.IncomingMessage, res: ServerResponse) => {
            if (req.method !== 'POST') {
              res.statusCode = 405
              res.end()
              return
            }
            try {
              const { captureGroupId } = await readJson<{ captureGroupId: string }>(req)
              const { deleteCaptureGroup } = await import(/* @vite-ignore */ toolModule)
              deleteCaptureGroup(captureGroupId)
              sendJson(res, { ok: true, captureGroupId })
            } catch (err) {
              sendJson(res, { ok: false, error: String(err) }, 500)
            }
          })

          server.middlewares.use('/api/dev/retry-hand-pose', async (req: Connect.IncomingMessage, res: ServerResponse) => {
            if (req.method !== 'POST') {
              res.statusCode = 405
              res.end()
              return
            }
            try {
              const { clipAssetId, projectId } = await readJson<{ clipAssetId: string; projectId?: string }>(req)
              const { retryHandPoseJob } = await import(/* @vite-ignore */ toolModule)
              sendJson(res, { ok: true, ...(await retryHandPoseJob(clipAssetId, projectId)) })
            } catch (err) {
              sendJson(res, { ok: false, error: String(err) }, 500)
            }
          })

          server.middlewares.use('/api/dev/retry-failed-hand-poses', async (req: Connect.IncomingMessage, res: ServerResponse) => {
            if (req.method !== 'POST') {
              res.statusCode = 405
              res.end()
              return
            }
            try {
              const parsed = await readJson<{ limit?: number; projectId?: string }>(req)
              const { retryFailedHandPoseBatch } = await import(/* @vite-ignore */ toolModule)
              sendJson(res, { ok: true, ...(await retryFailedHandPoseBatch(parsed.limit || 50, parsed.projectId)) })
            } catch (err) {
              sendJson(res, { ok: false, error: String(err) }, 500)
            }
          })
        },
      },
    ],
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
