import assert from 'node:assert/strict'
import test from 'node:test'

import {
  assertAcceptanceEnvironment,
  mergeEnvironment,
  requireAvailableArtifact,
  requireMediaProbeJob,
  sha256Hex,
} from './index.mjs'

test('acceptance environment requires explicit synthetic localhost authorization', () => {
  const environment = assertAcceptanceEnvironment({
    enabled: 'true',
    dataProfile: 'synthetic',
    baseUrl: 'http://localhost:8080/',
    adminBearerToken: 'synthetic-admin',
    uploaderBearerToken: 'synthetic-uploader',
  })

  assert.equal(environment.baseUrl, 'http://localhost:8080')
  assert.throws(
    () => assertAcceptanceEnvironment({
      enabled: 'false',
      dataProfile: 'synthetic',
      baseUrl: 'http://localhost:8080',
      adminBearerToken: 'synthetic-admin',
      uploaderBearerToken: 'synthetic-uploader',
    }),
    /RUN_DATASET_EXPORT_ACCEPTANCE=true/,
  )
  assert.throws(
    () => assertAcceptanceEnvironment({
      enabled: 'true',
      dataProfile: 'synthetic',
      baseUrl: 'https://platform.example.test',
      adminBearerToken: 'synthetic-admin',
      uploaderBearerToken: 'synthetic-uploader',
    }),
    /localhost Core API/,
  )
  assert.throws(
    () => assertAcceptanceEnvironment({
      enabled: 'true',
      dataProfile: 'production',
      baseUrl: 'http://127.0.0.1:8080',
      adminBearerToken: 'synthetic-admin',
      uploaderBearerToken: 'synthetic-uploader',
    }),
    /DATA_PROFILE=synthetic/,
  )
})

test('available export must expose one valid artifact', () => {
  const artifact = requireAvailableArtifact({
    status: 'AVAILABLE',
    artifact: {
      size_bytes: 2048,
      sha256: 'a'.repeat(64),
    },
  })

  assert.deepEqual(artifact, { size_bytes: 2048, sha256: 'a'.repeat(64) })
  assert.throws(() => requireAvailableArtifact({ status: 'RUNNING' }), /AVAILABLE/)
  assert.throws(
    () => requireAvailableArtifact({
      status: 'AVAILABLE',
      artifact: { size_bytes: 0, sha256: 'not-a-sha256' },
    }),
    /valid artifact/,
  )
})

test('media probe must start from an executable state', () => {
  const mediaJobId = requireMediaProbeJob({
    media_job_id: '1c7763bd-e4f1-4cb1-842a-8431b0cd2fd2',
    status: 'QUEUED',
  })

  assert.equal(mediaJobId, '1c7763bd-e4f1-4cb1-842a-8431b0cd2fd2')
  assert.throws(
    () => requireMediaProbeJob({
      media_job_id: '1c7763bd-e4f1-4cb1-842a-8431b0cd2fd2',
      status: 'FAILED',
    }),
    /executable state/,
  )
})

test('artifact digest uses the registered SHA-256 representation', () => {
  assert.equal(
    sha256Hex(Buffer.from('synthetic dataset export artifact', 'utf8')),
    'fcb7f05c524cd49fc7a3bd8562e162c5ff8576b206864b9dfef37290cbcce541',
  )
})

test('explicit and local configuration override example defaults deterministically', () => {
  const environment = mergeEnvironment(
    { DATA_PROFILE: 'synthetic', PLATFORM_BASE_URL: 'http://localhost:8080', DEV_BEARER_TOKEN: 'example' },
    { DEV_BEARER_TOKEN: 'local', LOCAL_ONLY: 'true' },
    { DEV_BEARER_TOKEN: 'explicit', RUN_DATASET_EXPORT_ACCEPTANCE: 'true' },
  )

  assert.deepEqual(environment, {
    DATA_PROFILE: 'synthetic',
    PLATFORM_BASE_URL: 'http://localhost:8080',
    DEV_BEARER_TOKEN: 'explicit',
    LOCAL_ONLY: 'true',
    RUN_DATASET_EXPORT_ACCEPTANCE: 'true',
  })
})
