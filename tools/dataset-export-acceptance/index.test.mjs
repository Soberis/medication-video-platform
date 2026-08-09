import assert from 'node:assert/strict'
import test from 'node:test'

import {
  assertAcceptanceEnvironment,
  assertDlqPrepareEnvironment,
  assertDlqReplayEnvironment,
  buildDatasetExportReplayRequest,
  matchesExpectedExportCompletion,
  mergeEnvironment,
  parseAcceptanceMode,
  requireActorIdentity,
  requireDatasetExportDeadLetterDiagnostic,
  requireDatasetExportDlqReplayReason,
  requireDatasetExportReplayPreflight,
  requireDatasetExportReplayCompletion,
  requireDatasetExportReplayResponse,
  requireAvailableArtifact,
  requireLeaseLossRecovery,
  requireMemberPermissionDenied,
  requireMediaProbeJob,
  requireUnchangedState,
  sha256Hex,
  uuidV5,
} from './index.mjs'

test('acceptance mode keeps the normal path explicit and rejects unknown options', () => {
  assert.deepEqual(parseAcceptanceMode([]), { recovery: false, leaseLoss: false })
  assert.deepEqual(parseAcceptanceMode(['--recovery']), { recovery: true, leaseLoss: false })
  assert.deepEqual(parseAcceptanceMode(['--lease-loss-recovery']), {
    recovery: true,
    leaseLoss: true,
  })
  assert.deepEqual(parseAcceptanceMode(['--dlq-replay']), {
    recovery: false,
    leaseLoss: false,
    dlqReplay: true,
  })
  assert.deepEqual(parseAcceptanceMode(['--dlq-prepare']), {
    recovery: false,
    leaseLoss: false,
    dlqPrepare: true,
  })
  assert.throws(() => parseAcceptanceMode(['--unexpected']), /Usage/)
  assert.throws(() => parseAcceptanceMode(['--recovery', '--lease-loss-recovery']), /Usage/)
})

test('DLQ preparation requires both acceptance authorizations, loopback credentials, and bootstrap project context', () => {
  const common = {
    enabled: 'true',
    normalAcceptanceEnabled: 'true',
    dataProfile: 'synthetic',
    baseUrl: 'http://localhost:8080',
    rabbitmqUrl: 'amqp://localhost:5672/medication',
    adminBearerToken: 'synthetic-admin',
    uploaderBearerToken: 'synthetic-uploader',
    projectId: '00000000-0000-4000-8000-000000000001',
  }

  assert.equal(assertDlqPrepareEnvironment(common).baseUrl, 'http://localhost:8080')
  assert.equal(assertDlqPrepareEnvironment(common).projectId, common.projectId)
  assert.equal(
    assertDlqPrepareEnvironment({ ...common, projectId: '00000000-0000-0000-0000-000000000001' }).projectId,
    '00000000-0000-0000-0000-000000000001',
  )
  assert.throws(
    () => assertDlqPrepareEnvironment({ ...common, enabled: 'false' }),
    /RUN_DATASET_EXPORT_DLQ_REPLAY_ACCEPTANCE=true/,
  )
  assert.throws(
    () => assertDlqPrepareEnvironment({ ...common, normalAcceptanceEnabled: 'false' }),
    /RUN_DATASET_EXPORT_ACCEPTANCE=true/,
  )
  assert.throws(
    () => assertDlqPrepareEnvironment({ ...common, uploaderBearerToken: undefined }),
    /DEV_ADMIN_BEARER_TOKEN and DEV_BEARER_TOKEN/,
  )
  assert.throws(
    () => assertDlqPrepareEnvironment({ ...common, baseUrl: 'http://platform:8080' }),
    /localhost Core API/,
  )
  assert.throws(() => assertDlqPrepareEnvironment({ ...common, projectId: undefined }), /DEV_PROJECT_ID/)
  assert.throws(() => assertDlqPrepareEnvironment({ ...common, projectId: 'not-a-uuid' }), /DEV_PROJECT_ID/)
})

test('DLQ replay authorization is independent, synthetic, and loopback-only', () => {
  const common = {
    enabled: 'true',
    normalAcceptanceEnabled: 'true',
    dataProfile: 'synthetic',
    baseUrl: 'http://localhost:8080',
    rabbitmqUrl: 'amqp://medication_local:password@localhost:5672/medication',
    rabbitmqManagementUrl: 'http://127.0.0.1:15672',
    rabbitmqManagementUsername: 'medication_local',
    rabbitmqManagementPassword: 'change-me-local-only',
    adminBearerToken: 'synthetic-admin',
    projectId: '00000000-0000-4000-8000-000000000001',
    datasetVersionId: '00000000-0000-4000-8000-000000000002',
    exportId: '00000000-0000-4000-8000-000000000003',
    operatorReasonCode: 'CONFIGURATION_FIXED',
    changeReference: 'change-ref-0001',
    idempotencyKey: 'dataset-export-dlq-replay-0001',
  }

  assert.equal(
    assertDlqReplayEnvironment(common).deadLetterQueue,
    'platform.dataset-export.dead-letter.v1',
  )
  assert.doesNotThrow(() =>
    assertDlqReplayEnvironment({
      ...common,
      projectId: '00000000-0000-0000-0000-000000000001',
    }),
  )
  assert.throws(
    () => assertDlqReplayEnvironment({ ...common, enabled: 'false' }),
    /RUN_DATASET_EXPORT_DLQ_REPLAY_ACCEPTANCE=true/,
  )
  assert.throws(
    () => assertDlqReplayEnvironment({ ...common, normalAcceptanceEnabled: 'false' }),
    /RUN_DATASET_EXPORT_ACCEPTANCE=true/,
  )
  assert.throws(
    () => assertDlqReplayEnvironment({ ...common, rabbitmqManagementUrl: 'http://rabbitmq:15672' }),
    /localhost\/loopback/,
  )
  assert.throws(
    () => assertDlqReplayEnvironment({ ...common, operatorReasonCode: 'UNAPPROVED' }),
    /REASON_CODE/,
  )
  assert.throws(
    () => assertDlqReplayEnvironment({ ...common, idempotencyKey: 'too-short' }),
    /16,128/,
  )
  assert.throws(
    () => assertDlqReplayEnvironment({ ...common, exportId: undefined }),
    /DLQ replay export/,
  )
  assert.throws(
    () => assertDlqReplayEnvironment({ ...common, idempotencyKey: 'invalid/key-0001' }),
    /16,128/,
  )
  assert.doesNotThrow(() =>
    assertDlqReplayEnvironment({ ...common, idempotencyKey: 'a'.repeat(16) }),
  )
})

test('DLQ diagnostic is strictly validated and derives the Worker UUIDv5 identity', () => {
  const reasonCode = 'DATASET_EXPORT_JOB_ACQUIRE_REJECTED'
  const bodySha256 = 'd'.repeat(64)
  const deadLetterId = uuidV5(
    '6ba7b811-9dad-11d1-80b4-00c04fd430c8',
    `dataset-export-dead-letter.v1:${reasonCode}:${bodySha256}`,
  )
  const diagnostic = {
    schema_version: 'dataset-export-dead-letter.v1',
    dead_letter_id: deadLetterId,
    observed_at: '2026-08-03T09:00:00Z',
    reason_code: reasonCode,
    safe_summary: 'Worker authorization or export lease was rejected',
    body_sha256: bodySha256,
    body_size_bytes: 512,
    message_id: '00000000-0000-4000-8000-000000000003',
    export_id: '00000000-0000-4000-8000-000000000004',
    attempt: 1,
  }
  const safe = requireDatasetExportDeadLetterDiagnostic({
    exchange: 'platform.dead-letter.v1',
    routing_key: 'dataset-export.dead-letter.v1',
    properties: {
      type: 'dataset-export-dead-letter.v1',
      content_type: 'application/json',
      message_id: deadLetterId,
    },
    payload: JSON.stringify(diagnostic),
    message_count: 0,
  })
  assert.deepEqual(safe, {
    deadLetterId,
    reasonCode,
    bodySha256,
    sourceMessageId: diagnostic.message_id,
    exportId: diagnostic.export_id,
    sourceAttempt: 1,
  })
  assert.throws(
    () =>
      requireDatasetExportDeadLetterDiagnostic({
        exchange: 'platform.dead-letter.v1',
        routing_key: 'dataset-export.dead-letter.v1',
        properties: {
          type: 'dataset-export-dead-letter.v1',
          content_type: 'application/json',
          message_id: deadLetterId,
        },
        payload: JSON.stringify({ ...diagnostic, lease_token: 'must-not-appear' }),
        message_count: 0,
      }),
    /forbidden or unknown field/,
  )
  assert.throws(
    () =>
      requireDatasetExportDeadLetterDiagnostic({
        exchange: 'platform.dead-letter.v1',
        routing_key: 'dataset-export.dead-letter.v1',
        properties: {
          type: 'dataset-export-dead-letter.v1',
          content_type: 'application/json',
          message_id: deadLetterId,
        },
        payload: JSON.stringify({ ...diagnostic, safe_summary: 'contains no job body' }),
        message_count: 0,
      }),
    /safe_summary does not match/,
  )
  assert.throws(
    () =>
      requireDatasetExportDeadLetterDiagnostic({
        exchange: 'platform.dead-letter.v1',
        routing_key: 'dataset-export.dead-letter.v1',
        properties: {
          type: 'dataset-export-dead-letter.v1',
          content_type: 'application/json',
          message_id: deadLetterId,
        },
        payload: JSON.stringify({ ...diagnostic, dead_letter_id: diagnostic.message_id }),
        message_count: 0,
      }),
    /UUIDv5 identity/,
  )
})

test('DLQ replay request copies only safe diagnostic fields and verifies the next attempt', () => {
  const diagnostic = {
    deadLetterId: '00000000-0000-4000-8000-000000000005',
    reasonCode: 'DATASET_EXPORT_JOB_LEASE_REJECTED',
    bodySha256: 'e'.repeat(64),
    sourceMessageId: '00000000-0000-4000-8000-000000000006',
    exportId: '00000000-0000-4000-8000-000000000007',
    sourceAttempt: 1,
  }
  const request = buildDatasetExportReplayRequest(
    diagnostic,
    'WORKER_DEPLOYMENT_FIXED',
    'deploy-2026-08-03',
  )
  assert.deepEqual(
    Object.keys(request).sort(),
    [
      'body_sha256',
      'change_reference',
      'dead_letter_id',
      'dead_letter_reason',
      'operator_reason_code',
      'source_attempt',
      'source_message_id',
    ].sort(),
  )
  assert.equal(Object.hasOwn(request, 'lease_token'), false)
  const response = requireDatasetExportReplayResponse(
    {
      replay_id: '00000000-0000-4000-8000-000000000008',
      dataset_export_id: diagnostic.exportId,
      dead_letter_id: diagnostic.deadLetterId,
      source_attempt: 1,
      new_attempt: 2,
      resulting_export_status: 'QUEUED',
      created_at: '2026-08-03T09:00:00Z',
    },
    diagnostic,
  )
  assert.equal(response.new_attempt, 2)
  assert.throws(
    () => requireDatasetExportReplayResponse({ ...response, new_attempt: 3 }, diagnostic),
    /next attempt/,
  )
})

test('DLQ replay acceptance fixes the injected acquire-rejected reason', () => {
  assert.equal(
    requireDatasetExportDlqReplayReason('DATASET_EXPORT_JOB_ACQUIRE_REJECTED'),
    'DATASET_EXPORT_JOB_ACQUIRE_REJECTED',
  )
  assert.throws(
    () => requireDatasetExportDlqReplayReason('DATASET_EXPORT_JOB_LEASE_REJECTED'),
    /requires DATASET_EXPORT_JOB_ACQUIRE_REJECTED/,
  )
})

test('DLQ replay preflight binds the operator supplied export to its first active attempt', () => {
  const exportId = '00000000-0000-4000-8000-000000000016'
  const response = {
    export_request: {
      dataset_export_id: exportId,
      status: 'QUEUED',
      attempt: { attempt: 1, status: 'QUEUED' },
      artifact: null,
    },
  }
  assert.equal(requireDatasetExportReplayPreflight(response, exportId).status, 'QUEUED')
  assert.throws(
    () => requireDatasetExportReplayPreflight(response, '00000000-0000-4000-8000-000000000017'),
    /does not match/,
  )
  assert.throws(
    () =>
      requireDatasetExportReplayPreflight(
        { export_request: { ...response.export_request, status: 'AVAILABLE' } },
        exportId,
      ),
    /queued or running export/,
  )
  assert.throws(
    () =>
      requireDatasetExportReplayPreflight(
        {
          export_request: { ...response.export_request, attempt: { attempt: 2, status: 'QUEUED' } },
        },
        exportId,
      ),
    /first Worker attempt/,
  )
})

test('DLQ replay completion requires attempt two and exactly one valid artifact', () => {
  const exportId = '00000000-0000-4000-8000-000000000009'
  const response = {
    export_request: {
      dataset_export_id: exportId,
      status: 'AVAILABLE',
      attempt: { attempt: 2, status: 'SUCCEEDED' },
      artifact: { size_bytes: 1024, sha256: 'f'.repeat(64) },
    },
  }
  const result = requireDatasetExportReplayCompletion(response, exportId, 2)
  assert.equal(result.artifact.size_bytes, 1024)
  assert.equal(
    requireDatasetExportReplayCompletion(
      { export_request: { ...response.export_request, status: 'RUNNING' } },
      exportId,
      2,
    ),
    false,
  )
  assert.throws(
    () =>
      requireDatasetExportReplayCompletion(
        { export_request: { ...response.export_request, artifact: null } },
        exportId,
        2,
      ),
    /valid artifact/,
  )
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

test('V1.11 acceptance evidence binds self-admin facts and rejected member commands', () => {
  const administratorActorId = 'local-synthetic-admin'
  assert.equal(
    requireActorIdentity(
      administratorActorId,
      administratorActorId,
      'Synthetic annotation review',
    ),
    administratorActorId,
  )
  assert.throws(
    () =>
      requireActorIdentity(
        'local-synthetic-uploader',
        administratorActorId,
        'Synthetic annotation review',
      ),
    /expected administrator/,
  )

  assert.equal(
    requireMemberPermissionDenied(
      { status: 403, errorCode: 'PROJECT_ACCESS_DENIED' },
      'ordinary member annotation review',
    ),
    true,
  )
  assert.throws(
    () =>
      requireMemberPermissionDenied(
        { status: 409, errorCode: 'DATASET_VERSION_FREEZE_STATUS_INVALID' },
        'ordinary member dataset freeze',
      ),
    /HTTP 403 \(PROJECT_ACCESS_DENIED\)/,
  )

  const pending = {
    status: 'PENDING',
    requested_by: administratorActorId,
    approved_by: null,
  }
  assert.equal(requireUnchangedState(pending, { ...pending }, 'Dataset freeze'), true)
  assert.throws(
    () =>
      requireUnchangedState(
        pending,
        { ...pending, status: 'APPROVED', approved_by: administratorActorId },
        'Dataset freeze',
      ),
    /changed state/,
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
