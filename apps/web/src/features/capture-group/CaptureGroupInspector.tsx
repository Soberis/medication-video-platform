import { Descriptions } from 'antd'
import type { ReactNode } from 'react'
import { Link } from 'react-router'

import type { AnnotationTask } from '@/api/annotation'
import type { CaptureGroup, CaptureGroupQualityReview } from '@/api/capture-groups'
import { paths } from '@/app/paths'
import { MetadataList } from '@/components/data/MetadataList'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import { InspectorSection } from '@/components/workspace/InspectorSection'
import {
  cleaningLabel,
  cleaningTone,
  formatTaskStatus,
  type GroupViewRow,
} from '@/features/capture-group/capture-group-detail-model'
import {
  captureGroupTaskDestination,
  formatViewQualitySummary,
  groupQualityDisplayLabel,
  groupQualityDisplayTone,
  groupQualityLabels,
  groupQualityTone,
  mediaStatusLabels,
  receiptStateLabels,
  syncSafety,
  technicalQualityLabels,
  viewQualityLabels,
  type CaptureGroupQualityVisibility,
} from '@/features/capture-group-presentation'
import { formatDateTime } from '@/features/catalog/catalog-presentation'

interface CaptureGroupInspectorProps {
  projectId: string
  captureGroupId: string
  group: CaptureGroup
  groupQuality: CaptureGroupQualityReview | null
  groupQualityVisibility: CaptureGroupQualityVisibility
  qualityHistory: CaptureGroupQualityReview[]
  selectedView: GroupViewRow | null
  task: AnnotationTask | null
}

export function CaptureGroupInspector({
  projectId,
  captureGroupId,
  group,
  groupQuality,
  groupQualityVisibility,
  qualityHistory,
  selectedView,
  task,
}: CaptureGroupInspectorProps) {
  const selectedStream = selectedView?.stream ?? null
  const selectedSync = selectedStream ? syncSafety(selectedStream) : null
  const revision = task?.annotation_revision ?? null
  const review = revision?.review ?? null
  const cleaning = revision?.capture_group_cleaning ?? null
  const taskFocus =
    groupQuality &&
    groupQuality.result !== 'QUALIFIED' &&
    groupQuality.result !== 'QUALIFIED_WITH_EXCLUSIONS'
      ? 'annotation'
      : task
        ? (captureGroupTaskDestination(task)?.focus ?? 'annotation')
        : 'annotation'

  return (
    <aside
      className="capture-group-inspector"
      aria-label="事实、历史与审计"
      aria-labelledby="capture-group-inspector-title"
    >
      <header className="capture-group-inspector-heading">
        <p className="app-eyebrow">渐进详情</p>
        <h2 id="capture-group-inspector-title">事实、历史与审计</h2>
        <p>默认收起低频技术信息；展开后仍可核对原始事实和版本。</p>
      </header>

      <ProgressiveDetail title="接收与采集事实" summary={receiptStateLabels[group.receipt_state]}>
        <InspectorSection title="接收状态 / 采集事实" titleId="capture-group-receipt-facts">
          <MetadataList
            ariaLabel="数据组接收事实"
            items={[
              {
                id: 'group-id',
                label: '数据组 ID',
                value: <code>{group.capture_group_id}</code>,
              },
              { id: 'capture-key', label: '归组键', value: group.capture_group_key ?? '未提供' },
              {
                id: 'key-source',
                label: '归组来源',
                value: group.capture_group_key_source ?? '未提供',
              },
              {
                id: 'capture-time',
                label: '拍摄时间',
                value: formatDateTime(group.device_time_utc),
              },
              {
                id: 'receive-time',
                label: '服务端接收时间',
                value: formatDateTime(group.server_receive_time),
              },
              { id: 'batch', label: '上传批次', value: <code>{group.batch_id}</code> },
              { id: 'device', label: '设备', value: group.device_id },
            ]}
          />
          <Descriptions
            column={1}
            items={[
              {
                key: 'expected',
                label: '机位集合',
                children: `应 ${group.expected_camera_numbers.map((number) => `机位 ${number}`).join('、')}`,
              },
              {
                key: 'received',
                label: '已接收',
                children: group.received_camera_numbers.length
                  ? group.received_camera_numbers.map((number) => `机位 ${number}`).join('、')
                  : '暂无',
              },
              {
                key: 'missing',
                label: '缺失',
                children: group.missing_camera_numbers.length
                  ? group.missing_camera_numbers.map((number) => `机位 ${number}`).join('、')
                  : '无',
              },
            ]}
          />
        </InspectorSection>
      </ProgressiveDetail>

      <ProgressiveDetail
        title="当前机位与媒体事实"
        summary={selectedStream ? `机位 ${selectedStream.camera_number}` : '尚未选择'}
      >
        <InspectorSection title="当前机位事实" titleId="capture-group-selected-view-facts">
          {selectedStream ? (
            <MetadataList
              ariaLabel="当前机位事实"
              items={[
                {
                  id: 'camera',
                  label: '机位',
                  value: `机位 ${selectedStream.camera_number} · ${selectedStream.camera_role}`,
                },
                { id: 'mount', label: '安装位置', value: selectedStream.mount_position },
                {
                  id: 'asset',
                  label: '视频资产',
                  value: <code>{selectedStream.clip_asset_id}</code>,
                },
                {
                  id: 'record-start',
                  label: '拍摄开始',
                  value: formatDateTime(selectedStream.record_start_utc),
                },
                {
                  id: 'stream-status',
                  label: '媒体流',
                  value:
                    mediaStatusLabels[selectedStream.stream_status] ?? selectedStream.stream_status,
                },
                {
                  id: 'technical-quality',
                  label: '技术质量',
                  value:
                    technicalQualityLabels[selectedStream.quality_status] ??
                    selectedStream.quality_status,
                },
                {
                  id: 'hand-enabled',
                  label: '手部检测',
                  value: selectedStream.enabled_for_hand_detection ? '已启用' : '未启用',
                },
              ]}
            />
          ) : (
            <p className="form-hint">当前没有选中的已接收机位。</p>
          )}
          {selectedSync ? (
            <div className="capture-group-sync-inspector capture-group-stack">
              <strong>同步声明</strong>
              <StatusIndicator tone={selectedSync.tone}>{selectedSync.label}</StatusIndicator>
              <p>{selectedSync.detail}</p>
              <span>
                边端声明偏移：{selectedStream?.time_offset_ms ?? 0}{' '}
                ms（不等同于已验证的可靠时间映射）
              </span>
            </div>
          ) : null}
        </InspectorSection>
      </ProgressiveDetail>

      {task ? (
        <ProgressiveDetail
          title="人工标签质量事实"
          summary={groupQualityDisplayLabel(groupQualityVisibility, groupQuality)}
        >
          <InspectorSection title="人工标签质量事实" titleId="capture-group-quality-facts">
            <p className="form-hint">人工记录的事件质量结论与自动检测事实分开保存。</p>
            <div className="capture-group-inspector-status">
              <StatusIndicator tone={groupQualityDisplayTone(groupQualityVisibility, groupQuality)}>
                {groupQualityDisplayLabel(groupQualityVisibility, groupQuality)}
              </StatusIndicator>
              {groupQuality?.reviewer_actor_id ? (
                <span>记录人：{groupQuality.reviewer_actor_id}</span>
              ) : null}
              {groupQuality?.decided_at ? (
                <span>{formatDateTime(groupQuality.decided_at)}</span>
              ) : null}
            </div>
            {groupQuality?.reason ? <p className="form-hint">理由：{groupQuality.reason}</p> : null}
            {groupQualityVisibility === 'unavailable' ? (
              <div className="capture-group-inspector-status" role="status">
                <strong>事件质量暂不可用</strong>
                <span>事件质量暂不可用，请刷新后重试</span>
              </div>
            ) : null}
            {groupQualityVisibility === 'forbidden' ? (
              <div className="capture-group-inspector-status" role="status">
                <strong>事件质量无权查看</strong>
                <span>事件质量按当前权限不可见</span>
                <span>当前账号不能读取或修改事件质量。</span>
              </div>
            ) : null}
            {groupQuality?.confirmed_view_set ? (
              <div className="capture-group-confirmed-view-set capture-group-stack">
                <strong>本次结论使用的机位集合</strong>
                <span>
                  {formatViewQualitySummary(groupQuality)} · 确认输入版本{' '}
                  {groupQuality.input_version ?? '未提供'}
                </span>
                <ul>
                  {groupQuality.confirmed_view_set.views.map((view) => (
                    <li key={view.camera_number}>
                      机位 {view.camera_number} · {viewQualityLabels[view.quality_review_decision]}
                      {view.quality_review_decision === 'REJECTED' ? ' · 已排除但保留' : ''}
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
            {qualityHistory.length ? (
              <details className="capture-group-quality-history capture-group-stack">
                <summary>查看追加质量历史（{qualityHistory.length} 条）</summary>
                <ol>
                  {qualityHistory.map((entry) => (
                    <li key={entry.capture_group_quality_review_id ?? `revision-${entry.revision}`}>
                      <StatusIndicator tone={groupQualityTone(entry.result)}>
                        第 {entry.revision} 次 · {groupQualityLabels[entry.result]}
                      </StatusIndicator>
                      <span>{entry.reason ?? '未提供理由'}</span>
                      <small>{formatDateTime(entry.decided_at)}</small>
                    </li>
                  ))}
                </ol>
              </details>
            ) : null}
          </InspectorSection>
        </ProgressiveDetail>
      ) : null}

      {task ? (
        <ProgressiveDetail
          title="人工标签修订、复核与适用性检查"
          summary={revision ? `第 ${revision.revision_number} 次正式修订` : '尚无正式修订'}
        >
          <InspectorSection
            title="人工标签修订、复核与适用性检查详情"
            titleId="capture-group-revision-facts"
          >
            <>
              <MetadataList
                ariaLabel="人工标签任务事实"
                items={[
                  {
                    id: 'task-id',
                    label: '任务 ID',
                    value: <code>{task.annotation_task_id}</code>,
                  },
                  { id: 'task-status', label: '任务状态', value: formatTaskStatus(task) },
                  { id: 'task-assignee', label: '执行人', value: task.assignee_actor_id },
                  { id: 'task-created-by', label: '创建人', value: task.created_by },
                  {
                    id: 'task-primary',
                    label: '主视图',
                    value: task.primary_camera_number
                      ? `机位 ${task.primary_camera_number}`
                      : '未选择',
                    secondary: task.primary_clip_asset_id ?? undefined,
                  },
                  {
                    id: 'task-input-version',
                    label: '确认输入版本',
                    value: task.group_input_version ?? '未提供',
                  },
                ]}
              />
              {revision ? (
                <div className="capture-group-inspector-history capture-group-stack">
                  <strong>正式修订</strong>
                  <span>
                    {revision.annotation_revision_id} · 第 {revision.revision_number} 次 ·{' '}
                    {revision.submitted_by} · {formatDateTime(revision.submitted_at)}
                  </span>
                  <span>确认输入版本：{revision.input_version ?? '未提供'}</span>
                  {review ? (
                    <StatusIndicator tone={review.decision === 'APPROVED' ? 'success' : 'warning'}>
                      人工复核 · {review.decision === 'APPROVED' ? '已通过' : '已退回'}
                    </StatusIndicator>
                  ) : (
                    <StatusIndicator tone="information">人工复核 · 待复核</StatusIndicator>
                  )}
                  {review?.review_note ? <p>复核意见：{review.review_note}</p> : null}
                  {cleaning ? (
                    <StatusIndicator tone={cleaningTone(cleaning.outcome)}>
                      标签适用性检查 · {cleaningLabel(cleaning.outcome)}
                    </StatusIndicator>
                  ) : (
                    <span>尚无标签适用性检查结果。</span>
                  )}
                </div>
              ) : (
                <p className="form-hint">任务已存在，尚无正式修订。</p>
              )}
              <Link
                className="text-link"
                to={paths.captureGroupFocus(projectId, captureGroupId, taskFocus)}
              >
                打开数据组人工标签任务上下文
              </Link>
            </>
          </InspectorSection>
        </ProgressiveDetail>
      ) : null}

      <ProgressiveDetail title="审计与受限操作" summary="保留原始事实">
        <InspectorSection title="审计与受限操作" titleId="capture-group-audit-facts">
          <p className="form-hint">
            本页只展示已授权的服务端事实。删除、隐私放行、数据集冻结和导出等高风险操作不在此处隐式执行。
          </p>
          <p className="form-hint">
            人工标签质量历史采用追加记录；当前页面不会因工作台展示而删除或改写历史事实。
          </p>
        </InspectorSection>
      </ProgressiveDetail>
    </aside>
  )
}

function ProgressiveDetail({
  title,
  summary,
  children,
}: {
  title: string
  summary: string
  children: ReactNode
}) {
  return (
    <details className="capture-group-progressive-detail">
      <summary>
        <span>{title}</span>
        <small>{summary}</small>
      </summary>
      <div className="capture-group-progressive-detail-content">{children}</div>
    </details>
  )
}
