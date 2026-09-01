/* eslint-disable react-refresh/only-export-components */

import { createBrowserRouter, Navigate, Outlet, useLocation } from 'react-router'

import AppShell from '@/App'
import { useAuth } from '@/app/auth'
import { paths } from '@/app/paths'
import { ErrorState, LoadingState } from '@/components/WorkspaceFeedback'

function RouteLoading() {
  return <LoadingState>正在加载页面…</LoadingState>
}

function AuthenticationBoundary() {
  const auth = useAuth()
  const location = useLocation()

  if (!auth.initialized || auth.loading) {
    return <LoadingState>正在确认登录状态…</LoadingState>
  }
  if (auth.sessionError) {
    return (
      <ErrorState code={auth.sessionError.errorCode} onRetry={() => void auth.retrySession()}>
        {auth.sessionError.message}
      </ErrorState>
    )
  }
  if (!auth.isAuthenticated) {
    const redirect = `${location.pathname}${location.search}${location.hash}`
    return <Navigate replace to={`${paths.login}?redirect=${encodeURIComponent(redirect)}`} />
  }
  return <Outlet />
}

function AdministratorBoundary() {
  const auth = useAuth()
  return auth.session?.role === 'ADMIN' ? <Outlet /> : <Navigate replace to={paths.projects} />
}

export const router = createBrowserRouter([
  {
    path: paths.login,
    HydrateFallback: RouteLoading,
    lazy: async () => ({ Component: (await import('@/views/LoginView')).default }),
  },
  {
    Component: AuthenticationBoundary,
    HydrateFallback: RouteLoading,
    children: [
      {
        Component: AppShell,
        children: [
          { index: true, element: <Navigate replace to={paths.projects} /> },
          {
            path: paths.projects,
            lazy: async () => ({ Component: (await import('@/views/ProjectListView')).default }),
          },
          {
            path: '/projects/:projectId/capture-groups',
            lazy: async () => ({
              Component: (await import('@/views/CaptureGroupListView')).default,
            }),
          },
          {
            path: '/projects/:projectId/capture-groups/:captureGroupId',
            lazy: async () => ({
              Component: (await import('@/views/CaptureGroupDetailView')).default,
            }),
          },
          {
            path: '/projects/:projectId/annotation-tasks',
            lazy: async () => ({
              Component: (await import('@/views/AnnotationTaskListView')).default,
            }),
          },
          {
            path: paths.uploads,
            lazy: async () => ({ Component: (await import('@/views/UploadProgressView')).default }),
          },
          {
            Component: AdministratorBoundary,
            children: [
              {
                path: paths.localUpload,
                lazy: async () => ({
                  Component: (await import('@/views/LocalUploadView')).default,
                }),
              },
              {
                path: '/projects/:projectId/members',
                lazy: async () => ({
                  Component: (await import('@/views/ProjectMembershipView')).default,
                }),
              },
              {
                path: paths.accounts,
                lazy: async () => ({
                  Component: (await import('@/views/AccountManagementView')).default,
                }),
              },
            ],
          },
        ],
      },
    ],
  },
  { path: '*', element: <Navigate replace to={paths.projects} /> },
])
