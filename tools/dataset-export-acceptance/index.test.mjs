import assert from 'node:assert/strict'
import test from 'node:test'
import path from 'node:path'

import {
  assertAcceptanceEnvironment,
  requireAvailableArtifact,
  selectEnvironmentFile,
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

test('artifact digest uses the registered SHA-256 representation', () => {
  assert.equal(
    sha256Hex(Buffer.from('synthetic dataset export artifact', 'utf8')),
    'fcb7f05c524cd49fc7a3bd8562e162c5ff8576b206864b9dfef37290cbcce541',
  )
})

test('local environment file takes precedence over the example configuration', () => {
  const repositoryRoot = 'synthetic-repository'
  assert.equal(
    selectEnvironmentFile(repositoryRoot, (candidate) => candidate.endsWith('.env')),
    path.join(repositoryRoot, '.env'),
  )
  assert.equal(
    selectEnvironmentFile(repositoryRoot, () => false),
    path.join(repositoryRoot, '.env.example'),
  )
})
