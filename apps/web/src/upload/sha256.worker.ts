import { sha256 } from '@noble/hashes/sha2.js'

interface HashRequest {
  readonly file: File
  readonly partSizeBytes: number
}

interface HashProgress {
  readonly type: 'progress'
  readonly processedBytes: number
  readonly totalBytes: number
}

interface HashCompleted {
  readonly type: 'completed'
  readonly sha256: string
  readonly partSha256: string[]
}

interface HashFailed {
  readonly type: 'failed'
}

type HashWorkerResponse = HashProgress | HashCompleted | HashFailed

const workerScope = globalThis as unknown as {
  onmessage: ((event: MessageEvent<HashRequest>) => void) | null
  postMessage: (message: HashWorkerResponse) => void
}

workerScope.onmessage = async ({ data }) => {
  try {
    const fullFileHash = sha256.create()
    const partSha256: string[] = []

    for (let offset = 0; offset < data.file.size; offset += data.partSizeBytes) {
      const nextOffset = Math.min(offset + data.partSizeBytes, data.file.size)
      const bytes = new Uint8Array(await data.file.slice(offset, nextOffset).arrayBuffer())
      fullFileHash.update(bytes)
      partSha256.push(toHex(sha256(bytes)))
      workerScope.postMessage({
        type: 'progress',
        processedBytes: nextOffset,
        totalBytes: data.file.size,
      })
    }

    workerScope.postMessage({
      type: 'completed',
      sha256: toHex(fullFileHash.digest()),
      partSha256,
    })
  } catch {
    workerScope.postMessage({ type: 'failed' })
  }
}

function toHex(bytes: Uint8Array) {
  return Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('')
}
