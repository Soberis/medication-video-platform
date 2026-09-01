import { App as AntdApp, Button } from 'antd'
import {
  Activity,
  Clapperboard,
  FolderKanban,
  ListChecks,
  LogOut,
  Upload,
  UserRoundCog,
  UsersRound,
  Video,
} from 'lucide-react'
import { useState } from 'react'
import { Link, matchPath, NavLink, Outlet, useLocation, useNavigate, useParams } from 'react-router'

import { useAuth } from '@/app/auth'
import { paths } from '@/app/paths'
import { AppShell as AppFrame, Sidebar } from '@/components/layout/AppShell'
import { StatusBadge } from '@/components/StatusBadge'

const managementRoutePatterns = [
  '/projects/:projectId/members',
  paths.uploads,
  paths.localUpload,
  paths.accounts,
] as const

const iconProps = { 'aria-hidden': true, size: 16, strokeWidth: 1.8 } as const

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
                  aria-label="视频管理"
                  title="视频管理"
                >
                  <Video {...iconProps} />
                  <span className="sidebar-label">视频管理</span>
                </Link>
              ) : null}
              {projectId ? (
                <NavLink
                  to={paths.annotationTasks(projectId)}
                  aria-label="配药动作标注"
                  title="配药动作标注"
                >
                  <ListChecks {...iconProps} />
                  <span className="sidebar-label">配药动作标注</span>
                </NavLink>
              ) : null}
            </div>
            <div
              className="sidebar-nav-group"
              data-active={isManagementRoute || undefined}
              aria-labelledby="management-navigation-label"
            >
              <p id="management-navigation-label" className="sidebar-nav-group-label">
                管理
              </p>
              {isAdministrator ? (
                <NavLink
                  end
                  to={paths.localUpload}
                  aria-label="本地上传（辅助）"
                  title="本地上传（辅助）"
                >
                  <Upload {...iconProps} />
                  <span className="sidebar-label">本地上传（辅助）</span>
                </NavLink>
              ) : null}
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
                <NavLink end to={paths.uploads} aria-label="上传排障" title="上传排障">
                  <Activity {...iconProps} />
                  <span className="sidebar-label">上传排障</span>
                </NavLink>
              ) : null}
              {isAdministrator ? (
                <NavLink end to={paths.accounts} aria-label="账号管理" title="账号管理">
                  <UserRoundCog {...iconProps} />
                  <span className="sidebar-label">账号管理</span>
                </NavLink>
              ) : null}
            </div>
          </nav>
        </Sidebar>
      }
    >
      <Outlet />
    </AppFrame>
  )
}
