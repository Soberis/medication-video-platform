import { Descriptions } from 'antd'
import type { ReactNode } from 'react'
import { Link } from 'react-router'

import type { AnnotationTask } from '@/api/annotation'
import type { CaptureGroup, CaptureGroupQualityReview } from '@/api/capture-groups'
import { paths } from '@/app/paths'
import { CopyableText } from '@/components/data/CopyableText'
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

const captureValueLabels: Record<string, string> = {
  EXTERNAL_CAPTURE_SESSION_ID: '外部采集会话',
  PLATFORM_LOCAL: '本地导入',
  UPSTREAM_MANIFEST_KEY: '上游清单',
}

function friendlyCaptureValue(value: string | null | undefined) {
  if (!value) return null
  return captureValueLabels[value] ?? value
}

function compactInputVersion(value: string) {
  const separator = value.indexOf(':')
  if (separator < 0) return value
  const schema = value.slice(0, separator)
  const digest = value.slice(separator + 1)
  if (digest.length <= 16) return value
  const version = schema.split('.').pop() ?? schema
  return `${version}:${digest.slice(0, 8)}…`
}

function InputVersionValue({ value }: { value: string }) {
  return <CopyableText value={value} displayValue={compactInputVersion(value)} label="复制版本" />
}

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
  const deviceLabel = friendlyCaptureValue(group.device_id)
  const expectedCameraNumbers = group.expected_camera_numbers
  const receivedCameraNumbers = group.received_camera_numbers
  const isSingleCameraComplete =
    expectedCameraNumbers.length === 1 &&
    receivedCameraNumbers.length === 1 &&
    group.missing_camera_numbers.length === 0
  const cameraModeLabel = expectedCameraNumbers.length === 1 ? '单机位' : '多机位'
  const selectedMountPosition = selectedStream
    ? friendlyCaptureValue(selectedStream.mount_position)
    : null
  const showMountPosition = Boolean(
    selectedMountPosition &&
      selectedMountPosition !== deviceLabel,
  )
  const taskFocus =
    groupQuality &&
    groupQuality.result !== 'QUALIFIED' &&
    groupQuality.result !== 'QUALIFIED_WITH_EXCLUSIONS'
      ? 'annotation'
      : task
        ? (captureGroupTaskDestination(task)?.focus ?? 'annotation')
        : 'annotation'

  return (
    <aside className="capture-group-inspector" aria-labelledby="capture-group-inspector-title">
      <header className="capture-group-inspector-heading">
        <h2 id="capture-group-inspector-title">记录详情</h2>
      </header>

      <ProgressiveDetail title="接收与采集事实" summary={receiptStateLabels[group.receipt_state]}>
        <InspectorSection title="接收状态 / 采集事实" titleId="capture-group-receipt-facts">
          <MetadataList
            ariaLabel="采集记录接收事实"
            items={[
              {
                id: 'group-id',
                label: '采集记录 ID',
                value: <CopyableText value={group.capture_group_id} />,
              },
              ...(group.capture_group_key
                ? [{ id: 'capture-key', label: '归组键', value: group.capture_group_key }]
                : []),
              ...(group.capture_group_key_source
                ? [
                    {
                      id: 'key-source',
                      label: '归组来源',
                      value: friendlyCaptureValue(group.capture_group_key_source),
                    },
                  ]
                : []),
              ...(group.device_time_utc
                ? [
                    {
                      id: 'capture-time',
                      label: '拍摄时间',
                      value: formatDateTime(group.device_time_utc),
                    },
                  ]
                : []),
              {
                id: 'receive-time',
                label: '服务端接收时间',
                value: formatDateTime(group.server_receive_time),
              },
              { id: 'batch', label: '上传批次', value: <code>{group.batch_id}</code> },
              ...(deviceLabel ? [{ id: 'device', label: '设备', value: deviceLabel }] : []),
              {
                id: 'camera-status',
                label: '机位状态',
                value: isSingleCameraComplete
                  ? `单机位（机位 ${expectedCameraNumbers[0]}）`
                  : group.missing_camera_numbers.length
                    ? `${cameraModeLabel} · 缺失 ${group.missing_camera_numbers.length} 个`
                    : `${cameraModeLabel} · 已接收 ${receivedCameraNumbers.length}/${expectedCameraNumbers.length}`,
              },
            ]}
          />
          {!isSingleCameraComplete ? (
            <Descriptions
              column={1}
              items={[
                {
                  key: 'expected',
                  label: '应收机位',
                  children: expectedCameraNumbers.map((number) => `机位 ${number}`).join('、'),
                },
                {
                  key: 'received',
                  label: '已接收',
                  children: receivedCameraNumbers.length
                    ? receivedCameraNumbers.map((number) => `机位 ${number}`).join('、')
                    : '暂无',
                },
                ...(group.missing_camera_numbers.length
                  ? [
                      {
                        key: 'missing',
                        label: '缺失',
                        children: group.missing_camera_numbers
                          .map((number) => `机位 ${number}`)
                          .join('、'),
                      },
                    ]
                  : []),
              ]}
            />
          ) : null}
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
                  value: `机位 ${selectedStream.camera_number}`,
                },
                ...(showMountPosition
                  ? [{ id: 'mount', label: '安装位置', value: selectedMountPosition }]
                  : []),
                {
                  id: 'asset',
                  label: '视频资产',
                  value: <code>{selectedStream.clip_asset_id}</code>,
                },
                ...(selectedStream.record_start_utc
                  ? [
                      {
                        id: 'record-start',
                        label: '拍摄开始',
                        value: formatDateTime(selectedStream.record_start_utc),
                      },
                    ]
                  : []),
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
                <span>暂时无法读取人工标签质量，请刷新后重试。</span>
              </div>
            ) : null}
            {groupQualityVisibility === 'forbidden' ? (
              <div className="capture-group-inspector-status" role="status">
                <span>当前账号无权查看人工标签质量。</span>
              </div>
            ) : null}
            {groupQuality?.confirmed_view_set ? (
              <div className="capture-group-confirmed-view-set capture-group-stack">
                <strong>本次结论使用的机位集合</strong>
                <span>{formatViewQualitySummary(groupQuality)}</span>
                {groupQuality.input_version ? (
                  <span>
                    确认输入版本：
                    <InputVersionValue value={groupQuality.input_version} />
                  </span>
                ) : null}
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
                      {entry.reason ? <span>{entry.reason}</span> : null}
                      {entry.decided_at ? <small>{formatDateTime(entry.decided_at)}</small> : null}
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
                    value: <CopyableText value={task.annotation_task_id} />,
                  },
                  { id: 'task-status', label: '任务状态', value: formatTaskStatus(task) },
                  { id: 'task-assignee', label: '执行人', value: task.assignee_actor_id },
                  { id: 'task-created-by', label: '创建人', value: task.created_by },
                  ...(task.camera_views?.length
                    ? [{
                        id: 'task-camera-progress',
                        label: '机位进度',
                        value: `${task.camera_views.filter((view) => view.state === 'SUBMITTED' || view.state === 'EXCLUDED').length} / ${task.camera_views.length} 路已提交或排除`,
                      }]
                    : []),
                  {
                    id: 'task-input-version',
                    label: '确认输入版本',
                    value: <InputVersionValue value={task.group_input_version} />,
                  },
                ]}
              />
              {revision ? (
                <div className="capture-group-inspector-history capture-group-stack">
                  <strong>正式修订</strong>
                  <CopyableText value={revision.annotation_revision_id} />
                  <span>
                    第 {revision.revision_number} 次 · {revision.submitted_by} ·{' '}
                    {formatDateTime(revision.submitted_at)}
                  </span>
                  <span>
                    确认输入版本：
                    <InputVersionValue value={revision.input_version} />
                  </span>
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
                      标注检查 · {cleaningLabel(cleaning.outcome)}
                    </StatusIndicator>
                  ) : null}
                </div>
              ) : (
                <p className="form-hint">任务已存在，尚无正式修订。</p>
              )}
              <Link
                className="text-link"
                to={paths.captureGroupFocus(projectId, captureGroupId, taskFocus)}
              >
                打开采集记录人工标签任务上下文
              </Link>
            </>
          </InspectorSection>
        </ProgressiveDetail>
      ) : null}
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
