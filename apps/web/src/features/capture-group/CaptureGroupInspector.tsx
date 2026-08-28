import { Descriptions } from 'antd'
import { Check } from 'lucide-react'
import { Link } from 'react-router'

import type { AnnotationTask } from '@/api/annotation'
import type { CaptureGroup, CaptureGroupQualityReview } from '@/api/capture-groups'
import { paths } from '@/app/paths'
import { MetadataList } from '@/components/data/MetadataList'
import { StatusBadge } from '@/components/StatusBadge'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import { InspectorSection } from '@/components/workspace/InspectorSection'
import {
  cleaningLabel,
  cleaningTone,
  type GroupViewRow,
} from '@/features/capture-group/capture-group-detail-model'
import { CaptureGroupQualityPanel } from '@/features/capture-group/CaptureGroupQualityPanel'
import {
  captureGroupReviewCleaningFallbackTargetId,
  captureGroupTaskDestination,
  mediaStatusLabels,
  syncSafety,
  technicalQualityLabels,
} from '@/features/capture-group-presentation'
import { formatDateTime } from '@/features/catalog/catalog-presentation'

interface CaptureGroupInspectorProps {
  projectId: string
  captureGroupId: string
  group: CaptureGroup
  groupQuality: CaptureGroupQualityReview | null
  qualityHistory: CaptureGroupQualityReview[]
  refreshVersion: number
  selectedView: GroupViewRow | null
  task: AnnotationTask | null
  onRefresh: () => Promise<void>
}

export function CaptureGroupInspector({
  projectId,
  captureGroupId,
  group,
  groupQuality,
  qualityHistory,
  refreshVersion,
  selectedView,
  task,
  onRefresh,
}: CaptureGroupInspectorProps) {
  const selectedStream = selectedView?.stream ?? null
  const selectedSync = selectedStream ? syncSafety(selectedStream) : null
  const currentGroupResult = groupQuality?.result ?? 'UNREVIEWED'
  const groupQualityDowngraded =
    groupQuality?.result === 'NEEDS_REVIEW' || groupQuality?.result === 'REJECTED'

  return (
    <aside className="capture-group-inspector" aria-label="CaptureGroup Inspector">
      <InspectorSection title="Receipt / capture facts" titleId="capture-group-receipt-facts">
        <MetadataList
          ariaLabel="CaptureGroup 接收事实"
          items={[
            {
              id: 'group-id',
              label: 'CaptureGroup ID',
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
              label: '设备采集时间',
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
              label: '相机集合',
              children: `应 ${group.expected_camera_numbers.map((number) => `Cam ${number}`).join('、')}`,
            },
            {
              key: 'received',
              label: '已接收',
              children: group.received_camera_numbers.length
                ? group.received_camera_numbers.map((number) => `Cam ${number}`).join('、')
                : '暂无',
            },
            {
              key: 'missing',
              label: '缺失',
              children: group.missing_camera_numbers.length
                ? group.missing_camera_numbers.map((number) => `Cam ${number}`).join('、')
                : '无',
            },
          ]}
        />
      </InspectorSection>

      <CaptureGroupQualityPanel
        projectId={projectId}
        captureGroupId={captureGroupId}
        group={group}
        groupQuality={groupQuality}
        qualityHistory={qualityHistory}
        refreshVersion={refreshVersion}
        onRefresh={onRefresh}
      />

      <InspectorSection title="Selected View facts" titleId="capture-group-selected-view-facts">
        {selectedStream ? (
          <MetadataList
            ariaLabel="当前视图事实"
            items={[
              {
                id: 'camera',
                label: '相机',
                value: `Cam ${selectedStream.camera_number} · ${selectedStream.camera_role}`,
              },
              { id: 'mount', label: '安装位置', value: selectedStream.mount_position },
              {
                id: 'asset',
                label: 'ClipAsset',
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
          <p className="form-hint">当前没有选中的已接收视图。</p>
        )}
        {selectedSync ? (
          <div className="capture-group-sync-inspector capture-group-stack">
            <strong>Sync evidence</strong>
            <StatusIndicator tone={selectedSync.tone}>{selectedSync.label}</StatusIndicator>
            <p>{selectedSync.detail}</p>
            <span>
              边端声明偏移：{selectedStream?.time_offset_ms ?? 0} ms（不等同于 validated mapping）
            </span>
          </div>
        ) : null}
      </InspectorSection>

      <InspectorSection
        className="capture-group-workflow-focus-target"
        id={captureGroupReviewCleaningFallbackTargetId}
        tabIndex={-1}
        title="Review / Cleaning"
        titleId="review-cleaning-context"
      >
        {task?.annotation_revision ? (
          <>
            <StatusIndicator
              tone={
                task.annotation_revision.review
                  ? task.annotation_revision.review.decision === 'APPROVED'
                    ? 'success'
                    : 'destructive'
                  : 'information'
              }
            >
              Review ·{' '}
              {task.annotation_revision.review
                ? task.annotation_revision.review.decision === 'APPROVED'
                  ? '已通过'
                  : '已退回'
                : '待复核'}
            </StatusIndicator>
            {task.annotation_revision.review?.review_note ? (
              <p className="form-hint">复核意见：{task.annotation_revision.review.review_note}</p>
            ) : null}
            {task.annotation_revision.capture_group_cleaning ? (
              <div className="capture-group-cleaning-fact capture-group-stack">
                <StatusBadge
                  tone={cleaningTone(task.annotation_revision.capture_group_cleaning.outcome)}
                >
                  训练资格检查 ·{' '}
                  {cleaningLabel(task.annotation_revision.capture_group_cleaning.outcome)}
                </StatusBadge>
                <span>
                  {formatDateTime(task.annotation_revision.capture_group_cleaning.evaluated_at)}
                </span>
                {task.annotation_revision.capture_group_cleaning.reason_codes.length ? (
                  <p className="form-hint">
                    原因：{task.annotation_revision.capture_group_cleaning.reason_codes.join('；')}
                  </p>
                ) : null}
              </div>
            ) : task.annotation_revision.post_cleaning_result ? (
              <div className="capture-group-cleaning-fact capture-group-stack">
                <StatusBadge
                  tone={cleaningTone(task.annotation_revision.post_cleaning_result.outcome)}
                >
                  训练资格检查 ·{' '}
                  {cleaningLabel(task.annotation_revision.post_cleaning_result.outcome)}
                </StatusBadge>
                <span>
                  {formatDateTime(task.annotation_revision.post_cleaning_result.evaluated_at)}
                </span>
              </div>
            ) : (
              <p className="form-hint">Review 通过后才会形成训练资格检查事实。</p>
            )}
          </>
        ) : (
          <p className="form-hint">尚无正式 Revision；Review / 训练资格检查尚未开始。</p>
        )}
        {task ? (
          <Link
            className="text-link"
            to={paths.captureGroupFocus(
              projectId,
              captureGroupId,
              captureGroupTaskDestination(task)?.focus ?? 'annotation',
            )}
          >
            打开 CaptureGroup 任务上下文
          </Link>
        ) : null}
      </InspectorSection>

      <InspectorSection title="Next action" titleId="capture-group-next-action">
        <div className="capture-group-next-action capture-group-stack">
          <Check aria-hidden="true" size={16} strokeWidth={1.8} />
          <strong>
            {group.receipt_state === 'RECEIVING'
              ? '等待回执关闭'
              : !groupQuality || currentGroupResult === 'UNREVIEWED'
                ? '记录 Group Quality'
                : groupQualityDowngraded
                  ? '先恢复组质量准入'
                  : task && !task.primary_clip_asset_id
                    ? '选择 Primary View'
                    : task?.status === 'SUBMITTED'
                      ? '等待 Review'
                      : task?.annotation_revision?.review?.decision === 'APPROVED'
                        ? '查看训练资格检查事实'
                        : '继续 CaptureGroup Annotation'}
          </strong>
          <span>下一步只依据服务端返回的 Receipt、Quality 和 Task 状态。</span>
        </div>
      </InspectorSection>
    </aside>
  )
}
