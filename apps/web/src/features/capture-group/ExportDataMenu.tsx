import { App as AntdApp, Button, Checkbox, Modal, Table } from 'antd'
import { Download } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'

import {
  AnnotationTaskApiError,
  listAnnotationTasks,
} from '@/api/annotation'
import {
  listAllCaptureGroupSummaries,
  type CaptureGroupPipelineItem,
  type CaptureGroupSummary,
} from '@/api/capture-groups'
import { ErrorState, LoadingState } from '@/components/WorkspaceFeedback'
import { triggerHdf5Download } from '@/features/capture-group/export-hdf5'
import { isHumanAnnotationExportReady } from '@/features/capture-group-presentation'
import { formatDateTime, parseAssetIdentity, shortId } from '@/features/catalog/catalog-presentation'

export function ExportDataMenu({
  projectId,
  pipelineItems = {},
}: {
  projectId: string
  pipelineItems?: Record<string, CaptureGroupPipelineItem>
}) {
  const { message } = AntdApp.useApp()
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set())
  const [rows, setRows] = useState<CaptureGroupSummary[]>([])
  const [readState, setReadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [readError, setReadError] = useState('')
  const activeRequest = useRef<AbortController | null>(null)

  useEffect(() => () => activeRequest.current?.abort(), [projectId])

  async function loadCandidates() {
    activeRequest.current?.abort()
    const request = new AbortController()
    activeRequest.current = request
    setRows([])
    setSelectedIds(new Set())
    setReadState('loading')
    setReadError('')
    try {
      const [groups, tasks] = await Promise.all([
        listAllCaptureGroupSummaries(projectId, request.signal),
        listAnnotationTasks(projectId, request.signal),
      ])
      if (request.signal.aborted || activeRequest.current !== request) return
      if (tasks.progress.scope !== 'PROJECT') {
        throw new AnnotationTaskApiError(
          '当前账号没有读取项目全部导出候选的权限。',
          'ANNOTATION_TASK_ACCESS_DENIED',
        )
      }
      const approvedIds = new Set(
        tasks.items.filter(isHumanAnnotationExportReady).map((task) => task.capture_group_id),
      )
      setRows(groups.filter((group) => approvedIds.has(group.capture_group_id)))
      setReadState('success')
    } catch (error) {
      if (request.signal.aborted || activeRequest.current !== request) return
      request.abort()
      setReadError(
        error instanceof AnnotationTaskApiError
          ? error.message
          : '暂时无法读取项目导出候选，请重试。',
      )
      setReadState('error')
    } finally {
      if (activeRequest.current === request) activeRequest.current = null
    }
  }

  function close() {
    if (loading) return
    activeRequest.current?.abort()
    activeRequest.current = null
    setOpen(false)
  }

  const isRowExportable = (row: CaptureGroupSummary) => {
    const item = pipelineItems[row.capture_group_id]
    if (!item) return true
    if (
      item.pipeline_status === 'NO_HAND' ||
      item.pipeline_status === 'FAILED' ||
      item.pipeline_status === 'NO_ACTION' ||
      item.mp_hand_result === 'NO_HAND_DETECTED'
    ) {
      return false
    }
    return true
  }

  // 严格过滤：仅收录已核准且已检出双手骨骼的真正可导出数据
  const exportableRows = useMemo(() => rows.filter(isRowExportable), [rows, pipelineItems])
  const exportableIds = useMemo(() => exportableRows.map((row) => row.capture_group_id), [exportableRows])
  const selectedExportIds = useMemo(
    () => exportableIds.filter((id) => selectedIds.has(id)),
    [exportableIds, selectedIds],
  )
  const allChecked = exportableIds.length > 0 && exportableIds.every((id) => selectedIds.has(id))
  const indeterminate =
    selectedExportIds.length > 0 && selectedExportIds.length < exportableIds.length

  async function download() {
    if (loading || readState !== 'success' || !selectedExportIds.length) return
    setLoading(true)
    try {
      const fileName = await triggerHdf5Download(projectId, selectedExportIds)
      setOpen(false)
      void message.success(`已开始下载 ${fileName}。`)
    } catch (error) {
      void message.error(
        error instanceof AnnotationTaskApiError
          ? error.message
          : 'HDF5 演示数据暂时无法导出，请稍后重试。',
      )
    } finally {
      setLoading(false)
    }
  }

  function toggleRow(captureGroupId: string, checked: boolean) {
    setSelectedIds((current) => {
      const next = new Set(current)
      if (checked) next.add(captureGroupId)
      else next.delete(captureGroupId)
      return next
    })
  }

  return (
    <>
      <Button
        icon={<Download aria-hidden="true" size={16} strokeWidth={1.8} />}
        onClick={() => {
          setOpen(true)
          void loadCandidates()
        }}
      >
        导出数据
      </Button>
      <Modal
        open={open}
        title="导出数据"
        width={620}
        destroyOnHidden
        closable={!loading}
        mask={{ closable: !loading }}
        keyboard={!loading}
        footer={
          <div className="capture-group-export-modal-footer">
            <div className="capture-group-export-footer-info">
              <div className="capture-group-export-count-wrapper">
                <span className="capture-group-export-selected-count" role="status">
                  已选择 {selectedExportIds.length} 条
                </span>
                {exportableRows.length > 0 ? (
                  <span className="export-modal-total-hint">
                    / 共 {exportableRows.length} 条可导出数据
                  </span>
                ) : null}
              </div>
              <span className="export-modal-subhint">
                仅包含动作与双手骨骼双合格的合规演示数据。
              </span>
            </div>
            <div className="capture-group-export-modal-actions">
              <Button aria-label="取消" disabled={loading} onClick={close}>
                取消
              </Button>
              <Button
                type="primary"
                aria-label="下载 HDF5"
                aria-busy={loading}
                disabled={loading || readState !== 'success' || !selectedExportIds.length}
                loading={loading}
                onClick={() => void download()}
              >
                下载 HDF5
              </Button>
            </div>
          </div>
        }
        onCancel={close}
      >
        <section aria-label="HDF5 演示数据导出">
          <div className="export-modal-description">
            <p className="export-modal-header-desc">
              仅包含已复核动作区间与双手骨骼对齐数据（HDF5 v2 格式）
            </p>
          </div>
          {readState === 'loading' ? (
            <LoadingState>正在读取当前项目的导出候选…</LoadingState>
          ) : null}
          {readState === 'error' ? (
            <ErrorState onRetry={() => void loadCandidates()}>{readError}</ErrorState>
          ) : null}
          {readState === 'success' ? (
            <div className="table-surface export-table-card">
              <Table
                className="capture-group-export-table"
                size="small"
                tableLayout="fixed"
                pagination={false}
                rowKey="capture_group_id"
                rowClassName={(row) =>
                  selectedIds.has(row.capture_group_id) ? 'is-selected ant-table-row-selected' : ''
                }
                dataSource={exportableRows}
                scroll={exportableRows.length > 5 ? { y: 270 } : undefined}
                locale={{ emptyText: '当前项目暂无已完成并通过确认的人工标注数据。' }}
                onRow={(row) => ({
                  onClick: (event) => {
                    if (loading) return
                    const target = event.target as HTMLElement
                    if (
                      target.closest('a') ||
                      target.closest('button') ||
                      target.closest('input') ||
                      target.closest('label') ||
                      (window.getSelection()?.toString().length ?? 0) > 0
                    ) {
                      return
                    }
                    toggleRow(row.capture_group_id, !selectedIds.has(row.capture_group_id))
                  },
                })}
                columns={[
                  {
                    title: (
                      <Checkbox
                        aria-label="全选"
                        checked={allChecked}
                        indeterminate={indeterminate}
                        disabled={loading || !exportableIds.length}
                        onChange={(event) =>
                          setSelectedIds(new Set(event.target.checked ? exportableIds : []))
                        }
                      >
                        全选
                      </Checkbox>
                    ),
                    key: 'select',
                    width: 72,
                    render: (_, row) => (
                      <Checkbox
                        aria-label={`选择导出 ${row.capture_group_key ?? shortId(row.capture_group_id)}`}
                        checked={selectedIds.has(row.capture_group_id)}
                        disabled={loading}
                        onChange={(event) => toggleRow(row.capture_group_id, event.target.checked)}
                      />
                    ),
                  },
                  {
                    title: '视频',
                    key: 'identity',
                    ellipsis: true,
                    render: (_, row) => {
                      const asset = parseAssetIdentity(row.capture_group_key, row.capture_group_id)
                      return (
                        <span className="capture-group-export-key" title={`完整标识: ${asset.fullKey}`}>
                          <span className="export-key-code">{asset.shortCode}</span>
                        </span>
                      )
                    },
                  },
                  {
                    title: '采集时间',
                    key: 'device_time',
                    width: 200,
                    render: (_, row) => (
                      <span className="muted-text" style={{ whiteSpace: 'nowrap' }}>
                        {formatDateTime(row.device_time_utc ?? row.created_at)}
                      </span>
                    ),
                  },
                  {
                    title: '机位',
                    key: 'cameras',
                    width: 80,
                    align: 'right',
                    render: (_, row) => (
                      <span className="muted-text" style={{ whiteSpace: 'nowrap' }}>
                        {row.received_camera_count > 0 ? `${row.received_camera_count} 机位` : '—'}
                      </span>
                    ),
                  },
                ]}
              />
            </div>
          ) : null}
        </section>
      </Modal>
    </>
  )
}
