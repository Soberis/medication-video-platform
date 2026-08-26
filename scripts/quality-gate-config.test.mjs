import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

import {
  parseQualityGateConfig,
  validateQualityGateConfig,
} from './internal/validate-quality-gate-config.mjs'

const configPath = fileURLToPath(new URL('quality-gate.config.json', import.meta.url))
const validConfig = JSON.parse(await readFile(configPath, 'utf8'))

test('accepts the shared quality gate configuration', () => {
  assert.deepEqual(validateQualityGateConfig(validConfig), validConfig)
})

test('rejects malformed JSON', () => {
  assert.throws(() => parseQualityGateConfig('{'), /invalid JSON/)
})

test('rejects a missing required property', () => {
  const config = structuredClone(validConfig)
  delete config.brunoManifest

  assert.throws(() => validateQualityGateConfig(config), /brunoManifest is required/)
})

test('rejects an incorrect property type', () => {
  const config = structuredClone(validConfig)
  config.profiles = {}

  assert.throws(() => validateQualityGateConfig(config), /profiles must be an array/)
})

test('rejects a malformed record', () => {
  const config = structuredClone(validConfig)
  delete config.jsonContracts[0].failure

  assert.throws(() => validateQualityGateConfig(config), /jsonContracts\[0\]\.failure is required/)
})
