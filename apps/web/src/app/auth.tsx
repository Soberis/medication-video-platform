/* eslint-disable react-refresh/only-export-components */

import {
  createContext,
  type PropsWithChildren,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react'

import {
  AuthApiError,
  getSession,
  login as loginRequest,
  logout as logoutRequest,
  type PlatformSession,
} from '@/api/auth'

interface AuthContextValue {
  initialized: boolean
  isAuthenticated: boolean
  loading: boolean
  session: PlatformSession | null
  sessionError: AuthApiError | null
  retrySession: () => Promise<void>
  login: (username: string, password: string) => Promise<void>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

let pendingSessionRequest: Promise<PlatformSession> | null = null

function requestSessionOnce() {
  pendingSessionRequest ??= getSession().finally(() => {
    pendingSessionRequest = null
  })
  return pendingSessionRequest
}

interface SessionResult {
  session: PlatformSession | null
  error: AuthApiError | null
}

async function readSession(): Promise<SessionResult> {
  try {
    return { session: await requestSessionOnce(), error: null }
  } catch (error) {
    if (error instanceof AuthApiError && error.errorCode === 'UNAUTHENTICATED') {
      return { session: null, error: null }
    }
    return {
      session: null,
      error:
        error instanceof AuthApiError
          ? error
          : new AuthApiError('认证服务暂不可用，请稍后重试。', 'AUTH_SERVICE_UNAVAILABLE'),
    }
  }
}

interface AuthProviderProps extends PropsWithChildren {
  initialSession?: PlatformSession | null
  initiallyInitialized?: boolean
}

export function AuthProvider({
  children,
  initialSession = null,
  initiallyInitialized = false,
}: AuthProviderProps) {
  const [session, setSession] = useState<PlatformSession | null>(initialSession)
  const [initialized, setInitialized] = useState(initiallyInitialized)
  const [loading, setLoading] = useState(!initiallyInitialized)
  const [sessionError, setSessionError] = useState<AuthApiError | null>(null)

  const applySessionResult = useCallback((result: SessionResult) => {
    setSession(result.session)
    setSessionError(result.error)
    setInitialized(true)
    setLoading(false)
  }, [])

  useEffect(() => {
    if (initiallyInitialized) return

    let active = true
    void readSession().then((result) => {
      if (active) applySessionResult(result)
    })
    return () => {
      active = false
    }
  }, [applySessionResult, initiallyInitialized])

  const retrySession = useCallback(async () => {
    setLoading(true)
    setSessionError(null)
    applySessionResult(await readSession())
  }, [applySessionResult])

  const login = useCallback(async (username: string, password: string) => {
    setLoading(true)
    setSessionError(null)
    try {
      setSession(await loginRequest(username, password))
      setInitialized(true)
    } finally {
      setLoading(false)
    }
  }, [])

  const logout = useCallback(async () => {
    setLoading(true)
    setSessionError(null)
    try {
      await logoutRequest()
      setSession(null)
      setInitialized(true)
    } finally {
      setLoading(false)
    }
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({
      initialized,
      isAuthenticated: session !== null,
      loading,
      session,
      sessionError,
      retrySession,
      login,
      logout,
    }),
    [initialized, loading, login, logout, retrySession, session, sessionError],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth 必须在 AuthProvider 内使用。')
  return context
}
