export interface DatasetDraftIntent {
  readonly projectId: string
  readonly displayName: string
  readonly captureSessionIds: readonly string[]
  readonly idempotencyKey: string
}

const storagePrefix = 'dataset-draft-idempotency:'
const fallbackKeys = new Map<string, string>()

export function getDatasetDraftIntent(
  projectId: string,
  displayName: string,
  captureSessionIds: readonly string[],
): DatasetDraftIntent {
  const storageKey = intentStorageKey(projectId, displayName, captureSessionIds)
  const idempotencyKey = readKey(storageKey) ?? crypto.randomUUID()
  writeKey(storageKey, idempotencyKey)
  return {
    projectId,
    displayName,
    captureSessionIds: [...captureSessionIds],
    idempotencyKey,
  }
}

export function clearDatasetDraftIntent(intent: DatasetDraftIntent) {
  const storageKey = intentStorageKey(
    intent.projectId,
    intent.displayName,
    intent.captureSessionIds,
  )
  fallbackKeys.delete(storageKey)
  try {
    sessionStorage.removeItem(storageKey)
  } catch {
    // The in-memory fallback was already cleared. A blocked browser store cannot affect the request.
  }
}

function intentStorageKey(projectId: string, displayName: string, captureSessionIds: readonly string[]) {
  return `${storagePrefix}${stableFingerprint(
    JSON.stringify([projectId, displayName, captureSessionIds]),
  )}`
}

function stableFingerprint(value: string) {
  let first = 0x811c9dc5
  let second = 0x9e3779b9
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index)
    first = Math.imul(first ^ code, 0x01000193)
    second = Math.imul(second ^ code, 0x85ebca6b)
  }
  return `${(first >>> 0).toString(16).padStart(8, '0')}${(second >>> 0).toString(16).padStart(8, '0')}`
}

function readKey(storageKey: string) {
  try {
    return sessionStorage.getItem(storageKey) ?? fallbackKeys.get(storageKey)
  } catch {
    return fallbackKeys.get(storageKey)
  }
}

function writeKey(storageKey: string, idempotencyKey: string) {
  try {
    sessionStorage.setItem(storageKey, idempotencyKey)
    fallbackKeys.delete(storageKey)
  } catch {
    // Private-mode and quota failures still retain the key until this page is closed.
    fallbackKeys.set(storageKey, idempotencyKey)
  }
}
