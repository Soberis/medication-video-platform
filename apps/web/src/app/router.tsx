/* eslint-disable react-refresh/only-export-components */

import { Button } from 'antd'
import { createBrowserRouter, Navigate, Outlet, useLocation } from 'react-router'

import AppShell from '@/App'
import { useAuth } from '@/app/auth'
import { paths } from '@/app/paths'

function RouteLoading() {
  return (
    <div className="catalog-notice" role="status">
      正在加载页面…
    </div>
  )
}

function AuthenticationBoundary() {
  const auth = useAuth()
  const location = useLocation()

  if (!auth.initialized || auth.loading) {
    return (
      <div className="catalog-notice" role="status">
        正在确认登录状态…
      </div>
    )
  }
  if (auth.sessionError) {
    return (
      <div className="catalog-error" role="alert">
        <strong>{auth.sessionError.errorCode}</strong>
        <span>{auth.sessionError.message}</span>
        <Button onClick={() => void auth.retrySession()}>重试</Button>
      </div>
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
            path: '/projects/:projectId/sessions',
            lazy: async () => ({ Component: (await import('@/views/SessionListView')).default }),
          },
          {
            path: '/projects/:projectId/sessions/:id',
            lazy: async () => ({ Component: (await import('@/views/SessionDetailView')).default }),
          },
          {
            path: '/projects/:projectId/assets',
            lazy: async () => ({ Component: (await import('@/views/AssetListView')).default }),
          },
          {
            path: '/projects/:projectId/assets/:id',
            lazy: async () => ({ Component: (await import('@/views/AssetDetailView')).default }),
          },
          {
            path: '/projects/:projectId/annotation-tasks',
            lazy: async () => ({
              Component: (await import('@/views/AnnotationTaskListView')).default,
            }),
          },
          {
            path: '/projects/:projectId/datasets',
            lazy: async () => ({
              Component: (await import('@/views/DatasetVersionListView')).default,
            }),
          },
          {
            path: '/projects/:projectId/datasets/:id',
            lazy: async () => ({
              Component: (await import('@/views/DatasetVersionDetailView')).default,
            }),
          },
          {
            path: '/projects/:projectId/event-logs',
            lazy: async () => ({
              Component: (await import('@/views/CaptureEventLogListView')).default,
            }),
          },
          {
            path: '/projects/:projectId/folders',
            lazy: async () => ({
              Component: (await import('@/views/FolderManagementView')).default,
            }),
          },
          {
            path: paths.uploads,
            lazy: async () => ({ Component: (await import('@/views/UploadProgressView')).default }),
          },
          {
            path: paths.localUpload,
            lazy: async () => ({ Component: (await import('@/views/LocalUploadView')).default }),
          },
          {
            Component: AdministratorBoundary,
            children: [
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
  { path: '/sessions', element: <Navigate replace to={paths.projects} /> },
  { path: '/assets', element: <Navigate replace to={paths.projects} /> },
  { path: '*', element: <Navigate replace to={paths.projects} /> },
])
