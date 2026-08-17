// V1.21 final acceptance E2E only: upload the approved local hand-pose verification
// video through the real upload pipeline (same flow as index.mjs, but with provided
// bytes instead of a generated testsrc2 clip). Local synthetic environment only.
import { readFile } from 'node:fs/promises'
import { PlatformApiError, SyntheticUploadClient } from './upload-client.mjs'

async function main() {
  const bearerToken = process.env.DEV_BEARER_TOKEN
  const adminBearerToken = process.env.DEV_ADMIN_BEARER_TOKEN
  const videoPath = process.env.E2E_VIDEO_PATH
  if (!bearerToken || !adminBearerToken || !videoPath) {
    throw new Error('Set DEV_BEARER_TOKEN, DEV_ADMIN_BEARER_TOKEN and E2E_VIDEO_PATH')
  }
  const baseUrl = process.env.PLATFORM_BASE_URL || 'http://localhost:8080'
  const projectId = process.env.DEV_PROJECT_ID || '00000000-0000-0000-0000-000000000001'

  const videoBytes = await readFile(videoPath)
  const administrator = new SyntheticUploadClient({ baseUrl, projectId, bearerToken: adminBearerToken })
  const camera = await administrator.provisionSyntheticCamera()
  const uploader = new SyntheticUploadClient({ baseUrl, projectId, bearerToken })
  const result = await uploader.uploadSyntheticSession(videoBytes, camera)
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
}

main().catch((error) => {
  if (error instanceof PlatformApiError) {
    const request = error.requestId ? ` request_id=${error.requestId}` : ''
    process.stderr.write(`${error.message}${request}\n`)
  } else {
    process.stderr.write(`${error.message}\n`)
  }
  process.exitCode = 1
})
