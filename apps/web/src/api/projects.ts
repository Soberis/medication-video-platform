import { CatalogApiError } from '@/api/catalog'

export type ProjectRole = 'ADMIN' | 'MEMBER'
export type ProjectStatus = 'ACTIVE'

export interface DataProject {
  project_id: string
  display_name: string
  status: ProjectStatus
  current_role: ProjectRole
  created_at: string
}

export interface AssetFolder {
  folder_id: string
  project_id: string
  display_name: string
  version: number
  created_at: string
  updated_at: string
}

interface ListResponse<T> {
  items: T[]
}

interface ApiErrorBody {
  error_code?: unknown
  retryable?: unknown
  request_id?: unknown
}

export async function listProjects(signal?: AbortSignal): Promise<DataProject[]> {
  return (await readJson<ListResponse<DataProject>>('/api/v1/projects', signal)).items
}

export async function listAssetFolders(
  projectId: string,
  signal?: AbortSignal,
): Promise<AssetFolder[]> {
  return (
    await readJson<ListResponse<AssetFolder>>(
      `/api/v1/projects/${encodeURIComponent(projectId)}/asset-folders`,
      signal,
    )
  ).items
}

async function readJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(path, {
    method: 'GET',
    headers: { Accept: 'application/json' },
    cache: 'no-store',
    signal,
  })

  if (response.ok) return (await response.json()) as T

  let body: ApiErrorBody = {}
  try {
    body = (await response.json()) as ApiErrorBody
  } catch {
    // Use the safe fallback below for proxy and non-JSON failures.
  }
  const errorCode = typeof body.error_code === 'string' ? body.error_code : 'PROJECT_REQUEST_FAILED'
  throw new CatalogApiError(
    errorCode === 'PROJECT_ACCESS_DENIED'
      ? '当前用户无权访问该项目。'
      : '暂时无法读取项目数据，请稍后重试。',
    errorCode,
    body.retryable === true,
    typeof body.request_id === 'string' ? body.request_id : null,
  )
}
