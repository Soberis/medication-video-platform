import assert from 'node:assert/strict'
import test from 'node:test'

import {
  assertAcceptanceEnvironment,
  matchesExpectedExportCompletion,
  mergeEnvironment,
  parseAcceptanceMode,
  requireAvailableArtifact,
  requireLeaseLossRecovery,
  requireMediaProbeJob,
  sha256Hex,
} from './index.mjs'

test('acceptance mode keeps the normal path explicit and rejects unknown options', () => {
  assert.deepEqual(parseAcceptanceMode([]), { recovery: false, leaseLoss: false })
  assert.deepEqual(parseAcceptanceMode(['--recovery']), { recovery: true, leaseLoss: false })
  assert.deepEqual(parseAcceptanceMode(['--lease-loss-recovery']), {
    recovery: true,
    leaseLoss: true,
  })
  assert.throws(() => parseAcceptanceMode(['--unexpected']), /Usage/)
  assert.throws(() => parseAcceptanceMode(['--recovery', '--lease-loss-recovery']), /Usage/)
})

test('acceptance environment requires explicit synthetic localhost authorization', () => {
  const environment = assertAcceptanceEnvironment({
    enabled: 'true',
    dataProfile: 'synthetic',
    baseUrl: 'http://localhost:8080/',
    rabbitmqUrl: 'amqp://user:password@localhost:5672/medication',
    adminBearerToken: 'synthetic-admin',
    uploaderBearerToken: 'synthetic-uploader',
  })

  assert.equal(environment.baseUrl, 'http://localhost:8080')
  assert.throws(
    () =>
      assertAcceptanceEnvironment({
        enabled: 'false',
        dataProfile: 'synthetic',
        baseUrl: 'http://localhost:8080',
        rabbitmqUrl: 'amqp://localhost:5672/medication',
        adminBearerToken: 'synthetic-admin',
        uploaderBearerToken: 'synthetic-uploader',
      }),
    /RUN_DATASET_EXPORT_ACCEPTANCE=true/,
  )
  assert.throws(
    () =>
      assertAcceptanceEnvironment({
        enabled: 'true',
        dataProfile: 'synthetic',
        baseUrl: 'https://platform.example.test',
        rabbitmqUrl: 'amqp://localhost:5672/medication',
        adminBearerToken: 'synthetic-admin',
        uploaderBearerToken: 'synthetic-uploader',
      }),
    /localhost Core API/,
  )
  assert.throws(
    () =>
      assertAcceptanceEnvironment({
        enabled: 'true',
        dataProfile: 'production',
        baseUrl: 'http://127.0.0.1:8080',
        rabbitmqUrl: 'amqp://localhost:5672/medication',
        adminBearerToken: 'synthetic-admin',
        uploaderBearerToken: 'synthetic-uploader',
      }),
    /DATA_PROFILE=synthetic/,
  )
  assert.throws(
    () =>
      assertAcceptanceEnvironment({
        enabled: 'true',
        dataProfile: 'synthetic',
        baseUrl: 'http://localhost:8080',
        rabbitmqUrl: 'amqp://rabbitmq.example.test:5672/medication',
        adminBearerToken: 'synthetic-admin',
        uploaderBearerToken: 'synthetic-uploader',
      }),
    /localhost RabbitMQ/,
  )
})

test('lease-loss acceptance requires an independent explicit authorization', () => {
  const common = {
    enabled: 'true',
    dataProfile: 'synthetic',
    baseUrl: 'http://localhost:8080',
    rabbitmqUrl: 'amqp://localhost:5672/medication',
    adminBearerToken: 'synthetic-admin',
    uploaderBearerToken: 'synthetic-uploader',
    leaseLoss: true,
  }

  assert.throws(
    () => assertAcceptanceEnvironment(common),
    /RUN_DATASET_EXPORT_LEASE_LOSS_ACCEPTANCE=true/,
  )
  assert.doesNotThrow(() =>
    assertAcceptanceEnvironment({
      ...common,
      leaseLossAuthorized: 'true',
    }),
  )
  assert.throws(
    () =>
      assertAcceptanceEnvironment({
        ...common,
        leaseLoss: false,
        leaseLossAuthorized: 'true',
      }),
    /--lease-loss-recovery/,
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
    () =>
      requireAvailableArtifact({
        status: 'AVAILABLE',
        artifact: { size_bytes: 0, sha256: 'not-a-sha256' },
      }),
    /valid artifact/,
  )
})

test('recovery acceptance requires RETRY_WAIT and a successful second attempt', () => {
  const completedExport = { status: 'AVAILABLE', attempt: { attempt: 2 } }

  assert.equal(
    matchesExpectedExportCompletion(completedExport, true, ['QUEUED', 'RETRY_WAIT']),
    true,
  )
  assert.throws(
    () => matchesExpectedExportCompletion(completedExport, true, ['QUEUED', 'AVAILABLE']),
    /did not observe RETRY_WAIT/,
  )
  assert.throws(
    () =>
      matchesExpectedExportCompletion({ status: 'AVAILABLE', attempt: { attempt: 1 } }, true, [
        'RETRY_WAIT',
      ]),
    /second Worker attempt/,
  )
})

test('lease-loss recovery requires a successful second attempt without relying on transient states', () => {
  const completedExport = { status: 'AVAILABLE', attempt: { attempt: 2 } }

  assert.equal(requireLeaseLossRecovery(completedExport), true)
  assert.throws(
    () => requireLeaseLossRecovery({ status: 'RUNNING', attempt: { attempt: 2 } }),
    /AVAILABLE/,
  )
  assert.throws(
    () => requireLeaseLossRecovery({ status: 'AVAILABLE', attempt: { attempt: 1 } }),
    /second Worker attempt/,
  )
})

test('media probe must start from an executable state', () => {
  const mediaJobId = requireMediaProbeJob({
    media_job_id: '1c7763bd-e4f1-4cb1-842a-8431b0cd2fd2',
    status: 'QUEUED',
  })

  assert.equal(mediaJobId, '1c7763bd-e4f1-4cb1-842a-8431b0cd2fd2')
  assert.throws(
    () =>
      requireMediaProbeJob({
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
    {
      DATA_PROFILE: 'synthetic',
      PLATFORM_BASE_URL: 'http://localhost:8080',
      DEV_BEARER_TOKEN: 'example',
    },
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
