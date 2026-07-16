import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import test from 'node:test'

import {
  DEFAULT_PART_SIZE_BYTES,
  buildManifest,
  createSyntheticMetadata,
  createUploadFile,
  sha256Hex,
  splitParts,
} from './upload-client.mjs'

test('buildManifest follows upload-file-manifest-v1 ordering and digest rules', () => {
  const later = createUploadFile(
    'VIDEO',
    'video/mp4',
    Buffer.from('video'),
    'ffffffff-ffff-4fff-8fff-ffffffffffff',
  )
  const earlier = createUploadFile(
    'METADATA',
    'application/json',
    Buffer.from('{}\n'),
    '00000000-0000-4000-8000-000000000001',
  )

  const manifest = buildManifest([later, earlier])

  assert.equal(
    manifest,
    `upload-file-manifest-v1\n${earlier.clientFileId}\tMETADATA\tapplication/json\t3\t${earlier.sha256}\t8388608\n${later.clientFileId}\tVIDEO\tvideo/mp4\t5\t${later.sha256}\t8388608\n`,
  )
  assert.match(sha256Hex(manifest), /^[a-f0-9]{64}$/)
})

test('splitParts keeps exact byte order and permits a small final part', () => {
  const source = Buffer.alloc(DEFAULT_PART_SIZE_BYTES + 17, 7)
  source[source.length - 1] = 9

  const parts = splitParts(source)

  assert.equal(parts.length, 2)
  assert.equal(parts[0].length, DEFAULT_PART_SIZE_BYTES)
  assert.equal(parts[1].length, 17)
  assert.deepEqual(Buffer.concat(parts), source)
})

test('createSyntheticMetadata binds exactly one generated video to server camera identities', () => {
  const cameraChannelId = randomUUID()
  const cameraSetId = randomUUID()
  const captureSessionId = randomUUID()
  const videoClientFileId = randomUUID()
  const recordedAt = '2026-07-16T08:00:00.000Z'

  const metadata = createSyntheticMetadata({
    cameraChannelId,
    cameraSetId,
    captureSessionId,
    deviceId: 'synthetic-edge-uploader-01',
    recordedAt,
    videoClientFileId,
  })

  assert.equal(metadata.schema_version, 'capture-session-metadata-v1')
  assert.equal(metadata.capture_session_id, captureSessionId)
  assert.equal(metadata.camera_set_id, cameraSetId)
  assert.equal(metadata.expected_camera_count, 1)
  assert.deepEqual(metadata.streams, [
    {
      client_file_id: videoClientFileId,
      camera_channel_id: cameraChannelId,
      camera_index: 0,
      enabled: true,
      record_start_utc: recordedAt,
      time_offset_ms: 0,
      sync_status: 'UNKNOWN',
    },
  ])
})

test('synthetic client refuses empty files and undersized planned parts', () => {
  assert.throws(() => createUploadFile('VIDEO', 'video/mp4', Buffer.alloc(0)), /non-empty/)
  assert.throws(() => splitParts(Buffer.from('x'), DEFAULT_PART_SIZE_BYTES - 1), /at least 8 MiB/)
})
