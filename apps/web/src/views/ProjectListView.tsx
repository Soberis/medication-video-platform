import { App as AntdApp, Button, Dropdown, Input } from 'antd'
import { ArrowRight, Calendar, FolderKanban, Search, Settings } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router'

import { CatalogApiError } from '@/api/catalog'
import {
  archiveProject,
  createProject,
  getProjectList,
  renameProject,
  type DataProject,
} from '@/api/projects'
import { paths } from '@/app/paths'
import { PageHeader } from '@/components/PageHeader'
import { usePromptDialog } from '@/components/PromptDialog'
import { RefreshButton } from '@/components/RefreshButton'
import { StatusBadge } from '@/components/StatusBadge'
import { EmptyState, ErrorState, LoadingState } from '@/components/WorkspaceFeedback'

type LoadState = 'loading' | 'success' | 'error'

export default function ProjectListView() {
  const { message, modal } = AntdApp.useApp()
  const navigate = useNavigate()
  const prompt = usePromptDialog()
  const [projects, setProjects] = useState<DataProject[]>([])
  const [loadState, setLoadState] = useState<LoadState>('loading')
  const [errorCode, setErrorCode] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const [canCreateProjects, setCanCreateProjects] = useState(false)
  const [isCreating, setIsCreating] = useState(false)
  const [renamingProjectId, setRenamingProjectId] = useState<string | null>(null)
  const [archivingProjectId, setArchivingProjectId] = useState<string | null>(null)
  const [searchKeyword, setSearchKeyword] = useState('')
  const activeRequest = useRef<AbortController | null>(null)

  const loadProjects = useCallback(async () => {
    activeRequest.current?.abort()
    const request = new AbortController()
    activeRequest.current = request
    setLoadState('loading')
    try {
      const response = await getProjectList(request.signal)
      if (activeRequest.current !== request) return
      setProjects(response.items)
      setCanCreateProjects(response.can_create_projects)
      setLoadState('success')
    } catch (error) {
      if (request.signal.aborted || activeRequest.current !== request) return
      setProjects([])
      setCanCreateProjects(false)
      setLoadState('error')
      setErrorCode(error instanceof CatalogApiError ? error.errorCode : 'PROJECT_REQUEST_FAILED')
      setErrorMessage(
        error instanceof CatalogApiError ? error.message : '暂时无法读取项目，请稍后重试。',
      )
    } finally {
      if (activeRequest.current === request) activeRequest.current = null
    }
  }, [])

  useEffect(() => {
    const timer = window.setTimeout(() => void loadProjects(), 0)
    return () => {
      window.clearTimeout(timer)
      activeRequest.current?.abort()
    }
  }, [loadProjects])

  const filteredProjects = useMemo(() => {
    const keyword = searchKeyword.trim().toLowerCase()
    if (!keyword) return projects
    return projects.filter((project) =>
      project.display_name.toLowerCase().includes(keyword),
    )
  }, [projects, searchKeyword])

  async function createDataProject() {
    if (isCreating) return
    const value = await prompt({
      title: '新建项目',
      description: '创建后，当前管理员将自动成为该项目的项目管理员。',
      confirmText: '创建项目',
      inputLabel: '项目名称',
      placeholder: '例如：病区 A 合成数据验证',
      validate: (input) => {
        if (!input.trim()) return '请输入项目名称。'
        return input.trim().length > 128 ? '项目名称不能超过 128 个字符。' : null
      },
    })
    if (value === null) return
    setIsCreating(true)
    try {
      await createProject(value.trim())
      void message.success('项目已创建。当前账号已成为项目管理员。')
      await loadProjects()
    } catch (error) {
      void message.error(
        error instanceof CatalogApiError ? error.message : '项目创建未完成，请稍后重试。',
      )
    } finally {
      setIsCreating(false)
    }
  }

  async function renameDataProject(project: DataProject) {
    if (renamingProjectId) return
    const value = await prompt({
      title: '修改项目名称',
      description: '只修改项目显示名称，不影响项目成员或视频归属。',
      confirmText: '保存名称',
      inputLabel: '项目名称',
      initialValue: project.display_name,
      validate: (input) => {
        if (!input.trim()) return '请输入项目名称。'
        return input.trim().length > 128 ? '项目名称不能超过 128 个字符。' : null
      },
    })
    if (value === null) return
    setRenamingProjectId(project.project_id)
    try {
      await renameProject(project.project_id, value.trim())
      void message.success('项目名称已更新。')
      await loadProjects()
    } catch (error) {
      void message.error(
        error instanceof CatalogApiError ? error.message : '项目名称未更新，请稍后重试。',
      )
    } finally {
      setRenamingProjectId(null)
    }
  }

  function archiveDataProject(project: DataProject) {
    if (archivingProjectId) return
    modal.confirm({
      title: '归档项目',
      content: (
        <div className="project-archive-confirmation">
          <p>归档后将立即停止项目访问并从日常列表移除；视频、对象和审计记录不会被物理删除。</p>
          <div className="project-archive-target">
            <span className="project-archive-target-label">将要归档的项目</span>
            <strong title={project.display_name}>{project.display_name}</strong>
          </div>
        </div>
      ),
      okText: '确认归档',
      okType: 'danger',
      cancelText: '取消',
      focusable: { autoFocusButton: 'cancel' },
      closable: { 'aria-label': '关闭' },
      mask: { closable: false },
      async onOk() {
        setArchivingProjectId(project.project_id)
        try {
          await archiveProject(project.project_id)
          void message.success('项目已归档，视频和审计记录仍被保留。')
          await loadProjects()
        } catch (error) {
          void message.error(
            error instanceof CatalogApiError ? error.message : '项目未归档，请稍后重试。',
          )
          throw error
        } finally {
          setArchivingProjectId(null)
        }
      },
    })
  }

  return (
    <>
      <PageHeader
        className="project-page-heading"
        titleId="projects-title"
        eyebrow="工作台"
        title="项目"
        actions={
          <>
            {canCreateProjects ? (
              <Button type="primary" loading={isCreating} onClick={() => void createDataProject()}>
                新建项目
              </Button>
            ) : null}
            <RefreshButton loading={loadState === 'loading'} onClick={() => void loadProjects()} />
          </>
        }
      />
      {loadState === 'loading' ? <LoadingState>正在加载项目…</LoadingState> : null}
      {loadState === 'error' ? (
        <ErrorState code={errorCode} onRetry={() => void loadProjects()}>
          {errorMessage}
        </ErrorState>
      ) : null}
      {projects.length > 0 ? (
        <section className="project-list-section" aria-label="项目列表">
          <div className="project-list-toolbar">
            <div className="project-list-toolbar-actions">
              {projects.length > 2 || searchKeyword ? (
                <Input
                  className="project-list-search"
                  placeholder="搜索项目名称…"
                  allowClear
                  prefix={<Search size={14} className="app-search-icon" />}
                  value={searchKeyword}
                  onChange={(e) => setSearchKeyword(e.target.value)}
                  style={{ width: 220 }}
                />
              ) : null}
              <span className="project-count-summary">共 {filteredProjects.length} 个项目</span>
            </div>
          </div>
          {filteredProjects.length > 0 ? (
            <ul className="project-grid" aria-label="已加入项目">
              {filteredProjects.map((project) => (
                <li
                  key={project.project_id}
                  className="project-card"
                  onClick={(event) => {
                    const target = event.target as HTMLElement
                    if (
                      target.closest('a') ||
                      target.closest('button') ||
                      target.closest('.ant-dropdown-trigger') ||
                      (window.getSelection()?.toString().length ?? 0) > 0
                    ) {
                      return
                    }
                    void navigate(paths.captureGroups(project.project_id))
                  }}
                >
                  <div className="project-card-header">
                    <div className="project-card-avatar">
                      <FolderKanban aria-hidden="true" size={20} strokeWidth={1.8} />
                    </div>
                    <div className="project-card-title-group">
                      <strong title={project.display_name} className="project-card-title">
                        {project.display_name}
                      </strong>
                      <div className="project-card-badges">
                        <StatusBadge
                          className="project-role-badge"
                          tone={project.current_role === 'ADMIN' ? 'info' : 'default'}
                        >
                          {project.current_role === 'ADMIN' ? '项目管理员' : '成员'}
                        </StatusBadge>
                      </div>
                    </div>
                    {project.current_role === 'ADMIN' ? (
                      <div onClick={(event) => event.stopPropagation()}>
                        <Dropdown
                          trigger={['click']}
                          menu={{
                            items: [
                              {
                                key: 'rename',
                                label: '修改名称',
                                disabled: renamingProjectId === project.project_id,
                              },
                              {
                                key: 'archive',
                                label: '归档项目',
                                danger: true,
                                disabled: archivingProjectId === project.project_id,
                              },
                            ],
                            onClick: ({ key }) =>
                              void (key === 'rename'
                                ? renameDataProject(project)
                                : archiveDataProject(project)),
                          }}
                        >
                          <Button
                            type="text"
                            size="small"
                            className="project-card-more-btn"
                            aria-label={`管理项目：${project.display_name}`}
                            icon={<Settings size={14} strokeWidth={1.8} />}
                          >
                            管理
                          </Button>
                        </Dropdown>
                      </div>
                    ) : null}
                  </div>
                  <div className="project-card-body">
                    <div className="project-card-meta-row">
                      <span className="project-card-meta-label">
                        <Calendar size={13} strokeWidth={1.8} />
                        创建时间
                      </span>
                      <span className="project-card-meta-value">
                        {project.created_at ? project.created_at.slice(0, 10) : '—'}
                      </span>
                    </div>
                  </div>
                  <div className="project-card-footer" onClick={(event) => event.stopPropagation()}>
                    <Link
                      className="project-open-link"
                      to={paths.captureGroups(project.project_id)}
                      aria-label={`进入项目：${project.display_name}`}
                    >
                      <span>进入项目</span>
                      <ArrowRight aria-hidden="true" size={14} strokeWidth={2} />
                    </Link>
                  </div>
                </li>
              ))}
            </ul>
          ) : (
            <div className="project-search-empty">没有找到包含“{searchKeyword}”的项目</div>
          )}
        </section>
      ) : loadState === 'success' ? (
        <EmptyState description="当前账号尚未加入项目。平台管理员可新建项目；成员需由项目管理员添加。">
          {canCreateProjects ? (
            <Button type="primary" loading={isCreating} onClick={() => void createDataProject()}>
              新建项目
            </Button>
          ) : null}
        </EmptyState>
      ) : null}
    </>
  )
}
