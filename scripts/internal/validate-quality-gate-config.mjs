import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const STRING_ARRAY_PROPERTIES = ['requiredFiles', 'brunoRequests', 'strictRequiredFiles']

const RECORD_PROPERTIES = {
  jsonContracts: ['path', 'success', 'failure'],
  profiles: ['path', 'name', 'expectedDigest', 'digestSuccess', 'digestFailure'],
}

const ARRAY_PROPERTIES = [...STRING_ARRAY_PROPERTIES, ...Object.keys(RECORD_PROPERTIES)]

const hasOwn = (value, property) => Object.prototype.hasOwnProperty.call(value, property)

const isRecord = (value) => typeof value === 'object' && value !== null && !Array.isArray(value)

function requireProperty(config, property) {
  if (!hasOwn(config, property)) {
    throw new Error(`${property} is required`)
  }
}

function requireNonEmptyString(value, property) {
  if (typeof value !== 'string' || value.trim().length === 0) {
    throw new Error(`${property} must be a non-empty string`)
  }
}

function validateStringArray(config, property) {
  requireProperty(config, property)
  if (!Array.isArray(config[property])) {
    throw new Error(`${property} must be an array`)
  }

  config[property].forEach((value, index) => {
    requireNonEmptyString(value, `${property}[${index}]`)
  })
}

function validateRecordArray(config, property, fields) {
  requireProperty(config, property)
  if (!Array.isArray(config[property])) {
    throw new Error(`${property} must be an array`)
  }

  config[property].forEach((record, index) => {
    if (!isRecord(record)) {
      throw new Error(`${property}[${index}] must be an object`)
    }

    fields.forEach((field) => {
      if (!hasOwn(record, field)) {
        throw new Error(`${property}[${index}].${field} is required`)
      }
      requireNonEmptyString(record[field], `${property}[${index}].${field}`)
    })
  })
}

export function validateQualityGateConfig(config) {
  if (!isRecord(config)) {
    throw new Error('configuration must be an object')
  }

  requireProperty(config, 'schema_version')
  requireNonEmptyString(config.schema_version, 'schema_version')

  ARRAY_PROPERTIES.forEach((property) => {
    if (STRING_ARRAY_PROPERTIES.includes(property)) {
      validateStringArray(config, property)
      return
    }

    validateRecordArray(config, property, RECORD_PROPERTIES[property])
  })

  requireProperty(config, 'brunoManifest')
  requireNonEmptyString(config.brunoManifest, 'brunoManifest')

  return config
}

export function parseQualityGateConfig(content) {
  let config
  try {
    config = JSON.parse(content)
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    throw new Error(`invalid JSON: ${message}`)
  }

  return validateQualityGateConfig(config)
}

function main() {
  const filePath = process.argv[2]
  if (!filePath) {
    throw new Error('configuration file path is required')
  }

  parseQualityGateConfig(readFileSync(filePath, 'utf8'))
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main()
  } catch (error) {
    process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`)
    process.exitCode = 1
  }
}
