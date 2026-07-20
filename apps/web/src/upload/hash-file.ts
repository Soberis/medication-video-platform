export interface FileHashResult {
  readonly sha256: string
  readonly partSha256: readonly string[]
}

interface HashProgressMessage {
  readonly type: 'progress'
  readonly processedBytes: number
  readonly totalBytes: number
}

interface HashCompletedMessage {
  readonly type: 'completed'
  readonly sha256: string
  readonly partSha256: string[]
}

interface HashFailedMessage {
  readonly type: 'failed'
}

type HashWorkerMessage = HashProgressMessage | HashCompletedMessage | HashFailedMessage

export function hashFileInWorker(
  file: File,
  partSizeBytes: number,
  onProgress: (processedBytes: number, totalBytes: number) => void,
  signal?: AbortSignal,
): Promise<FileHashResult> {
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL('./sha256.worker.ts', import.meta.url), { type: 'module' })
    let finished = false

    function finish(callback: () => void) {
      if (finished) return
      finished = true
      signal?.removeEventListener('abort', abort)
      worker.terminate()
      callback()
    }

    function abort() {
      finish(() => reject(new DOMException('The file hash was cancelled.', 'AbortError')))
    }

    worker.onmessage = ({ data }: MessageEvent<HashWorkerMessage>) => {
      if (data.type === 'progress') {
        onProgress(data.processedBytes, data.totalBytes)
        return
      }
      if (data.type === 'completed') {
        finish(() => resolve({ sha256: data.sha256, partSha256: data.partSha256 }))
        return
      }
      finish(() => reject(new Error('LOCAL_UPLOAD_HASH_FAILED')))
    }
    worker.onerror = () => finish(() => reject(new Error('LOCAL_UPLOAD_HASH_FAILED')))

    if (signal?.aborted) {
      abort()
      return
    }
    signal?.addEventListener('abort', abort, { once: true })
    worker.postMessage({ file, partSizeBytes })
  })
}
