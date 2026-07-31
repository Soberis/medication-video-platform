import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

import { PlatformApiError, SyntheticUploadClient } from './upload-client.mjs'
import { generateSyntheticVideo } from './synthetic-video.mjs'

async function main() {
  if (process.argv.length > 2) {
    throw new Error('This client accepts no source-file arguments; it only generates synthetic media')
  }

  const bearerToken = process.env.DEV_BEARER_TOKEN
  if (!bearerToken) {
    throw new Error('Set DEV_BEARER_TOKEN to the local synthetic API token before running')
  }
  const adminBearerToken = process.env.DEV_ADMIN_BEARER_TOKEN
  if (!adminBearerToken) {
    throw new Error('Set DEV_ADMIN_BEARER_TOKEN to configure the local synthetic camera before running')
  }
  const baseUrl = process.env.PLATFORM_BASE_URL || 'http://localhost:8080'
  const projectId = process.env.DEV_PROJECT_ID || '00000000-0000-0000-0000-000000000001'
  const workDirectory = await mkdtemp(path.join(tmpdir(), 'medication-synthetic-upload-'))

  try {
    const videoPath = path.join(workDirectory, 'synthetic-medication-preparation.mp4')
    await generateSyntheticVideo(videoPath)
    const videoBytes = await readFile(videoPath)
    const administrator = new SyntheticUploadClient({ baseUrl, projectId, bearerToken: adminBearerToken })
    const camera = await administrator.provisionSyntheticCamera()
    const uploader = new SyntheticUploadClient({ baseUrl, projectId, bearerToken })
    const result = await uploader.uploadSyntheticSession(videoBytes, camera)
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
  } finally {
    await rm(workDirectory, { recursive: true, force: true })
  }
}

const isEntryPoint = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
if (isEntryPoint) {
  main().catch((error) => {
    if (error instanceof PlatformApiError) {
      const request = error.requestId ? ` request_id=${error.requestId}` : ''
      process.stderr.write(`${error.message}${request}\n`)
    } else {
      process.stderr.write(`${error.message}\n`)
    }
    process.exitCode = 1
  })
}
