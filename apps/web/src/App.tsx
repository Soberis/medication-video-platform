import { App as AntdApp, Button } from 'antd'
import {
  Clapperboard,
  FolderKanban,
  ListChecks,
  LogOut,
  UserRoundCog,
  UsersRound,
  Video,
} from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Link, matchPath, NavLink, Outlet, useLocation, useNavigate, useParams } from 'react-router'

import { useAuth } from '@/app/auth'
import { documentTitleFor } from '@/app/document-title'
import { paths } from '@/app/paths'
import { LocalUploadProvider, useLocalUpload } from '@/app/local-upload'
import { ErrorBoundary } from '@/components/ErrorBoundary'
import { AppShell as AppFrame, Sidebar } from '@/components/layout/AppShell'

const iconProps = { 'aria-hidden': true, size: 16, strokeWidth: 1.8 } as const

const UPLOAD_ACTIVITY_AUTO_DISMISS_MS = 6000

function LocalUploadActivity() {
  const { tasks, uploadState, resetUpload } = useLocalUpload()
  const location = useLocation()

  const completedTaskCount = tasks.filter((task) => task.status === 'COMPLETED').length
  const failedTaskCount = tasks.filter((task) => task.status === 'FAILED').length
  const isAllCompleted = uploadState === 'completed' && failedTaskCount === 0 && tasks.length > 0
  const isOutsideUploadPage = location.pathname !== paths.localUpload

  useEffect(() => {
    if (!isAllCompleted || !isOutsideUploadPage) return
    const timer = window.setTimeout(() => {
      resetUpload()
    }, UPLOAD_ACTIVITY_AUTO_DISMISS_MS)
    return () => window.clearTimeout(timer)
  }, [isAllCompleted, isOutsideUploadPage, resetUpload])

  if (tasks.length === 0 || location.pathname === paths.localUpload) return null

  return (
    <aside className="app-upload-activity" aria-live="polite">
      <span className="muted-text">
        {uploadState === 'uploading'
          ? `上传中 · 已确认 ${completedTaskCount} / ${tasks.length} 个视频`
          : failedTaskCount > 0
            ? `上传完成 · ${completedTaskCount} 成功，${failedTaskCount} 需处理`
            : `上传完成 · 已确认 ${completedTaskCount} 个视频`}
      </span>
      <Link className="text-link" to={paths.localUpload}>
        查看上传进度
      </Link>
      {isAllCompleted ? (
        <Button size="small" type="text" onClick={resetUpload}>
          知道了
        </Button>
      ) : null}
    </aside>
  )
}

export default function AppShell() {
  const auth = useAuth()
  const { message } = AntdApp.useApp()
  const navigate = useNavigate()
  const location = useLocation()
  const { projectId = '' } = useParams()
  const isVideoWorkbench =
    matchPath(
      { path: '/projects/:projectId/capture-groups/:captureGroupId', end: true },
      location.pathname,
    ) !== null

  const [sidebarCollapsed, setSidebarCollapsed] = useState(() => {
    try {
      const isDirectWorkbench =
        matchPath(
          { path: '/projects/:projectId/capture-groups/:captureGroupId', end: true },
          window.location.pathname,
        ) !== null
      if (isDirectWorkbench) return true
      return localStorage.getItem('mvp:sidebar_collapsed') === 'true'
    } catch {
      return false
    }
  })

  const wasInWorkbenchRef = useRef(isVideoWorkbench)
  const userCollapsedBeforeWorkbenchRef = useRef<boolean | null>(
    isVideoWorkbench ? false : null,
  )

  useEffect(() => {
    if (isVideoWorkbench) {
      if (!wasInWorkbenchRef.current) {
        userCollapsedBeforeWorkbenchRef.current = sidebarCollapsed
        setSidebarCollapsed(true)
        wasInWorkbenchRef.current = true
      }
    } else if (wasInWorkbenchRef.current) {
      wasInWorkbenchRef.current = false
      if (userCollapsedBeforeWorkbenchRef.current !== null) {
        setSidebarCollapsed(userCollapsedBeforeWorkbenchRef.current)
        userCollapsedBeforeWorkbenchRef.current = null
      }
    }
  }, [isVideoWorkbench, sidebarCollapsed])

  const handleSidebarCollapsedChange = (collapsed: boolean) => {
    setSidebarCollapsed(collapsed)
    if (!isVideoWorkbench) {
      try {
        localStorage.setItem('mvp:sidebar_collapsed', String(collapsed))
      } catch {
        // ignore
      }
    }
  }
  const isAdministrator = auth.session?.role === 'ADMIN'

  useEffect(() => {
    document.title = documentTitleFor(location.pathname)
  }, [location.pathname])

  const [activeProjectId, setActiveProjectId] = useState<string>(() => {
    try {
      return sessionStorage.getItem('mvp:active_project_id') ?? ''
    } catch {
      return ''
    }
  })

  // 当当前路由携带明确的项目标识时，在渲染周期同步活跃项目，免去额外 effect 与级联重渲染
  if (projectId && projectId !== activeProjectId) {
    setActiveProjectId(projectId)
    try {
      sessionStorage.setItem('mvp:active_project_id', projectId)
    } catch {
      // ignore
    }
  }

  // 当用户显式回到项目选择总览根页（/projects）时，不固化特定项目的视频和成员上下文；
  // 但在跨入账号管理、标签库、上传等全局设置/管理页时，延续此前正在处理的项目上下文，
  // 避免“点击账号管理后项目成员消失、必须重新进入项目”的割裂感。
  const isProjectSelectionRoot =
    matchPath({ path: paths.projects, end: true }, location.pathname) !== null
  const contextualProjectId = isProjectSelectionRoot ? '' : projectId || activeProjectId

  const isVideoManagementContext =
    Boolean(contextualProjectId) &&
    [
      paths.captureGroups(contextualProjectId),
      '/projects/:projectId/capture-groups/:captureGroupId',
    ].some((path) => matchPath({ path, end: true }, location.pathname) !== null)

  async function handleLogout() {
    try {
      try {
        sessionStorage.removeItem('mvp:active_project_id')
      } catch {
        // ignore
      }
      setActiveProjectId('')
      await auth.logout()
      await navigate(paths.login, { replace: true })
    } catch {
      void message.error('退出登录未完成，请稍后重试。')
    }
  }

  return (
    <AppFrame
      collapsed={sidebarCollapsed}
      sidebar={
        <Sidebar
          collapsed={sidebarCollapsed}
          onCollapsedChange={handleSidebarCollapsedChange}
          brand={
            <Link className="app-brand" to={paths.projects} aria-label="返回项目列表">
              <div className="app-brand-badge" aria-hidden="true">
                <Clapperboard size={15} strokeWidth={2.2} />
              </div>
              <span className="app-brand-copy">
                <strong>配药视频平台</strong>
                <span className="visually-hidden">配药视频数据管理</span>
              </span>
            </Link>
          }
          footer={
            auth.session ? (
              <div className="account-summary">
                <div className="sidebar-account-avatar" aria-hidden="true">
                  {auth.session.username.slice(0, 1).toUpperCase()}
                </div>
                <div className="sidebar-account-copy">
                  <span className="sidebar-account-name" title={auth.session.username}>
                    {auth.session.username}
                  </span>
                  <span className="sidebar-account-role">
                    {isAdministrator ? '系统管理员' : '项目成员'}
                  </span>
                </div>
                <Button
                  className="sidebar-logout"
                  type="text"
                  aria-label="退出登录"
                  title="退出登录"
                  loading={auth.loading}
                  icon={<LogOut size={16} strokeWidth={1.8} />}
                  onClick={() => void handleLogout()}
                >
                  <span className="sidebar-label">退出登录</span>
                </Button>
              </div>
            ) : null
          }
        >
          <nav className="primary-nav" aria-label="主导航">
            <div className="sidebar-nav-group">
              <p className="sidebar-nav-group-label">工作区</p>
              <NavLink end to={paths.projects} aria-label="项目" title="项目">
                <FolderKanban {...iconProps} />
                <span className="sidebar-label">项目</span>
              </NavLink>
              {contextualProjectId ? (
                <Link
                  to={paths.captureGroups(contextualProjectId)}
                  aria-current={isVideoManagementContext ? 'page' : undefined}
                  aria-label="视频"
                  title="视频"
                >
                  <Video {...iconProps} />
                  <span className="sidebar-label">视频</span>
                </Link>
              ) : null}
            </div>
            {isAdministrator ? (
              <div className="sidebar-nav-group">
                <p className="sidebar-nav-group-label">设置</p>
                <NavLink end to={paths.annotationLabelLibrary()} aria-label="标签库" title="标签库">
                  <ListChecks {...iconProps} />
                  <span className="sidebar-label">标签库</span>
                </NavLink>
                {contextualProjectId ? (
                  <NavLink
                    end
                    to={paths.memberships(contextualProjectId)}
                    aria-label="项目成员"
                    title="项目成员"
                  >
                    <UsersRound {...iconProps} />
                    <span className="sidebar-label">项目成员</span>
                  </NavLink>
                ) : null}
                <NavLink end to={paths.accounts} aria-label="账号管理" title="账号管理">
                  <UserRoundCog {...iconProps} />
                  <span className="sidebar-label">账号管理</span>
                </NavLink>
              </div>
            ) : null}
          </nav>
        </Sidebar>
      }
    >
      <LocalUploadProvider>
        <ErrorBoundary title="应用遇到异常">
          <LocalUploadActivity />
          <Outlet />
        </ErrorBoundary>
      </LocalUploadProvider>
    </AppFrame>
  )
}
