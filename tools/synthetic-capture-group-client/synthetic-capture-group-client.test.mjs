import assert from 'node:assert/strict'
import { test } from 'node:test'

import {
  assertCaptureGroupIdentity,
  assertCleaningResult,
  assertReviewContext,
} from './assertions.mjs'
import { parseArgs } from './index.mjs'
import { buildScenarioFixture, SCENARIO_NAMES } from './scenarios.mjs'
import { createSyntheticEventLog, createSyntheticGroupMetadata, normalizeCameraNumbers, SyntheticUploadClient } from '../synthetic-upload-client/upload-client.mjs'
import { SyntheticCaptureGroupWorkflowClient } from './workflow-client.mjs'

test('scenario catalog contains explicit N=1, N=2, N=5, missing and gate cases', () => {
  for (const name of ['n1-complete', 'n2-complete', 'n5-complete', 'n5-missing-camera', 'n5-rejected-view']) {
    assert.ok(SCENARIO_NAMES.includes(name))
  }
  const n1 = buildScenarioFixture('n1-complete', 'fast')
  const n2 = buildScenarioFixture('n2-complete', 'fast')
  const n5 = buildScenarioFixture('n5-complete', 'fast')
  assert.deepEqual(n1.expectedCameraNumbers, [1])
  assert.deepEqual(n2.expectedCameraNumbers, [1, 2])
  assert.deepEqual(n5.expectedCameraNumbers, [1, 2, 3, 4, 5])
  assert.equal(n1.groupQuality, 'QUALIFIED')
  assert.equal(n2.cleaningOutcome, 'PASS')
  assert.equal(n5.reviewSequence[0], 'APPROVED')
})

test('missing Camera 2 does not renumber Camera 3 and is a qualified-with-exclusions fixture', () => {
  const fixture = buildScenarioFixture('n5-missing-camera', 'fast')
  assert.deepEqual(fixture.expectedCameraNumbers, [1, 2, 3, 4, 5])
  assert.deepEqual(fixture.presentCameraNumbers, [1, 3, 4, 5])
  assert.deepEqual(fixture.missingCameraNumbers, [2])
  assert.equal(fixture.qualityByCamera[3], 'QUALIFIED')
  assert.equal(fixture.groupQuality, 'QUALIFIED_WITH_EXCLUSIONS')
  assert.equal(fixture.cleaningOutcome, 'PASS')
  const second = buildScenarioFixture('n5-missing-camera', 'fast')
  assert.deepEqual(second, fixture)
})

test('scenario definitions keep RECEIVING, media failure, candidate and human gates distinct', () => {
  const receiving = buildScenarioFixture('receiving-before-finalize', 'fast')
  assert.equal(receiving.finalizeReceipt, false)
  assert.equal(receiving.receiptAfterFinalize, 'RECEIVING')
  assert.equal(receiving.expectedGroupQualityError, 'CAPTURE_GROUP_QUALITY_RECEIPT_NOT_FINALIZED')
  const mediaFailure = buildScenarioFixture('media-probe-failure', 'fast')
  assert.equal(mediaFailure.mediaMode, 'truncated')
  assert.equal(mediaFailure.mediaProbeExpected, 'FAILED')
  const noHand = buildScenarioFixture('no-hand-detected', 'fast')
  assert.deepEqual(noHand.candidateExpected, { status: 'SUCCEEDED', handResult: 'NO_HAND_DETECTED' })
  assert.equal(noHand.groupQuality, 'QUALIFIED')
  const candidateFailure = buildScenarioFixture('candidate-failure', 'fast')
  assert.deepEqual(candidateFailure.candidateExpected, { status: 'FAILED', handResult: null })
  assert.equal(candidateFailure.qualityByCamera[1], 'QUALIFIED')
  const reviewReject = buildScenarioFixture('review-rejected', 'fast')
  assert.deepEqual(reviewReject.reviewSequence, ['REJECTED'])
  assert.equal(reviewReject.cleaningOutcome, null)
  const inputChange = buildScenarioFixture('review-input-change', 'fast')
  assert.deepEqual(inputChange.reviewSequence, ['APPROVED', 'APPROVED'])
  assert.deepEqual(inputChange.inputChangeAfterReview, {
    cameraNumber: 2,
    viewQuality: 'REJECTED',
    intermediateGroupQuality: 'NEEDS_REVIEW',
    finalGroupQuality: 'QUALIFIED_WITH_EXCLUSIONS',
  })
  const viewNeedsReview = buildScenarioFixture('view-needs-review', 'fast')
  assert.equal(viewNeedsReview.qualityByCamera[2], 'NEEDS_REVIEW')
  assert.equal(viewNeedsReview.groupQuality, 'NEEDS_REVIEW')
})

test('synthetic metadata preserves explicit sparse camera indices and stream facts', () => {
  const metadata = createSyntheticGroupMetadata({
    cameraSetId: 'set-1',
    captureSessionId: 'session-1',
    deviceId: 'synthetic-device',
    recordedAt: '2026-08-01T01:00:00.000Z',
    layoutVersion: 'synthetic-capture-group-v1',
    syncGroupId: 'synthetic-group',
    expectedCameraNumbers: [3, 1],
    streams: [
      {
        cameraNumber: 3,
        cameraChannelId: 'channel-3',
        videoClientFileId: 'video-3',
        recordStartAt: '2026-08-01T01:00:00.111Z',
      },
      {
        cameraNumber: 1,
        cameraChannelId: 'channel-1',
        videoClientFileId: 'video-1',
        recordStartAt: '2026-08-01T01:00:00.037Z',
      },
    ],
  })
  assert.equal(metadata.expected_camera_count, 2)
  assert.deepEqual(metadata.streams.map((stream) => stream.camera_index), [0, 2])
  assert.notEqual(metadata.streams[0].record_start_utc, metadata.streams[1].record_start_utc)
  assert.equal(metadata.streams[1].sync_status, 'UNKNOWN')
})

test('synthetic event log carries the group key and camera numbers explicitly', () => {
  const line = createSyntheticEventLog({
    captureGroupKey: 'synthetic-group-fast',
    captureSessionId: 'session-1',
    cameraNumbers: [4, 1, 3],
    occurredAt: '2026-08-01T01:00:00.000Z',
  }).toString('utf8').trim()
  const event = JSON.parse(line)
  assert.equal(event.capture_group_key, 'synthetic-group-fast')
  assert.deepEqual(event.camera_numbers, [1, 3, 4])
  assert.equal(event.schema_version, 'synthetic-capture-event-v1')
})

test('reused upload primitive provisions sparse camera members without renumbering', async () => {
  const calls = []
  const client = new SyntheticUploadClient({
    baseUrl: 'http://localhost:8080',
    projectId: 'project-1',
    bearerToken: 'synthetic-admin-token',
    fetchImpl: async (url, request) => {
      calls.push({ url, request })
      if (url.includes('/camera-channels/')) {
        const cameraNumber = Number(url.match(/camera-(\d+)$/)?.[1])
        return new Response(JSON.stringify({ camera_channel_id: `channel-${cameraNumber}` }), { status: 201 })
      }
      const body = JSON.parse(request.body)
      assert.deepEqual(body.members.map((member) => member.camera_index), [0, 2, 3])
      return new Response(JSON.stringify({ camera_set_id: 'camera-set-1' }), { status: 201 })
    },
  })
  const cameraSet = await client.provisionSyntheticCameraSet({
    cameraNumbers: [1, 3, 4],
    cameraSetKey: 'synthetic-acceptance-sparse',
  })
  assert.deepEqual(cameraSet.channels, [
    { cameraNumber: 1, cameraChannelId: 'channel-1' },
    { cameraNumber: 3, cameraChannelId: 'channel-3' },
    { cameraNumber: 4, cameraChannelId: 'channel-4' },
  ])
  assert.equal(calls.length, 4)
})

test('camera numbers reject duplicates and invalid membership instead of inferring identity', () => {
  assert.deepEqual(normalizeCameraNumbers([5, 1, 3]), [1, 3, 5])
  assert.throws(() => normalizeCameraNumbers([1, 1]), /unique/)
  assert.throws(() => normalizeCameraNumbers([0]), /integer from 1/)
  assert.throws(() => normalizeCameraNumbers([65]), /integer from 1/)
  assert.throws(() => normalizeCameraNumbers([]), /non-empty/)
})

test('CLI accepts only named scenarios and never accepts source media or token arguments', () => {
  assert.deepEqual(parseArgs(['--scenario', 'n5-missing-camera']), {
    scenario: 'n5-missing-camera',
    help: false,
    list: false,
  })
  assert.deepEqual(parseArgs(['--list']), {
    scenario: 'n5-missing-camera',
    help: false,
    list: true,
  })
  for (const argument of ['--video', '--video-path', '--source', '--token', '--bearer-token']) {
    assert.throws(() => parseArgs([argument, 'not-accepted']), /Unsupported argument/)
  }
  assert.throws(() => parseArgs(['patient-video.mp4']), /Unsupported argument/)
  assert.throws(() => parseArgs(['--scenario', 'unknown']), /Unknown synthetic CaptureGroup scenario/)
})

test('CaptureGroup readback preserves sparse identity and receipt facts', () => {
  const fixture = buildScenarioFixture('n5-missing-camera', 'fast')
  assertCaptureGroupIdentity({
    capture_group_key: fixture.captureGroupKey,
    expected_camera_numbers: [1, 2, 3, 4, 5],
    received_camera_numbers: [1, 3, 4, 5],
    missing_camera_numbers: [2],
    streams: [
      { camera_number: 1 },
      { camera_number: 3 },
      { camera_number: 4 },
      { camera_number: 5 },
    ],
  }, fixture)
})

function syntheticCleaningFixture(fixture, excludedCameraNumbers) {
  const participating = fixture.presentCameraNumbers
    .filter((cameraNumber) => !excludedCameraNumbers.includes(cameraNumber))
    .map((cameraNumber) => cameraNumber === fixture.primaryCameraNumber
      ? {
          camera_number: cameraNumber,
          retained: true,
          downstream_included: true,
          interval: [{ start_ms: 250, end_ms: 1250, label: 'prepare_workspace' }],
          interval_source: 'PRIMARY_AUTHORITATIVE',
          action_aligned_interval: true,
        }
      : {
          camera_number: cameraNumber,
          retained: true,
          downstream_included: true,
          interval_source: 'EVENT_LEVEL_EVIDENCE',
          action_aligned_interval: false,
        })
  const excluded = [...fixture.missingCameraNumbers, ...excludedCameraNumbers].map((cameraNumber) => ({
    camera_number: cameraNumber,
    present: fixture.presentCameraNumbers.includes(cameraNumber),
    retained: true,
    downstream_included: false,
    interval_source: 'EVENT_LEVEL_EVIDENCE',
    action_aligned_interval: false,
    exclusion_reason: 'MISSING_VIEW',
  }))
  return {
    annotation_review_decision: 'APPROVED',
    primary_interval: [{ start_ms: 250, end_ms: 1250, label: 'prepare_workspace' }],
    participating_views: participating,
    excluded_views: excluded,
    mapping_provenance: [],
    outcome: 'PASS',
  }
}

test('qualified-with-exclusions Cleaning passes while missing and rejected views stay retained/excluded', () => {
  const missing = buildScenarioFixture('n5-missing-camera', 'fast')
  assertCleaningResult(syntheticCleaningFixture(missing, []), missing)
  const rejected = buildScenarioFixture('n5-rejected-view', 'fast')
  assertCleaningResult(syntheticCleaningFixture(rejected, [2]), rejected)
})

test('Review context requires the current revision, primary, view sets and candidate evidence', () => {
  const fixture = buildScenarioFixture('n2-complete', 'fast')
  assertReviewContext({
    capture_group_id: 'group-1',
    revision: 1,
    primary_clip_asset_id: 'asset-1',
    confirmed_view_set: { schema_version: 'capture-group-view-set.v1' },
    qualified_views: [{ camera_number: 1 }, { camera_number: 2 }],
    rejected_missing_views: [],
    candidate_evidence: [],
  }, fixture, 1)
  assert.throws(() => assertReviewContext({
    capture_group_id: 'group-1',
    revision: 1,
    primary_clip_asset_id: 'asset-1',
    confirmed_view_set: {},
    qualified_views: [{ camera_number: 1 }],
    rejected_missing_views: [],
    candidate_evidence: [],
  }, fixture, 1), /qualified view camera numbers differ/)
})

test('workflow client uses project-scoped paths and keeps administrator review separate', async () => {
  const calls = []
  const client = new SyntheticCaptureGroupWorkflowClient({
    baseUrl: 'http://localhost:8080',
    projectId: 'project-1',
    bearerToken: 'synthetic-member-token',
    adminBearerToken: 'synthetic-admin-token',
    fetchImpl: async (url, request) => {
      calls.push({ url, request })
      return new Response(JSON.stringify({
        decision: 'APPROVED',
      }), { status: 200, headers: { 'content-type': 'application/json' } })
    },
  })
  await client.reviewRevision('revision-1', 'APPROVED', 'synthetic review')
  assert.match(calls[0].url, /\/api\/v1\/projects\/project-1\/annotation-revisions\/revision-1\/review$/)
  assert.equal(calls[0].request.headers.Authorization, 'Bearer synthetic-admin-token')
  assert.match(calls[0].request.headers['Idempotency-Key'], /^synthetic-acceptance-/)
  await client.recheckCaptureGroupCleaning('revision-1')
  assert.match(calls[1].url, /\/capture-group-cleaning-rechecks$/)
  assert.equal(calls[1].request.body, undefined)
  assert.equal(calls[1].request.headers['Content-Type'], undefined)
  await client.recordViewQuality('asset-1', 'QUALIFIED', 'MEETS_REQUIREMENTS')
  assert.equal(calls[2].request.headers.Authorization, 'Bearer synthetic-member-token')
  assert.match(calls[2].url, /\/clip-assets\/asset-1\/quality-review$/)
})
