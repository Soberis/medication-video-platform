import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import test from 'node:test'

import {
  buildPaddedMp4Descriptor,
  createPaddedMp4Part,
  largeUploadManifest,
  payloadSha256,
} from './large-upload-acceptance.mjs'

test('padded MP4 payload has an exact size, valid free-box header and stable digest', () => {
  const prefix = Buffer.from('synthetic-mp4-prefix')
  const totalSize = 11 * 1024 * 1024
  const partSize = 5 * 1024 * 1024
  const descriptor = buildPaddedMp4Descriptor(prefix, totalSize, partSize)
  const payload = Buffer.concat(
    Array.from({ length: descriptor.totalParts }, (_, index) => createPaddedMp4Part(descriptor, index + 1)),
  )

  assert.equal(payload.length, totalSize)
  assert.equal(payload.subarray(0, prefix.length).toString(), prefix.toString())
  assert.equal(payload.readUInt32BE(prefix.length), totalSize - prefix.length)
  assert.equal(payload.subarray(prefix.length + 4, prefix.length + 8).toString('ascii'), 'free')
  assert.equal(payloadSha256(descriptor), createHash('sha256').update(payload).digest('hex'))
})

test('large upload manifest uses the frozen file metadata', () => {
  assert.equal(
    largeUploadManifest({
      clientFileId: '10000000-0000-0000-0000-000000000001',
      sha256: 'a'.repeat(64),
      totalSizeBytes: 2_147_483_648,
      partSizeBytes: 67_108_864,
    }),
    'upload-file-manifest-v1\n' +
      '10000000-0000-0000-0000-000000000001\tVIDEO\tvideo/mp4\t2147483648\t' +
      `${'a'.repeat(64)}\t67108864\n`,
  )
})
