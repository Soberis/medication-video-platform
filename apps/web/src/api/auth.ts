import { csrfHeaders } from '@/api/http'

export type PlatformRole = 'ADMIN' | 'MEMBER'

export interface PlatformSession {
  username: string
  role: PlatformRole
}

export class AuthApiError extends Error {
  readonly errorCode: string

  constructor(message: string, errorCode: string) {
    super(message)
    this.name = 'AuthApiError'
    this.errorCode = errorCode
  }
}

export async function login(username: string, password: string): Promise<PlatformSession> {
  const response = await authFetch('/api/v1/auth/login', {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
    credentials: 'same-origin',
    cache: 'no-store',
  })
  return readSession(response)
}

export async function getSession(): Promise<PlatformSession> {
  const response = await authFetch('/api/v1/auth/session', {
    method: 'GET',
    headers: { Accept: 'application/json' },
    credentials: 'same-origin',
    cache: 'no-store',
  })
  return readSession(response)
}

export async function logout(): Promise<void> {
  const response = await authFetch('/api/v1/auth/logout', {
    method: 'POST',
    headers: { Accept: 'application/json', ...csrfHeaders() },
    credentials: 'same-origin',
    cache: 'no-store',
  })
  if (!response.ok && response.status !== 401) throw await toAuthError(response)
}

async function authFetch(input: RequestInfo | URL, init: RequestInit): Promise<Response> {
  try {
    return await fetch(input, init)
  } catch {
    throw new AuthApiError(
      '认证服务暂不可用，请确认 Core API 已启动后重试。',
      'AUTH_SERVICE_UNAVAILABLE',
    )
  }
}

async function readSession(response: Response): Promise<PlatformSession> {
  if (!response.ok) throw await toAuthError(response)
  return (await response.json()) as PlatformSession
}

async function toAuthError(response: Response): Promise<AuthApiError> {
  let errorCode = 'AUTH_REQUEST_FAILED'
  try {
    const body = (await response.json()) as { error_code?: unknown }
    if (typeof body.error_code === 'string') errorCode = body.error_code
  } catch {
    // Use the stable fallback for a proxy or non-JSON failure.
  }
  if (errorCode === 'AUTH_REQUEST_FAILED' && response.status >= 500) {
    errorCode = 'AUTH_SERVICE_UNAVAILABLE'
  }
  const message =
    errorCode === 'INVALID_CREDENTIALS'
      ? '账号或密码不正确。'
      : errorCode === 'UNAUTHENTICATED'
        ? '登录已过期，请重新登录。'
        : errorCode === 'AUTH_SERVICE_UNAVAILABLE'
          ? '认证服务暂不可用，请确认 Core API 已启动后重试。'
          : '暂时无法完成登录，请稍后重试。'
  return new AuthApiError(message, errorCode)
}
