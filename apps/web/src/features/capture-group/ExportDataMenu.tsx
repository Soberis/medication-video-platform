import { App as AntdApp, Button, Checkbox, Modal, Table } from 'antd'
import { Download } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

import {
  AnnotationTaskApiError,
  downloadHumanDemonstrationHdf5,
  listAnnotationTasks,
} from '@/api/annotation'
import { listAllCaptureGroupSummaries, type CaptureGroupSummary } from '@/api/capture-groups'
import { ErrorState, LoadingState } from '@/components/WorkspaceFeedback'
import { CopyableText } from '@/components/data/CopyableText'
import { isHumanAnnotationExportReady } from '@/features/capture-group-presentation'
import { shortId } from '@/features/catalog/catalog-presentation'

export function ExportDataMenu({ projectId }: { projectId: string }) {
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

  const exportableIds = rows.map((row) => row.capture_group_id)
  const selectedExportIds = exportableIds.filter((id) => selectedIds.has(id))
  const allChecked = exportableIds.length > 0 && exportableIds.every((id) => selectedIds.has(id))
  const indeterminate =
    selectedExportIds.length > 0 && selectedExportIds.length < exportableIds.length

  async function download() {
    if (loading || readState !== 'success' || !selectedExportIds.length) return
    setLoading(true)
    try {
      const { blob, fileName } = await downloadHumanDemonstrationHdf5(projectId, selectedExportIds)
      const objectUrl = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = objectUrl
      link.download = fileName
      link.rel = 'noopener'
      link.hidden = true
      document.body.append(link)
      try {
        link.click()
      } finally {
        URL.revokeObjectURL(objectUrl)
        link.remove()
      }
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
        width={720}
        destroyOnHidden
        closable={!loading}
        mask={{ closable: !loading }}
        keyboard={!loading}
        footer={
          <div className="capture-group-export-modal-footer">
            <p className="form-hint">仅导出勾选记录；下载时仍会检查当前媒体与标注来源是否有效。</p>
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
          <p className="form-hint">
            HDF5 演示数据包含已复核人工动作区间、标签来源与各机位当前可验证的 MediaPipe 手部关键点。
          </p>
          <p className="form-hint">
            文件不包含 MP4、对象存储地址、患者身份或机器人控制动作；仅限合成数据配置导出。
          </p>
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
                dataSource={rows}
                scroll={{ y: 320 }}
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
                    width: 132,
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
                    render: (_, row) => (
                      <span className="mono-wrap">
                        {row.capture_group_key ?? shortId(row.capture_group_id)}
                      </span>
                    ),
                  },
                  {
                    title: '导出',
                    key: 'export',
                    align: 'right',
                    render: () => <span className="muted-text">可选择</span>,
                  },
                ]}
              />
            </div>
          ) : null}
          <p role="status">已选择 {selectedExportIds.length} 条</p>
        </section>
      </Modal>
    </>
  )
}
