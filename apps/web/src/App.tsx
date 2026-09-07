import { App as AntdApp, Button } from 'antd'
import {
  Clapperboard,
  FolderKanban,
  ListChecks,
  LogOut,
  Settings,
  Upload,
  UserRoundCog,
  UsersRound,
  Video,
} from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, matchPath, NavLink, Outlet, useLocation, useNavigate, useParams } from 'react-router'

import { useAuth } from '@/app/auth'
import { getProject } from '@/api/projects'
import { paths } from '@/app/paths'
import { LocalUploadProvider, useLocalUpload } from '@/app/local-upload'
import { AppShell as AppFrame, Sidebar } from '@/components/layout/AppShell'
import { StatusBadge } from '@/components/StatusBadge'

const managementRoutePatterns = [
  '/projects/:projectId/members',
  paths.uploads,
  paths.annotationLabelLibrary(),
  '/projects/:projectId/annotation-label-library',
  paths.accounts,
] as const

const iconProps = { 'aria-hidden': true, size: 16, strokeWidth: 1.8 } as const

function ProjectContext({ projectId }: { projectId: string }) {
  const [name, setName] = useState<string | null>(null)
  useEffect(() => {
    const request = new AbortController()
    void getProject(projectId, request.signal)
      .then((project) => {
        if (!request.signal.aborted) setName(project.display_name)
      })
      .catch(() => {
        /* The page retains its own access and retry state. */
      })
    return () => request.abort()
  }, [projectId])
  return <p className="form-hint">当前项目：{name ?? projectId}</p>
}

function LocalUploadActivity() {
  const { tasks, uploadState, resetUpload } = useLocalUpload()
  const location = useLocation()
  if (tasks.length === 0 || location.pathname === paths.localUpload) return null

  const completedTaskCount = tasks.filter((task) => task.status === 'COMPLETED').length
  const failedTaskCount = tasks.filter((task) => task.status === 'FAILED').length

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
      {uploadState === 'completed' && failedTaskCount === 0 ? (
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
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false)
  const isAdministrator = auth.session?.role === 'ADMIN'
  const isManagementRoute = managementRoutePatterns.some(
    (path) => matchPath({ path, end: true }, location.pathname) !== null,
  )
  const isVideoManagementContext =
    Boolean(projectId) &&
    [paths.captureGroups(projectId), '/projects/:projectId/capture-groups/:captureGroupId'].some(
      (path) => matchPath({ path, end: true }, location.pathname) !== null,
    )

  async function handleLogout() {
    try {
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
          onCollapsedChange={setSidebarCollapsed}
          brand={
            <Link className="app-brand" to={paths.projects} aria-label="返回项目列表">
              <Clapperboard {...iconProps} size={18} />
              <span className="app-brand-copy">
                <strong>配药视频</strong>
                <span>数据管理</span>
              </span>
            </Link>
          }
          footer={
            auth.session ? (
              <div className="account-summary">
                <div className="sidebar-account-copy">
                  <span title={auth.session.username}>{auth.session.username}</span>
                  <StatusBadge tone={isAdministrator ? 'info' : 'default'}>
                    {isAdministrator ? '管理员' : '成员'}
                  </StatusBadge>
                </div>
                <Button
                  className="sidebar-logout"
                  type="text"
                  aria-label="退出登录"
                  title="退出登录"
                  loading={auth.loading}
                  icon={<LogOut {...iconProps} />}
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
              {projectId ? (
                <Link
                  to={paths.captureGroups(projectId)}
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
              <NavLink end to={paths.localUpload} aria-label="上传视频" title="上传视频">
                <Upload {...iconProps} />
                <span className="sidebar-label">上传视频</span>
              </NavLink>
            ) : null}
            {isAdministrator ? (
              <details
                className="sidebar-nav-group"
                open={isManagementRoute}
                data-active={isManagementRoute || undefined}
                aria-labelledby="management-navigation-label"
              >
                <summary
                  id="management-navigation-label"
                  className="sidebar-settings-toggle"
                  title="设置"
                >
                  <Settings {...iconProps} />
                  <span className="sidebar-label">设置</span>
                </summary>
                {projectId && isAdministrator ? (
                  <NavLink
                    end
                    to={paths.memberships(projectId)}
                    aria-label="项目成员"
                    title="项目成员"
                  >
                    <UsersRound {...iconProps} />
                    <span className="sidebar-label">项目成员</span>
                  </NavLink>
                ) : null}
                {isAdministrator ? (
                  <NavLink
                    end
                    to={paths.annotationLabelLibrary()}
                    aria-label="标签库"
                    title="标签库"
                  >
                    <ListChecks {...iconProps} />
                    <span className="sidebar-label">标签库</span>
                  </NavLink>
                ) : null}
                {isAdministrator ? (
                  <NavLink end to={paths.accounts} aria-label="账号管理" title="账号管理">
                    <UserRoundCog {...iconProps} />
                    <span className="sidebar-label">账号管理</span>
                  </NavLink>
                ) : null}
              </details>
            ) : null}
          </nav>
        </Sidebar>
      }
    >
      <LocalUploadProvider>
        <LocalUploadActivity />
        {projectId && location.pathname !== paths.captureGroups(projectId) ? (
          <ProjectContext key={projectId} projectId={projectId} />
        ) : null}
        <Outlet />
      </LocalUploadProvider>
    </AppFrame>
  )
}
