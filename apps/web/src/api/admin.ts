import { csrfHeaders } from '@/api/http'

export type PlatformRole = 'ADMIN' | 'MEMBER'

export interface PlatformAccount {
  username: string
  role: PlatformRole
  enabled: boolean
  version: number
  created_at: string
  updated_at: string
}

export class AdminApiError extends Error {
  constructor(
    message: string,
    readonly errorCode: string,
    readonly requestId: string | null,
  ) {
    super(message)
    this.name = 'AdminApiError'
  }
}

export async function listPlatformAccounts(signal?: AbortSignal): Promise<PlatformAccount[]> {
  const response = await fetch('/api/v1/admin/accounts', {
    method: 'GET',
    headers: { Accept: 'application/json' },
    credentials: 'same-origin',
    cache: 'no-store',
    signal,
  })
  if (!response.ok) throw await toAdminApiError(response)
  return ((await response.json()) as { items: PlatformAccount[] }).items
}

export async function createPlatformAccount(username: string, password: string): Promise<PlatformAccount> {
  return writeAccount('/api/v1/admin/accounts', 'POST', { username, password })
}

export async function changePlatformAccountEnabled(
  username: string,
  enabled: boolean,
): Promise<PlatformAccount> {
  return writeAccount(`/api/v1/admin/accounts/${encodeURIComponent(username)}/enabled`, 'PATCH', { enabled })
}

async function writeAccount(path: string, method: 'POST' | 'PATCH', body: object): Promise<PlatformAccount> {
  const response = await fetch(path, {
    method,
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      ...csrfHeaders(),
    },
    body: JSON.stringify(body),
    credentials: 'same-origin',
    cache: 'no-store',
  })
  if (!response.ok) throw await toAdminApiError(response)
  return (await response.json()) as PlatformAccount
}

async function toAdminApiError(response: Response): Promise<AdminApiError> {
  let body: { error_code?: unknown; request_id?: unknown } = {}
  try {
    body = (await response.json()) as { error_code?: unknown; request_id?: unknown }
  } catch {
    // Use the safe fallback below for a proxy or non-JSON failure.
  }
  const errorCode = typeof body.error_code === 'string' ? body.error_code : 'ACCOUNT_REQUEST_FAILED'
  const message =
    errorCode === 'PLATFORM_ACCOUNT_USERNAME_CONFLICT'
      ? '该账号名已存在，请使用其他账号名。'
      : errorCode === 'PLATFORM_ACCOUNT_ACCESS_DENIED'
        ? '只有管理员可以管理平台账号。'
        : errorCode === 'PLATFORM_ACCOUNT_ROLE_IMMUTABLE'
          ? '初始管理员不能在此页面停用。'
          : '账号操作未完成，请稍后重试。'
  return new AdminApiError(message, errorCode, typeof body.request_id === 'string' ? body.request_id : null)
}
