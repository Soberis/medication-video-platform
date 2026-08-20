import { App as AntdApp, Button } from 'antd'
import { useEffect, useRef } from 'react'
import { Link, matchPath, NavLink, Outlet, useLocation, useNavigate, useParams } from 'react-router'

import { useAuth } from '@/app/auth'
import { paths } from '@/app/paths'
import { StatusBadge } from '@/components/StatusBadge'

const managementRoutePatterns = [
  '/projects/:projectId/members',
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
  const managementMenuRef = useRef<HTMLDetailsElement>(null)
  const isManagementRoute = managementRoutePatterns.some(
    (path) => matchPath({ path, end: true }, location.pathname) !== null,
  )
  const isVideoManagementContext =
    Boolean(projectId) &&
    [paths.videos(projectId), '/projects/:projectId/assets/:id'].some(
      (path) => matchPath({ path, end: true }, location.pathname) !== null,
    )

  useEffect(() => {
    function closeOnOutsidePointer(event: PointerEvent) {
      const menu = managementMenuRef.current
      if (menu && menu.open && !menu.contains(event.target as Node)) {
        menu.removeAttribute('open')
      }
    }

    function closeOnEscape(event: KeyboardEvent) {
      const menu = managementMenuRef.current
      if (!menu) return
      if (event.key !== 'Escape' || !menu.open) return
      menu.removeAttribute('open')
      menu.querySelector<HTMLElement>('summary')?.focus()
    }

    document.addEventListener('pointerdown', closeOnOutsidePointer)
    document.addEventListener('keydown', closeOnEscape)
    return () => {
      document.removeEventListener('pointerdown', closeOnOutsidePointer)
      document.removeEventListener('keydown', closeOnEscape)
    }
  }, [])

  useEffect(() => {
    managementMenuRef.current?.removeAttribute('open')
  }, [location.pathname])

  async function handleLogout() {
    try {
      await auth.logout()
      await navigate(paths.login, { replace: true })
    } catch {
      void message.error('退出登录未完成，请稍后重试。')
    }
  }

  function closeManagementMenu() {
    managementMenuRef.current?.removeAttribute('open')
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
          {projectId ? (
            <Link
              to={paths.videos(projectId)}
              aria-current={isVideoManagementContext ? 'page' : undefined}
            >
              视频管理
            </Link>
          ) : null}
          {projectId ? <NavLink to={paths.annotationTasks(projectId)}>配药动作标注</NavLink> : null}
          <details
            ref={managementMenuRef}
            className={`nav-more${isManagementRoute ? ' nav-more-active' : ''}`}
          >
            <summary>管理</summary>
            <div className="nav-more-menu">
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
            <StatusBadge tone={isAdministrator ? 'processing' : 'default'}>
              {isAdministrator ? '管理员' : '普通用户'}
            </StatusBadge>
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
