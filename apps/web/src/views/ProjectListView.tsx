import { App as AntdApp, Button, Card, Dropdown, Empty, Tag } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'

import { CatalogApiError } from '@/api/catalog'
import {
  archiveProject,
  createProject,
  getProjectList,
  renameProject,
  type DataProject,
} from '@/api/projects'
import { paths } from '@/app/paths'
import { usePromptDialog } from '@/components/PromptDialog'

type LoadState = 'loading' | 'success' | 'error'

export default function ProjectListView() {
  const { message } = AntdApp.useApp()
  const prompt = usePromptDialog()
  const [projects, setProjects] = useState<DataProject[]>([])
  const [loadState, setLoadState] = useState<LoadState>('loading')
  const [errorCode, setErrorCode] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const [canCreateProjects, setCanCreateProjects] = useState(false)
  const [isCreating, setIsCreating] = useState(false)
  const [renamingProjectId, setRenamingProjectId] = useState<string | null>(null)
  const [archivingProjectId, setArchivingProjectId] = useState<string | null>(null)
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
        error instanceof CatalogApiError ? error.message : '暂时无法读取授权项目，请稍后重试。',
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

  async function createDataProject() {
    if (isCreating) return
    const value = await prompt({
      title: '新建项目',
      description: '创建后，当前管理员会自动成为该项目的第一位项目管理员。',
      confirmText: '创建项目',
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
      void message.success('项目已创建，当前身份已获得该项目的管理员权限。')
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
      description: '仅修改项目显示名称，不会影响项目成员或视频归属。',
      confirmText: '保存名称',
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

  async function archiveDataProject(project: DataProject) {
    if (archivingProjectId) return
    const value = await prompt({
      title: '归档项目',
      description:
        '归档会立即停止项目访问并从日常列表移除，但不会物理删除视频、对象或审计记录。请输入完整项目名称确认。',
      confirmText: '确认归档',
      placeholder: project.display_name,
      validate: (input) =>
        input.trim() === project.display_name ? null : '输入的项目名称不匹配，未执行归档。',
    })
    if (value === null) return
    setArchivingProjectId(project.project_id)
    try {
      await archiveProject(project.project_id)
      void message.success('项目已归档，视频与审计记录仍被保留。')
      await loadProjects()
    } catch (error) {
      void message.error(
        error instanceof CatalogApiError ? error.message : '项目未归档，请稍后重试。',
      )
    } finally {
      setArchivingProjectId(null)
    }
  }

  return (
    <>
      <section className="page-heading project-page-heading" aria-labelledby="projects-title">
        <div>
          <h1 id="projects-title">项目</h1>
          <p>选择项目，继续管理配药操作和视频。</p>
        </div>
        <div className="detail-actions">
          <Button loading={loadState === 'loading'} onClick={() => void loadProjects()}>
            刷新
          </Button>
          {canCreateProjects ? (
            <Button type="primary" loading={isCreating} onClick={() => void createDataProject()}>
              新建项目
            </Button>
          ) : null}
        </div>
      </section>
      {loadState === 'loading' ? (
        <div className="catalog-notice" role="status">
          正在读取授权项目…
        </div>
      ) : null}
      {loadState === 'error' ? (
        <div className="catalog-error" role="alert">
          <strong>{errorCode}</strong>
          <span>{errorMessage}</span>
          <Button onClick={() => void loadProjects()}>重试</Button>
        </div>
      ) : null}
      {projects.length > 0 ? (
        <Card className="project-list-card" variant="outlined">
          <ul className="project-list" aria-label="已加入的项目">
            {projects.map((project) => (
              <li key={project.project_id} className="project-list-item">
                <div className="project-list-name">
                  <div className="project-list-heading">
                    <strong>{project.display_name}</strong>
                    <Tag color={project.current_role === 'ADMIN' ? 'blue' : 'default'}>
                      {project.current_role === 'ADMIN' ? '管理员' : '普通用户'}
                    </Tag>
                  </div>
                  <p>在项目内统一管理配药操作、视频资产和配药动作标注。</p>
                </div>
                <div className="project-list-actions">
                  <Link
                    className="project-open-link"
                    to={paths.videos(project.project_id)}
                    aria-label={`进入项目：${project.display_name}`}
                  >
                    进入项目
                  </Link>
                  {project.current_role === 'ADMIN' ? (
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
                      <Button type="link" aria-label={`管理项目：${project.display_name}`}>
                        管理
                      </Button>
                    </Dropdown>
                  ) : null}
                </div>
              </li>
            ))}
          </ul>
        </Card>
      ) : loadState === 'success' ? (
        <Empty description="当前账号尚未加入任何项目。管理员可新建首个项目；普通用户需要由项目管理员添加为成员。">
          {canCreateProjects ? (
            <Button type="primary" loading={isCreating} onClick={() => void createDataProject()}>
              新建项目
            </Button>
          ) : null}
        </Empty>
      ) : null}
    </>
  )
}
