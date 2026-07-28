import { Button, Card, DatePicker, Empty, Form, Input, message, Modal, Select, Table, Tag } from 'antd'
import type { Dayjs } from 'dayjs'
import { type Key, useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'

import {
  CatalogApiError,
  createDatasetVersionDraft,
  listCaptureSessions,
  type CaptureQualityStatus,
  type CaptureSessionAssemblyStatus,
  type CaptureSessionFilters,
  type CaptureSessionListItem,
} from '@/api/catalog'
import { getProject, type ProjectRole } from '@/api/projects'
import { clearDatasetDraftIntent, getDatasetDraftIntent } from '@/app/dataset-draft-intent'
import { paths } from '@/app/paths'
import {
  assemblyStatusLabels,
  formatDateTime,
  qualityStatusLabels,
  shortId,
  statusLabel,
  statusTone,
} from '@/catalog-presentation'

export default function SessionListView() {
  const { projectId = '' } = useParams()
  return <SessionListContent key={projectId} />
}

function SessionListContent() {
  const { projectId = '' } = useParams()
  const navigate = useNavigate()
  const [deviceId, setDeviceId] = useState('')
  const [assemblyStatus, setAssemblyStatus] = useState<CaptureSessionAssemblyStatus | undefined>()
  const [qualityStatus, setQualityStatus] = useState<CaptureQualityStatus | undefined>()
  const [recordedRange, setRecordedRange] = useState<[Dayjs, Dayjs] | null>(null)
  const [activeFilters, setActiveFilters] = useState<CaptureSessionFilters>({})
  const [items, setItems] = useState<CaptureSessionListItem[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [isAppending, setIsAppending] = useState(false)
  const [errorCode, setErrorCode] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const [requestId, setRequestId] = useState<string | null>(null)
  const [projectRole, setProjectRole] = useState<ProjectRole | null>(null)
  const [roleLoadState, setRoleLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [roleErrorCode, setRoleErrorCode] = useState('')
  const [selectedSessionIds, setSelectedSessionIds] = useState<string[]>([])
  const [isDraftModalOpen, setIsDraftModalOpen] = useState(false)
  const [draftDisplayName, setDraftDisplayName] = useState('配药操作训练候选')
  const [isCreatingDatasetDraft, setIsCreatingDatasetDraft] = useState(false)
  const activeRequest = useRef<AbortController | null>(null)
  const roleRequest = useRef<AbortController | null>(null)
  const draftRequest = useRef<AbortController | null>(null)

  useLayoutEffect(() => {
    return () => {
      // A user-triggered write must finish or be retried with its saved key, but its late response
      // must not update a route that has already been replaced.
      draftRequest.current = null
    }
  }, [projectId])

  const loadPage = useCallback(
    async (append = false, filters = activeFilters) => {
      activeRequest.current?.abort()
      const request = new AbortController()
      activeRequest.current = request
      setIsAppending(append)
      if (!append) {
        setLoadState('loading')
        setSelectedSessionIds([])
      }
      setErrorCode('')
      setErrorMessage('')
      setRequestId(null)
      try {
        const page = await listCaptureSessions(
          projectId,
          filters,
          append ? nextCursor : null,
          request.signal,
        )
        if (activeRequest.current !== request) return
        setItems((current) => (append ? [...current, ...page.items] : page.items))
        setNextCursor(page.next_cursor)
        setLoadState('success')
      } catch (error) {
        if (request.signal.aborted || activeRequest.current !== request) return
        setLoadState('error')
        if (error instanceof CatalogApiError) {
          setErrorCode(error.errorCode)
          setErrorMessage(error.message)
          setRequestId(error.requestId)
        } else {
          setErrorCode('CATALOG_REQUEST_FAILED')
          setErrorMessage('暂时无法读取配药操作记录，请稍后重试。')
        }
      } finally {
        if (activeRequest.current === request) {
          activeRequest.current = null
          setIsAppending(false)
        }
      }
    },
    [activeFilters, nextCursor, projectId],
  )

  const loadProjectRole = useCallback(async () => {
    roleRequest.current?.abort()
    const request = new AbortController()
    roleRequest.current = request
    setProjectRole(null)
    setRoleLoadState('loading')
    setRoleErrorCode('')
    try {
      const project = await getProject(projectId, request.signal)
      if (roleRequest.current !== request) return
      setProjectRole(project.current_role)
      setRoleLoadState('success')
      if (project.current_role !== 'ADMIN') setSelectedSessionIds([])
    } catch (error) {
      if (request.signal.aborted || roleRequest.current !== request) return
      setProjectRole(null)
      setRoleLoadState('error')
      setRoleErrorCode(error instanceof CatalogApiError ? error.errorCode : 'PROJECT_ROLE_REQUEST_FAILED')
      setSelectedSessionIds([])
    } finally {
      if (roleRequest.current === request) roleRequest.current = null
    }
  }, [projectId])

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setItems([])
      setNextCursor(null)
      setActiveFilters({})
      void loadPage(false, {})
    }, 0)
    return () => {
      window.clearTimeout(timer)
      activeRequest.current?.abort()
      draftRequest.current = null
    }
    // loadPage changes with pagination state; project changes are the only automatic reload trigger.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId])

  useEffect(() => {
    const timer = window.setTimeout(() => void loadProjectRole(), 0)
    return () => {
      window.clearTimeout(timer)
      roleRequest.current?.abort()
    }
  }, [loadProjectRole])

  function applyFilters() {
    const filters: CaptureSessionFilters = {
      deviceId: deviceId.trim() || undefined,
      assemblyStatus,
      qualityStatus,
      recordedFrom: recordedRange?.[0].toISOString(),
      recordedBefore: recordedRange?.[1].toISOString(),
    }
    setActiveFilters(filters)
    setItems([])
    setNextCursor(null)
    void loadPage(false, filters)
  }
  function resetFilters() {
    setDeviceId('')
    setAssemblyStatus(undefined)
    setQualityStatus(undefined)
    setRecordedRange(null)
    setActiveFilters({})
    setItems([])
    setNextCursor(null)
    void loadPage(false, {})
  }

  function updateSelectedSessions(keys: Key[]) {
    const selected = new Set(keys.map(String))
    const orderedIds = items
      .map((item) => item.capture_session_id)
      .filter((captureSessionId) => selected.has(captureSessionId))
    if (orderedIds.length > 100) {
      void message.warning('单次最多选择 100 条配药操作。')
      setSelectedSessionIds(orderedIds.slice(0, 100))
      return
    }
    setSelectedSessionIds(orderedIds)
  }

  function openDraftModal() {
    if (!selectedSessionIds.length) return
    setDraftDisplayName('配药操作训练候选')
    setIsDraftModalOpen(true)
  }

  async function createDraft() {
    if (projectRole !== 'ADMIN') {
      void message.error('只有当前项目管理员可以创建数据集草稿。')
      return
    }
    const displayName = draftDisplayName.trim()
    if (!displayName) {
      void message.error('请填写草稿名称。')
      return
    }
    if (!selectedSessionIds.length) {
      void message.error('请至少选择一条配药操作。')
      return
    }
    if (draftRequest.current) return
    const intent = getDatasetDraftIntent(projectId, displayName, selectedSessionIds)
    const request = new AbortController()
    draftRequest.current = request
    setIsCreatingDatasetDraft(true)
    try {
      const draft = await createDatasetVersionDraft(
        projectId,
        displayName,
        selectedSessionIds,
        intent.idempotencyKey,
        request.signal,
      )
      if (draftRequest.current !== request) return
      clearDatasetDraftIntent(intent)
      void message.success(`已创建数据集草稿 ${draft.dataset_version_id.slice(0, 8)}。`)
      setIsDraftModalOpen(false)
      setSelectedSessionIds([])
      void navigate(paths.dataset(projectId, draft.dataset_version_id))
    } catch (error) {
      if (draftRequest.current !== request) return
      const outcomeIsUncertain = !(error instanceof CatalogApiError) || error.retryable
      if (!outcomeIsUncertain) clearDatasetDraftIntent(intent)
      void message.error(
        outcomeIsUncertain
          ? '暂时无法确认数据集草稿是否已创建，请再次点击创建安全重试。'
          : error.message,
      )
    } finally {
      if (draftRequest.current === request) {
        draftRequest.current = null
        setIsCreatingDatasetDraft(false)
      }
    }
  }

  return (
    <>
      <section className="page-heading" aria-labelledby="sessions-title">
        <div>
          <p className="app-eyebrow">护士配药 · 多相机数据</p>
          <h1 id="sessions-title">配药操作记录</h1>
          <p>按一次配药操作查看多路视频是否绑定、是否完成媒体入库，以及后续质量状态。</p>
        </div>
        <Button loading={loadState === 'loading'} onClick={() => void loadPage(false)}>
          刷新
        </Button>
      </section>
      <Card className="filter-card" variant="outlined">
        <Form className="catalog-filters" layout="vertical" onFinish={applyFilters}>
          <Form.Item label="边端设备 ID">
            <Input
              value={deviceId}
              allowClear
              placeholder="例如 synthetic-edge-01"
              onChange={(event) => setDeviceId(event.target.value)}
            />
          </Form.Item>
          <Form.Item label="组装状态">
            <Select
              value={assemblyStatus}
              allowClear
              placeholder="全部状态"
              onChange={setAssemblyStatus}
              options={Object.entries(assemblyStatusLabels).map(([value, label]) => ({
                value,
                label,
              }))}
            />
          </Form.Item>
          <Form.Item label="技术检测状态">
            <Select
              value={qualityStatus}
              allowClear
              placeholder="全部状态"
              onChange={setQualityStatus}
              options={Object.entries(qualityStatusLabels).map(([value, label]) => ({
                value,
                label,
              }))}
            />
          </Form.Item>
          <Form.Item label="拍摄时间">
            <DatePicker.RangePicker
              showTime
              value={recordedRange}
              onChange={(value) => setRecordedRange(value as [Dayjs, Dayjs] | null)}
              placeholder={['开始时间', '结束时间']}
            />
          </Form.Item>
          <div className="filter-actions">
            <Button htmlType="submit" type="primary">
              应用筛选
            </Button>
            <Button onClick={resetFilters}>重置</Button>
          </div>
        </Form>
      </Card>
      {loadState === 'loading' ? (
        <div className="catalog-notice" role="status" aria-live="polite">
          正在读取配药操作记录…
        </div>
      ) : null}
      {loadState === 'error' ? (
        <div className="catalog-error" role="alert">
          <strong>{errorCode}</strong>
          <span>{errorMessage}</span>
          {requestId ? <small>请求标识：{requestId}</small> : null}
          <Button onClick={() => void loadPage(false)}>重试</Button>
        </div>
      ) : null}
      {roleLoadState === 'error' ? (
        <div className="permission-check-error" role="alert">
          <span>暂时无法核对项目管理员权限，无法创建数据集草稿。</span>
          <strong>{roleErrorCode}</strong>
          <Button onClick={() => void loadProjectRole()}>重试权限核对</Button>
        </div>
      ) : null}
      {items.length > 0 ? (
        <Card className="table-card" variant="outlined" aria-busy={isAppending}>
          {projectRole === 'ADMIN' ? (
            <div className="dataset-draft-selection-toolbar" aria-live="polite">
              <div>
                <strong>已选择 {selectedSessionIds.length} 条配药操作</strong>
                <span>仅提交当前已加载的记录；服务端会在创建时重新核对每一条记录。</span>
              </div>
              <div className="dataset-draft-selection-actions">
                {selectedSessionIds.length ? (
                  <Button type="link" onClick={() => setSelectedSessionIds([])}>
                    清除选择
                  </Button>
                ) : null}
                <Button type="primary" disabled={!selectedSessionIds.length} onClick={openDraftModal}>
                  创建数据集草稿
                </Button>
              </div>
            </div>
          ) : null}
          <Table<CaptureSessionListItem>
            dataSource={items}
            rowKey="capture_session_id"
            rowSelection={
              projectRole === 'ADMIN'
                ? {
                    selectedRowKeys: selectedSessionIds,
                    onChange: updateSelectedSessions,
                    getCheckboxProps: (row) => ({
                      disabled:
                        selectedSessionIds.length >= 100 &&
                        !selectedSessionIds.includes(row.capture_session_id),
                    }),
                  }
                : undefined
            }
            pagination={false}
            scroll={{ x: 1100 }}
            columns={[
              {
                title: '拍摄时间',
                width: 190,
                render: (_, row) => (
                  <>
                    <strong>{formatDateTime(row.device_time_utc)}</strong>
                    <span className="cell-secondary">
                      接收：{formatDateTime(row.server_receive_time)}
                    </span>
                  </>
                ),
              },
              { title: '设备', dataIndex: 'device_id', width: 150 },
              {
                title: '相机完整性',
                width: 170,
                render: (_, row) => (
                  <>
                    <strong>
                      已绑定 {row.bound_stream_count} / {row.expected_camera_count}
                    </strong>
                    <span className="cell-secondary">已入库 {row.received_camera_count}</span>
                  </>
                ),
              },
              {
                title: 'CameraSet / 布局',
                width: 180,
                render: (_, row) => (
                  <>
                    <span>
                      {row.camera_set_key} · v{row.camera_set_configuration_version}
                    </span>
                    <span className="cell-secondary">{row.camera_layout_version}</span>
                  </>
                ),
              },
              {
                title: '组装状态',
                width: 130,
                render: (_, row) => (
                  <Tag color={statusTone(row.assembly_status)}>
                    {statusLabel(assemblyStatusLabels, row.assembly_status)}
                  </Tag>
                ),
              },
              {
                title: '质量状态',
                width: 120,
                render: (_, row) => (
                  <Tag color={statusTone(row.quality_status)}>
                    {statusLabel(qualityStatusLabels, row.quality_status)}
                  </Tag>
                ),
              },
              {
                title: '操作',
                width: 100,
                fixed: 'right',
                render: (_, row) => (
                  <Link
                    className="text-link table-action-link"
                    aria-label={`查看配药操作：${shortId(row.capture_session_id)}`}
                    to={paths.session(projectId, row.capture_session_id)}
                  >
                    查看详情
                  </Link>
                ),
              },
            ]}
          />
          <div className="table-footer">
            <span>已显示 {items.length} 组配药操作</span>
            {nextCursor ? (
              <Button loading={isAppending} onClick={() => void loadPage(true)}>
                加载更多
              </Button>
            ) : null}
          </div>
        </Card>
      ) : null}
      {loadState === 'success' && items.length === 0 ? (
        <Empty description="当前项目没有符合条件的配药操作记录。可调整筛选，或通过边端自动上传和平台本地上传新增视频。" />
      ) : null}
      <Modal
        open={isDraftModalOpen}
        title="创建数据集草稿"
        okText="创建草稿"
        cancelText="取消"
        confirmLoading={isCreatingDatasetDraft}
        okButtonProps={{ disabled: !draftDisplayName.trim() || !selectedSessionIds.length }}
        onCancel={() => setIsDraftModalOpen(false)}
        onOk={() => void createDraft()}
      >
        <p className="form-hint">
          将为已选的 {selectedSessionIds.length} 条配药操作保存候选证据快照。服务端会重新核对准入条件；任一记录不符合时，不会创建草稿。草稿不会拆分、冻结或导出。
        </p>
        <Form layout="vertical">
          <Form.Item label="草稿名称" htmlFor="dataset-draft-display-name">
            <Input
              id="dataset-draft-display-name"
              value={draftDisplayName}
              maxLength={128}
              showCount
              autoFocus
              onChange={(event) => setDraftDisplayName(event.target.value)}
            />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
