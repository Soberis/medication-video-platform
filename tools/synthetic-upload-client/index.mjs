import { spawn } from 'node:child_process'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

import { PlatformApiError, SyntheticUploadClient } from './upload-client.mjs'

function run(command, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ['ignore', 'ignore', 'pipe'], windowsHide: true })
    let stderr = ''
    child.stderr.setEncoding('utf8')
    child.stderr.on('data', (chunk) => {
      stderr += chunk
    })
    child.on('error', reject)
    child.on('exit', (code) => {
      if (code === 0) {
        resolve()
      } else {
        reject(new Error(`FFmpeg exited with code ${code}: ${stderr.trim()}`))
      }
    })
  })
}

async function generateSyntheticVideo(outputPath) {
  const ffmpeg = process.env.FFMPEG_PATH || 'ffmpeg'
  await run(ffmpeg, [
    '-hide_banner',
    '-loglevel',
    'error',
    '-y',
    '-f',
    'lavfi',
    '-i',
    'testsrc2=size=640x360:rate=25',
    '-f',
    'lavfi',
    '-i',
    'sine=frequency=880:sample_rate=48000',
    '-t',
    '2',
    '-c:v',
    'libx264',
    '-preset',
    'veryfast',
    '-pix_fmt',
    'yuv420p',
    '-c:a',
    'aac',
    '-shortest',
    '-movflags',
    '+faststart',
    '-metadata',
    'title=synthetic-medication-preparation-test-pattern',
    outputPath,
  ])
}

async function main() {
  if (process.argv.length > 2) {
    throw new Error('This client accepts no source-file arguments; it only generates synthetic media')
  }

  const bearerToken = process.env.DEV_BEARER_TOKEN
  if (!bearerToken) {
    throw new Error('Set DEV_BEARER_TOKEN to the local synthetic API token before running')
  }
  const baseUrl = process.env.PLATFORM_BASE_URL || 'http://localhost:8080'
  const projectId = process.env.DEV_PROJECT_ID || '00000000-0000-0000-0000-000000000001'
  const workDirectory = await mkdtemp(path.join(tmpdir(), 'medication-synthetic-upload-'))

  try {
    const videoPath = path.join(workDirectory, 'synthetic-medication-preparation.mp4')
    await generateSyntheticVideo(videoPath)
    const videoBytes = await readFile(videoPath)
    const client = new SyntheticUploadClient({ baseUrl, projectId, bearerToken })
    const result = await client.uploadSyntheticSession(videoBytes)
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
