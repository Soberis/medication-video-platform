import { App as AntdApp, Button, Tag } from 'antd'
import { Link, matchPath, NavLink, Outlet, useLocation, useNavigate, useParams } from 'react-router'

import { useAuth } from '@/app/auth'
import { paths } from '@/app/paths'

const managementRoutePatterns = [
  '/projects/:projectId/sessions/*',
  '/projects/:projectId/datasets/*',
  '/projects/:projectId/assets/*',
  '/projects/:projectId/members',
  '/projects/:projectId/event-logs',
  paths.uploads,
  paths.localUpload,
  paths.accounts,
] as const

export default function AppShell() {
  const auth = useAuth()
  const { message } = AntdApp.useApp()
  const navigate = useNavigate()
  const location = useLocation()
  const { projectId = '' } = useParams()
  const isAdministrator = auth.session?.role === 'ADMIN'
  const isManagementRoute = managementRoutePatterns.some(
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

  function closeManagementMenu(event: React.MouseEvent<HTMLAnchorElement>) {
    event.currentTarget.closest('details')?.removeAttribute('open')
  }

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content">
        跳到主要内容
      </a>
      <header className="app-header">
        <Link className="app-brand" to={paths.projects} aria-label="返回项目列表">
          配药视频数据管理平台
        </Link>
        <nav className="primary-nav" aria-label="主导航">
          <NavLink end to={paths.projects}>
            项目
          </NavLink>
          {projectId ? <NavLink to={paths.videos(projectId)}>视频管理</NavLink> : null}
          {projectId ? <NavLink to={paths.annotationTasks(projectId)}>配药动作标注</NavLink> : null}
          <details className={`nav-more${isManagementRoute ? ' nav-more-active' : ''}`}>
            <summary>管理</summary>
            <div className="nav-more-menu">
              {projectId ? (
                <NavLink end to={paths.sessions(projectId)} onClick={closeManagementMenu}>
                  多相机查看
                </NavLink>
              ) : null}
              {isAdministrator ? (
                <NavLink end to={paths.localUpload} onClick={closeManagementMenu}>
                  本地上传（辅助）
                </NavLink>
              ) : null}
              {projectId && isAdministrator ? (
                <NavLink end to={paths.memberships(projectId)} onClick={closeManagementMenu}>
                  项目成员
                </NavLink>
              ) : null}
              {projectId && isAdministrator ? (
                <NavLink end to={paths.eventLogs(projectId)} onClick={closeManagementMenu}>
                  采集事件日志
                </NavLink>
              ) : null}
              {isAdministrator ? (
                <NavLink end to={paths.uploads} onClick={closeManagementMenu}>
                  上传排障
                </NavLink>
              ) : null}
              {isAdministrator ? (
                <NavLink end to={paths.accounts} onClick={closeManagementMenu}>
                  账号管理
                </NavLink>
              ) : null}
            </div>
          </details>
        </nav>
        {auth.session ? (
          <div className="account-summary">
            <span>{auth.session.username}</span>
            <Tag color={isAdministrator ? 'blue' : 'default'}>
              {isAdministrator ? '管理员' : '普通用户'}
            </Tag>
            <Button type="link" loading={auth.loading} onClick={() => void handleLogout()}>
              退出登录
            </Button>
          </div>
        ) : null}
      </header>
      <main id="main-content" tabIndex={-1}>
        <Outlet />
      </main>
    </div>
  )
}
