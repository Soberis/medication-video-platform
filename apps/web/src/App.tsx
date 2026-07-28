import { App as AntdApp, Button, Tag } from 'antd'
import { Link, NavLink, Outlet, useLocation, useNavigate, useParams } from 'react-router'

import { useAuth } from '@/app/auth'
import { paths } from '@/app/paths'

const managementPaths = ['/folders', '/members', '/event-logs', '/uploads', '/admin/users']

export default function AppShell() {
  const auth = useAuth()
  const { message } = AntdApp.useApp()
  const navigate = useNavigate()
  const location = useLocation()
  const { projectId = '' } = useParams()
  const isAdministrator = auth.session?.role === 'ADMIN'
  const isManagementRoute = managementPaths.some((path) => location.pathname.includes(path))

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
          <NavLink to={paths.projects}>项目</NavLink>
          {projectId ? <NavLink to={paths.sessions(projectId)}>配药操作</NavLink> : null}
          {projectId ? <NavLink to={paths.assets(projectId)}>视频资产</NavLink> : null}
          {projectId ? <NavLink to={paths.annotationTasks(projectId)}>标注任务</NavLink> : null}
          {projectId ? <NavLink to={paths.datasets(projectId)}>数据集草稿</NavLink> : null}
          <NavLink to={paths.localUpload}>本地上传</NavLink>
          <details className={`nav-more${isManagementRoute ? ' nav-more-active' : ''}`}>
            <summary>管理</summary>
            <div className="nav-more-menu">
              {projectId ? (
                <NavLink to={paths.folders(projectId)} onClick={closeManagementMenu}>
                  文件夹
                </NavLink>
              ) : null}
              {projectId && isAdministrator ? (
                <NavLink to={paths.memberships(projectId)} onClick={closeManagementMenu}>
                  项目成员
                </NavLink>
              ) : null}
              {projectId ? (
                <NavLink to={paths.eventLogs(projectId)} onClick={closeManagementMenu}>
                  采集事件日志
                </NavLink>
              ) : null}
              <NavLink to={paths.uploads} onClick={closeManagementMenu}>
                上传排障
              </NavLink>
              {isAdministrator ? (
                <NavLink to={paths.accounts} onClick={closeManagementMenu}>
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
