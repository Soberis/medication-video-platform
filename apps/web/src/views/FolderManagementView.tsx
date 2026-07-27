import { Alert, App as AntdApp, Button, Card, Empty, Table, Tag } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'

import { CatalogApiError } from '@/api/catalog'
import {
  createAssetFolder,
  deleteAssetFolder,
  getProject,
  listAssetFolders,
  renameAssetFolder,
  type AssetFolder,
  type ProjectRole,
} from '@/api/projects'
import { paths } from '@/app/paths'
import { usePromptDialog } from '@/components/PromptDialog'

export default function FolderManagementView() {
  const { projectId = '' } = useParams()
  const { message, modal } = AntdApp.useApp()
  const prompt = usePromptDialog()
  const [folders, setFolders] = useState<AssetFolder[]>([])
  const [projectRole, setProjectRole] = useState<ProjectRole | null>(null)
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [errorCode, setErrorCode] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const [isMutating, setIsMutating] = useState(false)
  const activeRequest = useRef<AbortController | null>(null)
  const canManageFolders = projectRole === 'ADMIN'

  const loadFolders = useCallback(async () => {
    activeRequest.current?.abort()
    const request = new AbortController()
    activeRequest.current = request
    setLoadState('loading')
    setErrorCode('')
    setErrorMessage('')
    try {
      const [project, listedFolders] = await Promise.all([
        getProject(projectId, request.signal),
        listAssetFolders(projectId, request.signal),
      ])
      if (activeRequest.current !== request) return
      setProjectRole(project.current_role)
      setFolders(listedFolders)
      setLoadState('success')
    } catch (error) {
      if (request.signal.aborted || activeRequest.current !== request) return
      setFolders([])
      setProjectRole(null)
      setLoadState('error')
      setErrorCode(error instanceof CatalogApiError ? error.errorCode : 'FOLDER_REQUEST_FAILED')
      setErrorMessage(
        error instanceof CatalogApiError ? error.message : '暂时无法读取项目文件夹，请稍后重试。',
      )
    } finally {
      if (activeRequest.current === request) activeRequest.current = null
    }
  }, [projectId])

  useEffect(() => {
    const timer = window.setTimeout(() => void loadFolders(), 0)
    return () => {
      window.clearTimeout(timer)
      activeRequest.current?.abort()
    }
  }, [loadFolders])

  function showCommandError(error: unknown) {
    void message.error(
      error instanceof CatalogApiError ? error.message : '文件夹操作未完成，请稍后重试。',
    )
  }

  async function createFolder() {
    if (!canManageFolders || isMutating) return
    const value = await prompt({
      title: '新建文件夹',
      description: '文件夹仅用于当前项目内的一层视频分类。',
      confirmText: '创建文件夹',
      placeholder: '例如：待标注',
      validate: (input) => (input.trim() ? null : '请输入文件夹名称。'),
    })
    if (value === null) return
    setIsMutating(true)
    try {
      await createAssetFolder(projectId, value.trim())
      void message.success('文件夹已创建。')
      await loadFolders()
    } catch (error) {
      showCommandError(error)
    } finally {
      setIsMutating(false)
    }
  }

  async function renameFolder(folder: AssetFolder) {
    if (!canManageFolders || isMutating) return
    const value = await prompt({
      title: '重命名文件夹',
      description: '修改仅影响当前项目内的分类名称。',
      confirmText: '保存名称',
      initialValue: folder.display_name,
      validate: (input) => (input.trim() ? null : '请输入文件夹名称。'),
    })
    if (value === null) return
    setIsMutating(true)
    try {
      await renameAssetFolder(projectId, folder.folder_id, value.trim())
      void message.success('文件夹名称已更新。')
      await loadFolders()
    } catch (error) {
      showCommandError(error)
    } finally {
      setIsMutating(false)
    }
  }

  function removeFolder(folder: AssetFolder) {
    if (!canManageFolders || isMutating) return
    modal.confirm({
      title: '确认删除文件夹',
      content: `删除“${folder.display_name}”只会取消其中视频的分类，不会删除任何视频或改变项目权限。`,
      okText: '删除文件夹',
      cancelText: '取消',
      okButtonProps: { danger: true },
      async onOk() {
        setIsMutating(true)
        try {
          await deleteAssetFolder(projectId, folder.folder_id)
          void message.success('文件夹已删除，原分类视频已变为未分类。')
          await loadFolders()
        } catch (error) {
          showCommandError(error)
          throw error
        } finally {
          setIsMutating(false)
        }
      },
    })
  }

  return (
    <>
      <nav className="breadcrumb" aria-label="面包屑">
        <Link to={paths.projects}>项目</Link>
        <span aria-hidden="true">/</span>
        <span>文件夹</span>
      </nav>
      <section className="page-heading" aria-labelledby="folders-title">
        <div>
          <p className="app-eyebrow">项目内一层分类</p>
          <h1 id="folders-title">视频文件夹</h1>
          <p>文件夹仅用于归类视频，不改变项目权限、对象存储位置或视频本身。</p>
        </div>
        <div className="detail-actions">
          <Button loading={loadState === 'loading'} onClick={() => void loadFolders()}>
            刷新
          </Button>
          {canManageFolders ? (
            <Button type="primary" loading={isMutating} onClick={() => void createFolder()}>
              新建文件夹
            </Button>
          ) : null}
        </div>
      </section>
      {projectRole === 'MEMBER' ? (
        <Alert
          title="当前为普通用户权限"
          description="你可以按文件夹查找视频；新建、重命名和删除文件夹仅对项目管理员开放。"
          type="info"
          showIcon
        />
      ) : null}
      {loadState === 'loading' ? (
        <div className="catalog-notice" role="status">
          正在读取项目文件夹…
        </div>
      ) : null}
      {loadState === 'error' ? (
        <div className="catalog-error" role="alert">
          <strong>{errorCode}</strong>
          <span>{errorMessage}</span>
          <Button onClick={() => void loadFolders()}>重试</Button>
        </div>
      ) : null}
      {loadState === 'success' && folders.length > 0 ? (
        <Card className="table-card" variant="outlined">
          <Table<AssetFolder>
            dataSource={folders}
            rowKey="folder_id"
            pagination={false}
            columns={[
              { title: '文件夹', dataIndex: 'display_name' },
              { title: '分类范围', render: () => '仅当前项目 · 一层分类' },
              { title: '状态', width: 120, render: () => <Tag>可用</Tag> },
              ...(canManageFolders
                ? [
                    {
                      title: '操作',
                      width: 190,
                      render: (_: unknown, row: AssetFolder) => (
                        <div className="table-actions">
                          <Button
                            type="link"
                            disabled={isMutating}
                            aria-label={`重命名文件夹：${row.display_name}`}
                            onClick={() => void renameFolder(row)}
                          >
                            重命名
                          </Button>
                          <Button
                            type="link"
                            danger
                            disabled={isMutating}
                            aria-label={`删除文件夹：${row.display_name}`}
                            onClick={() => removeFolder(row)}
                          >
                            删除
                          </Button>
                        </div>
                      ),
                    },
                  ]
                : []),
            ]}
          />
        </Card>
      ) : null}
      {loadState === 'success' && folders.length === 0 ? (
        <Empty
          description={
            canManageFolders
              ? '尚未创建文件夹。可先新建一个分类，再在视频详情中归类。'
              : '当前项目尚未创建文件夹。'
          }
        />
      ) : null}
    </>
  )
}
