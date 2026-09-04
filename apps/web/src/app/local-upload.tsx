/* eslint-disable react-refresh/only-export-components */

import {
  createContext,
  type Dispatch,
  type MutableRefObject,
  type PropsWithChildren,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useReducer,
  useRef,
} from 'react'

import {
  LocalUploadApiError,
  type LocalUploadProgress,
  uploadPlatformLocalVideo,
} from '@/api/local-upload'

export type LocalUploadTaskStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED'
export type LocalUploadState = 'idle' | 'uploading' | 'completed'

export interface LocalUploadTask {
  readonly id: number
  readonly file: File
  readonly projectId: string
  readonly status: LocalUploadTaskStatus
  readonly progress: LocalUploadProgress | null
  readonly errorCode: string | null
  readonly errorMessage: string | null
  readonly captureSessionId: string | null
  readonly clipAssetId: string | null
}

interface UploadStore {
  readonly tasks: LocalUploadTask[]
  readonly uploadState: LocalUploadState
}

type UploadAction =
  | { type: 'initialize'; projectId: string; files: File[] }
  | { type: 'running'; id: number }
  | { type: 'progress'; id: number; progress: LocalUploadProgress }
  | { type: 'completed'; id: number; captureSessionId: string; clipAssetId: string | null }
  | { type: 'failed'; id: number; errorCode: string; errorMessage: string }
  | { type: 'finish' }
  | { type: 'reset' }

const initialStore: UploadStore = { tasks: [], uploadState: 'idle' }

function uploadReducer(store: UploadStore, action: UploadAction): UploadStore {
  if (action.type === 'reset') return initialStore
  if (action.type === 'initialize') {
    return {
      uploadState: 'uploading',
      tasks: action.files.map((file, id) => ({
        id,
        file,
        projectId: action.projectId,
        status: 'QUEUED',
        progress: null,
        errorCode: null,
        errorMessage: null,
        captureSessionId: null,
        clipAssetId: null,
      })),
    }
  }
  if (action.type === 'finish') return { ...store, uploadState: 'completed' }

  return {
    ...store,
    tasks: store.tasks.map((task) =>
      task.id !== action.id
        ? task
        : action.type === 'running'
          ? { ...task, status: 'RUNNING' }
          : action.type === 'progress'
            ? { ...task, progress: action.progress }
            : action.type === 'completed'
              ? {
                  ...task,
                  status: 'COMPLETED',
                  captureSessionId: action.captureSessionId,
                  clipAssetId: action.clipAssetId,
                }
              : {
                  ...task,
                  status: 'FAILED',
                  errorCode: action.errorCode,
                  errorMessage: action.errorMessage,
                },
    ),
  }
}

interface LocalUploadContextValue extends UploadStore {
  resetUpload: () => void
  startUpload: (projectId: string, files: File[]) => void
}

const LocalUploadContext = createContext<LocalUploadContextValue | null>(null)

export function LocalUploadProvider({ children }: PropsWithChildren) {
  const [store, dispatch] = useReducer(uploadReducer, initialStore)
  const uploadRequest = useRef<AbortController | null>(null)

  useEffect(
    () => () => {
      uploadRequest.current?.abort()
    },
    [],
  )

  const resetUpload = useCallback(() => {
    if (uploadRequest.current) return
    dispatch({ type: 'reset' })
  }, [])

  const startUpload = useCallback(
    (projectId: string, files: File[]) => {
      if (projectId.length === 0 || files.length === 0) return
      if (uploadRequest.current || store.tasks.length > 0) return

      const request = new AbortController()
      uploadRequest.current = request
      dispatch({ type: 'initialize', projectId, files })
      void runUpload(projectId, files, request, dispatch, uploadRequest)
    },
    [store.tasks.length],
  )

  const value = useMemo<LocalUploadContextValue>(
    () => ({ ...store, resetUpload, startUpload }),
    [resetUpload, startUpload, store],
  )

  return <LocalUploadContext.Provider value={value}>{children}</LocalUploadContext.Provider>
}

export function useLocalUpload() {
  const context = useContext(LocalUploadContext)
  if (!context) throw new Error('useLocalUpload 必须在 LocalUploadProvider 内使用。')
  return context
}

async function runUpload(
  projectId: string,
  files: File[],
  request: AbortController,
  dispatch: Dispatch<UploadAction>,
  uploadRequest: MutableRefObject<AbortController | null>,
) {
  for (let id = 0; id < files.length; id += 1) {
    const file = files[id]
    if (!file || request.signal.aborted) return
    dispatch({ type: 'running', id })
    try {
      const result = await uploadPlatformLocalVideo(
        { projectId, file },
        (progress) => dispatch({ type: 'progress', id, progress }),
        request.signal,
      )
      dispatch({
        type: 'completed',
        id,
        captureSessionId: result.captureSessionId,
        clipAssetId: result.clipAssetId,
      })
    } catch (error) {
      if (request.signal.aborted) return
      const apiError = error instanceof LocalUploadApiError
      dispatch({
        type: 'failed',
        id,
        errorCode: apiError ? error.errorCode : 'LOCAL_UPLOAD_FAILED',
        errorMessage: apiError ? error.message : '本地视频上传失败，请检查网络和本地服务后重试。',
      })
    }
  }
  if (uploadRequest.current !== request || request.signal.aborted) return
  uploadRequest.current = null
  dispatch({ type: 'finish' })
}
