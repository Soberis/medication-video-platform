import { App as AntdApp, Button, Checkbox, Modal, Table } from 'antd'
import { Download } from 'lucide-react'
import { useMemo, useState } from 'react'

import {
  AnnotationTaskApiError,
  downloadHumanDemonstrationHdf5,
  type AnnotationTask,
} from '@/api/annotation'
import type { CaptureGroupSummary } from '@/api/capture-groups'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import { formatTaskStatus } from '@/features/capture-group/capture-group-detail-model'
import { describeExportEligibility } from '@/features/capture-group-presentation'
import { shortId } from '@/features/catalog/catalog-presentation'

interface ExportRow {
  captureGroupId: string
  identity: string
  taskStatus: string | null
  exportable: boolean
  reason: string
}

export function ExportDataMenu({
  projectId,
  rows,
  tasksByGroupId,
  tasksReady,
}: {
  projectId: string
  rows: CaptureGroupSummary[]
  tasksByGroupId: Map<string, AnnotationTask>
  tasksReady: boolean
}) {
  const { message } = AntdApp.useApp()
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set())

  const exportRows = useMemo<ExportRow[]>(
    () =>
      rows.map((group) => {
        const task = tasksByGroupId.get(group.capture_group_id) ?? null
        const eligibility = describeExportEligibility(task, tasksReady)
        return {
          captureGroupId: group.capture_group_id,
          identity: group.capture_group_key ?? shortId(group.capture_group_id),
          taskStatus: task ? formatTaskStatus(task) : null,
          exportable: eligibility.exportable,
          reason: eligibility.reason,
        }
      }),
    [rows, tasksByGroupId, tasksReady],
  )

  const exportableIds = exportRows.filter((row) => row.exportable).map((row) => row.captureGroupId)
  const selectedExportIds = exportableIds.filter((id) => selectedIds.has(id))
  const pageAllChecked =
    exportableIds.length > 0 && exportableIds.every((id) => selectedIds.has(id))
  const pageIndeterminate =
    selectedExportIds.length > 0 && selectedExportIds.length < exportableIds.length

  async function download() {
    if (loading || !selectedExportIds.length) return
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

  function togglePageAll(checked: boolean) {
    setSelectedIds((current) => {
      const next = new Set(current)
      if (checked) exportableIds.forEach((id) => next.add(id))
      else exportableIds.forEach((id) => next.delete(id))
      return next
    })
  }

  return (
    <>
      <Button
        icon={<Download aria-hidden="true" size={16} strokeWidth={1.8} />}
        onClick={() => {
          setSelectedIds(new Set())
          setOpen(true)
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
              <Button aria-label="取消" disabled={loading} onClick={() => setOpen(false)}>
                取消
              </Button>
              <Button
                type="primary"
                aria-label={`下载 HDF5（${selectedExportIds.length} 条）`}
                aria-busy={loading}
                disabled={loading || !selectedExportIds.length}
                loading={loading}
                onClick={() => void download()}
              >
                下载 HDF5（{selectedExportIds.length} 条）
              </Button>
            </div>
          </div>
        }
        onCancel={() => !loading && setOpen(false)}
      >
        <section aria-label="HDF5 演示数据导出">
          <p className="form-hint">
            HDF5 演示数据包含已复核人工动作区间、标签来源与各机位当前可验证的 MediaPipe 手部关键点。
          </p>
          <p className="form-hint" role="status">
            当前列表基于已加载的 {rows.length}{' '}
            条采集记录；批量勾选仅作用于这些记录，不包含未加载的视频。
          </p>
          <p className="form-hint">
            文件不包含 MP4、对象存储地址、患者身份或机器人控制动作；仅限合成数据配置导出。
          </p>
          <Table
            size="small"
            pagination={false}
            rowKey="captureGroupId"
            dataSource={exportRows}
            locale={{ emptyText: '尚未加载采集记录，请返回视频列表加载后重试。' }}
            columns={[
              {
                title: (
                  <Checkbox
                    aria-label="勾选已加载的可导出记录"
                    checked={pageAllChecked}
                    indeterminate={pageIndeterminate}
                    disabled={loading || !exportableIds.length}
                    onChange={(event) => togglePageAll(event.target.checked)}
                  >
                    勾选已加载
                  </Checkbox>
                ),
                key: 'select',
                width: 132,
                render: (_, row) => (
                  <Checkbox
                    aria-label={`选择导出 ${row.identity}`}
                    checked={row.exportable && selectedIds.has(row.captureGroupId)}
                    disabled={loading || !row.exportable}
                    onChange={(event) => toggleRow(row.captureGroupId, event.target.checked)}
                  />
                ),
              },
              {
                title: '视频',
                dataIndex: 'identity',
                render: (value: string) => <span className="mono-wrap">{value}</span>,
              },
              {
                title: '人工任务',
                key: 'task-status',
                render: (_, row) =>
                  row.taskStatus ? (
                    <StatusIndicator tone="information">{row.taskStatus}</StatusIndicator>
                  ) : (
                    <span className="muted-text">—</span>
                  ),
              },
              {
                title: '导出',
                key: 'export',
                render: (_, row) =>
                  row.exportable ? (
                    <StatusIndicator tone="success">{row.reason}</StatusIndicator>
                  ) : (
                    <span className="muted-text">{row.reason}</span>
                  ),
              },
            ]}
          />
        </section>
      </Modal>
    </>
  )
}
