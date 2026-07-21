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

export interface ClipAssetFolderAssignment {
  clip_asset_id: string
  project_id: string
  folder_id: string | null
  version: number
  updated_at: string
}

interface ListResponse<T> {
  items: T[]
}

interface ApiErrorBody {
  error_code?: unknown
  error_message?: unknown
  retryable?: unknown
  request_id?: unknown
}

export async function listProjects(signal?: AbortSignal): Promise<DataProject[]> {
  return (await readJson<ListResponse<DataProject>>('/api/v1/projects', signal)).items
}

export function getProject(projectId: string, signal?: AbortSignal): Promise<DataProject> {
  return readJson<DataProject>(`/api/v1/projects/${encodeURIComponent(projectId)}`, signal)
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

export function createAssetFolder(projectId: string, displayName: string): Promise<AssetFolder> {
  return writeJson<AssetFolder>(`/api/v1/projects/${encodeURIComponent(projectId)}/asset-folders`, 'POST', {
    display_name: displayName,
  })
}

export function renameAssetFolder(
  projectId: string,
  folderId: string,
  displayName: string,
): Promise<AssetFolder> {
  return writeJson<AssetFolder>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/asset-folders/${encodeURIComponent(folderId)}`,
    'PUT',
    { display_name: displayName },
  )
}

export async function deleteAssetFolder(projectId: string, folderId: string): Promise<void> {
  const response = await write(
    `/api/v1/projects/${encodeURIComponent(projectId)}/asset-folders/${encodeURIComponent(folderId)}`,
    'DELETE',
  )
  if (response.status !== 204) throw await projectApiError(response)
}

export function assignClipAssetFolder(
  projectId: string,
  clipAssetId: string,
  folderId: string,
): Promise<ClipAssetFolderAssignment> {
  return writeJson<ClipAssetFolderAssignment>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/clip-assets/${encodeURIComponent(clipAssetId)}/folder`,
    'PUT',
    { folder_id: folderId },
  )
}

export function unclassifyClipAsset(
  projectId: string,
  clipAssetId: string,
): Promise<ClipAssetFolderAssignment> {
  return writeJson<ClipAssetFolderAssignment>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/clip-assets/${encodeURIComponent(clipAssetId)}/folder`,
    'DELETE',
  )
}

async function readJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(path, {
    method: 'GET',
    headers: { Accept: 'application/json' },
    cache: 'no-store',
    signal,
  })

  if (response.ok) return (await response.json()) as T

  throw await projectApiError(response)
}

async function writeJson<T>(path: string, method: 'POST' | 'PUT' | 'DELETE', body?: object): Promise<T> {
  const response = await write(path, method, body)
  if (response.ok) return (await response.json()) as T
  throw await projectApiError(response)
}

async function write(path: string, method: 'POST' | 'PUT' | 'DELETE', body?: object) {
  return fetch(path, {
    method,
    headers: {
      Accept: 'application/json',
      'Idempotency-Key': crypto.randomUUID(),
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
    cache: 'no-store',
  })
}

async function projectApiError(response: Response) {
  let body: ApiErrorBody = {}
  try {
    body = (await response.json()) as ApiErrorBody
  } catch {
    // Use the safe fallback below for proxy and non-JSON failures.
  }
  const errorCode = typeof body.error_code === 'string' ? body.error_code : 'PROJECT_REQUEST_FAILED'
  const errorMessage =
    errorCode === 'PROJECT_ACCESS_DENIED'
      ? '当前用户无权管理该项目。'
      : errorCode === 'ASSET_FOLDER_NAME_CONFLICT'
        ? '当前项目中已存在同名文件夹。'
        : errorCode === 'ASSET_FOLDER_NOT_FOUND'
          ? '未找到该文件夹，或它已被其他管理员删除。'
          : '暂时无法读取或更新项目数据，请稍后重试。'
  throw new CatalogApiError(
    errorMessage,
    errorCode,
    body.retryable === true,
    typeof body.request_id === 'string' ? body.request_id : null,
  )
}
