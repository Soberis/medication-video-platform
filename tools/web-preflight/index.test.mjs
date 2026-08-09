import assert from 'node:assert/strict'
import test from 'node:test'

import { VIEWPORTS, assertPageCheck, assertPreflightEnvironment } from './index.mjs'

const environment = {
  RUN_P0_WEB_PREFLIGHT: 'true',
  DATA_PROFILE: 'synthetic',
  WEB_PREFLIGHT_USERNAME: 'local-admin',
  WEB_PREFLIGHT_PASSWORD: 'test-only-password',
  P0_WEB_PREFLIGHT_PROJECT_ID: '00000000-0000-4000-8000-000000000001',
  P0_WEB_PREFLIGHT_ANNOTATION_TASK_ID: '00000000-0000-4000-8000-000000000002',
  P0_WEB_PREFLIGHT_DATASET_VERSION_ID: '00000000-0000-4000-8000-000000000003',
}

test('precheck is explicit, synthetic, and loopback-only', () => {
  const config = assertPreflightEnvironment(environment)
  assert.equal(config.baseUrl, 'http://127.0.0.1:5173')
  assert.deepEqual(VIEWPORTS, [
    [1366, 768],
    [1920, 1080],
    [2560, 1440],
    [3840, 2160],
  ])

  assert.throws(
    () => assertPreflightEnvironment({ ...environment, RUN_P0_WEB_PREFLIGHT: 'false' }),
    /RUN_P0_WEB_PREFLIGHT/,
  )
  assert.throws(
    () => assertPreflightEnvironment({ ...environment, DATA_PROFILE: 'production' }),
    /DATA_PROFILE=synthetic/,
  )
  assert.throws(
    () =>
      assertPreflightEnvironment({
        ...environment,
        P0_WEB_PREFLIGHT_BASE_URL: 'https://example.com',
      }),
    /loopback/,
  )
  assert.throws(
    () =>
      assertPreflightEnvironment({
        ...environment,
        P0_WEB_PREFLIGHT_BASE_URL: 'http://admin:secret@localhost:5173',
      }),
    /loopback/,
  )
})

test('precheck requires the three exact fixture identifiers', () => {
  assert.throws(
    () =>
      assertPreflightEnvironment({ ...environment, P0_WEB_PREFLIGHT_ANNOTATION_TASK_ID: 'wrong' }),
    /ANNOTATION_TASK_ID/,
  )
  assert.throws(
    () => assertPreflightEnvironment({ ...environment, P0_WEB_PREFLIGHT_DATASET_VERSION_ID: '' }),
    /DATASET_VERSION_ID/,
  )
  assert.throws(
    () => assertPreflightEnvironment({ ...environment, WEB_PREFLIGHT_PASSWORD: '' }),
    /WEB_PREFLIGHT_PASSWORD/,
  )
})

test('page checks reject missing marker evidence, overflow, and invisible focus', () => {
  const passing = {
    horizontalOverflow: false,
    focusVisible: true,
    missingTexts: [],
    specificEvidence: true,
  }
  assert.doesNotThrow(() => assertPageCheck(passing))
  assert.throws(() => assertPageCheck({ ...passing, horizontalOverflow: true }), /overflow/)
  assert.throws(() => assertPageCheck({ ...passing, focusVisible: false }), /focus/)
  assert.throws(
    () => assertPageCheck({ ...passing, specificEvidence: false }),
    /self-admin evidence/,
  )
})
