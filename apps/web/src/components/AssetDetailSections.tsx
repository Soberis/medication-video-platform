import { Alert, Button, Card, Descriptions, Select, Tag } from 'antd'
import { Link } from 'react-router'

import type { AnnotationTask } from '@/api/annotation'
import type { ClipAssetPlaybackGrant, ClipAssetRead, MediaReport } from '@/api/catalog'
import type { AssetFolder, ProjectMembership } from '@/api/projects'
import {
  qualityDecisionLabels,
  qualityReasonLabels,
  type QualityReview,
  type QualityReviewCommandDecision,
  type QualityReviewReasonCode,
} from '@/api/quality'
import { paths } from '@/app/paths'
import {
  declaredSyncStatusLabels,
  formatBytes,
  formatDateTime,
  qualityStatusLabels,
  streamStatusLabels,
} from '@/catalog-presentation'

export type PlaybackState = 'idle' | 'waiting' | 'ready' | 'error' | 'expired'

export interface PlaybackAvailability {
  title: string
  description: string
  type: 'success' | 'warning' | 'error' | 'info'
}

interface PlaybackCardProps {
  availability: PlaybackAvailability | null
  canPlay: boolean
  canRecoverMissingMediaProbe: boolean
  canRequestMediaProbe: boolean
  grant: ClipAssetPlaybackGrant | null
  isRecoveringMediaProbe: boolean
  isRequestingMediaProbe: boolean
  isRequestingPlayback: boolean
  mediaProbeMessage: string
  playbackMessage: string
  playbackState: PlaybackState
  onClose: () => void
  onLoadedMetadata: () => void
  onPlaybackError: () => void
  onRecoverMissingMediaProbe: () => void
  onRequestMediaProbe: () => void
  onStartPlayback: () => void
}

export function PlaybackCard({
  availability,
  canPlay,
  canRecoverMissingMediaProbe,
  canRequestMediaProbe,
  grant,
  isRecoveringMediaProbe,
  isRequestingMediaProbe,
  isRequestingPlayback,
  mediaProbeMessage,
  playbackMessage,
  playbackState,
  onClose,
  onLoadedMetadata,
  onPlaybackError,
  onRecoverMissingMediaProbe,
  onRequestMediaProbe,
  onStartPlayback,
}: PlaybackCardProps) {
  const playbackFailed = playbackState === 'error' || playbackState === 'expired'

  return (
    <Card
      className="detail-card playback-card"
      title={<strong>视频查看</strong>}
      variant="outlined"
    >
      {!grant && availability ? (
        <Alert
          title={availability.title}
          description={availability.description}
          type={availability.type}
          showIcon
        />
      ) : null}
      {canPlay && !grant ? (
        <Button
          className="playback-primary-action"
          type="primary"
          loading={isRequestingPlayback}
          onClick={onStartPlayback}
        >
          播放视频
        </Button>
      ) : null}
      {grant ? (
        <>
          <div className="playback-meta">
            <span>授权有效至 {formatDateTime(grant.expires_at)}</span>
            <Tag color="success">人工查看</Tag>
          </div>
          {playbackMessage ? (
            <Alert
              className="playback-feedback"
              title={playbackMessage}
              type={playbackFailed ? 'error' : 'info'}
              showIcon
            />
          ) : null}
          {!playbackFailed ? (
            <div className="video-stage">
              <video
                className="review-video"
                controls
                preload="none"
                playsInline
                aria-label="视频预览"
                src={grant.media_url}
                onLoadedMetadata={onLoadedMetadata}
                onError={onPlaybackError}
              >
                当前浏览器不支持视频播放。
              </video>
            </div>
          ) : null}
          <div className="playback-toolbar">
            {playbackFailed ? (
              <Button type="primary" loading={isRequestingPlayback} onClick={onStartPlayback}>
                重新获取播放授权
              </Button>
            ) : null}
            <Button onClick={onClose}>关闭视频</Button>
          </div>
        </>
      ) : null}
      {canRequestMediaProbe ? (
        <div className="playback-toolbar media-probe-toolbar">
          <Button loading={isRequestingMediaProbe} onClick={onRequestMediaProbe}>
            检测媒体信息
          </Button>
          <p className="form-hint">补充时长、编码和完整解码结果，不影响直接查看。</p>
        </div>
      ) : null}
      {mediaProbeMessage ? (
        <p className="form-hint media-probe-message">{mediaProbeMessage}</p>
      ) : null}
      {canRecoverMissingMediaProbe ? (
        <div className="playback-toolbar media-probe-toolbar">
          <Button loading={isRecoveringMediaProbe} onClick={onRecoverMissingMediaProbe}>
            恢复已丢失的检测任务
          </Button>
          <p className="form-hint">
            仅在 RabbitMQ 已确认原任务消息不存在时使用；会创建新的受审计尝试。
          </p>
        </div>
      ) : null}
    </Card>
  )
}

interface MediaReportCardProps {
  detail: ClipAssetRead
  errorCode: string
  errorMessage: string
  loadState: 'idle' | 'loading' | 'success' | 'error'
  report: MediaReport | null
}

const mediaWarningLabels: Record<string, string> = {
  PTS_MISSING: '时间戳缺失',
  PTS_NON_MONOTONIC: '时间戳不连续',
  FRAME_COUNT_UNAVAILABLE: '无法统计帧数',
  DURATION_MISMATCH: '时长存在差异',
  VFR_DETECTED: '可变帧率',
  AUDIO_PRESENT: '包含音轨',
}

export function MediaReportCard({
  detail,
  errorCode,
  errorMessage,
  loadState,
  report,
}: MediaReportCardProps) {
  let content
  if (detail.status === 'INGESTED' && loadState === 'loading') {
    content = (
      <div className="catalog-notice" role="status">
        正在读取媒体检测报告…
      </div>
    )
  } else if (detail.status === 'INGESTED' && loadState === 'error') {
    content = (
      <Alert
        type="error"
        title={errorMessage}
        description={errorCode ? `错误码：${errorCode}` : undefined}
        showIcon
      />
    )
  } else if (detail.status === 'INGESTED' && report) {
    const warnings = report.warning_codes
    content = (
      <>
        <div className="media-report-summary">
          <Tag color="success">完整解码已验证</Tag>
          <span className="form-hint">检测完成于 {formatDateTime(report.created_at)}</span>
        </div>
        <Descriptions
          column={{ xs: 1, sm: 2, lg: 3 }}
          className="media-report-facts"
          items={[
            {
              key: 'duration',
              label: '时长',
              children: formatMediaDuration(report.duration_microseconds),
            },
            { key: 'image', label: '画面', children: `${report.width} × ${report.height}` },
            {
              key: 'codec',
              label: '编码',
              children: `${report.video_codec}${report.video_profile ? ` · ${report.video_profile}` : ''}`,
            },
            {
              key: 'fps',
              label: '帧率',
              children: formatFrameRate(
                report.average_frame_rate_numerator,
                report.average_frame_rate_denominator,
              ),
            },
            {
              key: 'bitrate',
              label: '视频码率',
              children: formatBitrate(report.primary_video_bitrate_bps),
            },
            {
              key: 'audio',
              label: '音轨',
              children: report.audio_stream_count ? `${report.audio_stream_count} 条` : '无',
            },
          ]}
        />
        {warnings.length > 0 ? (
          <Alert
            className="media-report-warnings"
            type="warning"
            title="检测发现需关注项"
            description={warnings.map((code) => mediaWarningLabels[code] ?? code).join('；')}
            showIcon
          />
        ) : null}
      </>
    )
  } else if (detail.status === 'INGEST_FAILED') {
    content = (
      <Alert
        type="error"
        title="媒体检测失败"
        description="该视频仍可人工查看，但不能记录质量结论或进入标注流程。"
        showIcon
      />
    )
  } else {
    content = (
      <Alert
        type="info"
        title={detail.status === 'INGESTING' ? '媒体检测进行中' : '媒体检测尚未开始'}
        description="检测完成后将在这里显示时长、分辨率、编码和完整解码结果。"
        showIcon
      />
    )
  }

  return (
    <Card className="detail-card" title={<strong>媒体检测报告</strong>} variant="outlined">
      {content}
    </Card>
  )
}

interface QualityReviewCardProps {
  assetStatus: ClipAssetRead['status']
  canReview: boolean
  errorCode: string
  errorMessage: string
  isSaving: boolean
  loadState: 'loading' | 'success' | 'error'
  review: QualityReview | null
  selectedDecision: QualityReviewCommandDecision
  selectedReason: QualityReviewReasonCode
  onSave: () => void
  onSelectionChange: (
    decision: QualityReviewCommandDecision,
    reason: QualityReviewReasonCode,
  ) => void
}

const reasonsByDecision: Record<QualityReviewCommandDecision, QualityReviewReasonCode[]> = {
  QUALIFIED: ['MEETS_REQUIREMENTS'],
  REJECTED: [
    'SENSITIVE_CONTENT',
    'BLURRY_OR_OUT_OF_FOCUS',
    'BLACK_OR_CORRUPTED_FRAMES',
    'SEVERE_OCCLUSION',
    'INCOMPLETE_ACTION',
    'WRONG_SCENE',
    'DUPLICATE',
    'OTHER_QUALITY_FAILURE',
  ],
  NEEDS_REVIEW: ['UNCERTAIN_QUALITY', 'METADATA_MISMATCH'],
}

export function QualityReviewCard({
  assetStatus,
  canReview,
  errorCode,
  errorMessage,
  isSaving,
  loadState,
  review,
  selectedDecision,
  selectedReason,
  onSave,
  onSelectionChange,
}: QualityReviewCardProps) {
  const reasonOptions = reasonsByDecision[selectedDecision]
  const tagColor =
    review?.decision === 'QUALIFIED'
      ? 'success'
      : review?.decision === 'REJECTED'
        ? 'error'
        : review?.decision === 'NEEDS_REVIEW'
          ? 'warning'
          : 'default'

  return (
    <Card className="detail-card" title={<strong>人工质量判断</strong>} variant="outlined">
      <p className="form-hint">
        根据实际画面判断该视频是否适合进入标注。播放本身不会自动产生质量结论。
      </p>
      {loadState === 'loading' ? (
        <div className="catalog-notice" role="status">
          正在读取质量结论…
        </div>
      ) : null}
      {loadState === 'error' ? (
        <Alert
          type="error"
          title={errorMessage}
          description={errorCode ? `错误码：${errorCode}` : undefined}
          showIcon
        />
      ) : null}
      {loadState === 'success' && review ? (
        <>
          <div className="quality-review-summary">
            <Tag color={assetStatus === 'INGESTED' ? tagColor : 'default'}>
              {assetStatus === 'INGESTED' ? qualityDecisionLabels[review.decision] : '待技术检测'}
            </Tag>
            {assetStatus === 'INGESTED' && review.reason_code ? (
              <span>{qualityReasonLabels[review.reason_code]}</span>
            ) : null}
            {assetStatus === 'INGESTED' && review.reviewed_by && review.reviewed_at ? (
              <span className="form-hint">
                {review.reviewed_by} · {formatDateTime(review.reviewed_at)}
              </span>
            ) : null}
          </div>
          {assetStatus !== 'INGESTED' ? (
            <Alert
              title="技术检测完成后才能记录质量结论"
              description="当前视频仍可直接播放并供人工预览；检测只补充时长、编码和完整解码等技术事实。"
              type="info"
              showIcon
            />
          ) : (
            <div className="quality-review-actions">
              <Select
                value={selectedDecision}
                aria-label="质量结论"
                onChange={(decision) =>
                  onSelectionChange(
                    decision,
                    reasonsByDecision[decision][0] ?? 'MEETS_REQUIREMENTS',
                  )
                }
                options={[
                  { label: '合格', value: 'QUALIFIED' },
                  { label: '不合格', value: 'REJECTED' },
                  { label: '需复核', value: 'NEEDS_REVIEW' },
                ]}
              />
              <Select
                value={selectedReason}
                aria-label="质量原因"
                onChange={(reason) => onSelectionChange(selectedDecision, reason)}
                options={reasonOptions.map((reason) => ({
                  value: reason,
                  label: qualityReasonLabels[reason],
                }))}
              />
              <Button type="primary" loading={isSaving} disabled={!canReview} onClick={onSave}>
                保存质量结论
              </Button>
            </div>
          )}
        </>
      ) : null}
    </Card>
  )
}

interface AnnotationTaskCardProps {
  assignees: ProjectMembership[]
  createdTask: AnnotationTask | null
  isCreating: boolean
  projectId: string
  selectedAssignee: string
  onCreate: () => void
  onSelectedAssigneeChange: (actorId: string) => void
}

export function AnnotationTaskCard({
  assignees,
  createdTask,
  isCreating,
  projectId,
  selectedAssignee,
  onCreate,
  onSelectedAssigneeChange,
}: AnnotationTaskCardProps) {
  return (
    <Card className="detail-card" title={<strong>标注任务</strong>} variant="outlined">
      {createdTask ? (
        <>
          <Alert
            title="标注任务已创建并分配"
            description={`执行人：${createdTask.assignee_actor_id}。请在标注任务页提交时间段标注；当前项目管理员可对提交结果进行复核。`}
            type="success"
            showIcon
          />
          <Link className="text-link" to={paths.annotationTasks(projectId)}>
            查看标注任务
          </Link>
        </>
      ) : (
        <>
          <p className="form-hint">该视频已通过人工质量判断。请选择项目成员并创建标注任务。</p>
          <div className="annotation-task-actions">
            <Select
              value={selectedAssignee}
              aria-label="标注任务执行人"
              onChange={onSelectedAssigneeChange}
              options={assignees.map((member) => ({
                value: member.actor_id,
                label: `${member.actor_id} · ${member.role === 'ADMIN' ? '管理员' : '普通用户'}`,
              }))}
            />
            <Button
              type="primary"
              loading={isCreating}
              disabled={!selectedAssignee}
              onClick={onCreate}
            >
              创建标注任务
            </Button>
          </div>
        </>
      )}
    </Card>
  )
}

interface ClassificationCardProps {
  changed: boolean
  folders: AssetFolder[]
  isSaving: boolean
  selectedFolderId: string
  onSave: () => void
  onSelectedFolderChange: (folderId: string) => void
}

export function ClassificationCard({
  changed,
  folders,
  isSaving,
  selectedFolderId,
  onSave,
  onSelectedFolderChange,
}: ClassificationCardProps) {
  return (
    <Card className="detail-card" title={<strong>视频分类</strong>} variant="outlined">
      <p className="form-hint">仅在当前项目内归类；取消分类不会删除视频，也不会改变项目权限。</p>
      <div className="asset-classification-actions">
        <Select
          value={selectedFolderId}
          aria-label="视频文件夹"
          onChange={onSelectedFolderChange}
          options={[
            { label: '未分类', value: '' },
            ...folders.map((folder) => ({
              label: folder.display_name,
              value: folder.folder_id,
            })),
          ]}
        />
        <Button type="primary" disabled={!changed} loading={isSaving} onClick={onSave}>
          更新分类
        </Button>
      </div>
    </Card>
  )
}

export function AssetFacts({ detail, projectId }: { detail: ClipAssetRead; projectId: string }) {
  return (
    <>
      <Card className="detail-card" title={<strong>文件信息</strong>} variant="outlined">
        <Descriptions
          column={{ xs: 1, md: 2 }}
          items={[
            { key: 'content', label: '内容类型', children: detail.content_type },
            { key: 'size', label: '文件大小', children: formatBytes(detail.size_bytes) },
            {
              key: 'folder',
              label: '文件夹',
              children: detail.folder_id ? <code>{detail.folder_id}</code> : '未分类',
            },
            {
              key: 'batch',
              label: '批次 ID',
              children: <code>{detail.batch_id}</code>,
            },
            {
              key: 'source',
              label: '源上传文件 ID',
              span: { xs: 1, md: 2 },
              children: <code>{detail.source_upload_file_id}</code>,
            },
            {
              key: 'sha',
              label: 'SHA-256',
              span: { xs: 1, md: 2 },
              children: <code className="mono-wrap">{detail.sha256}</code>,
            },
            { key: 'created', label: '创建时间', children: formatDateTime(detail.created_at) },
            { key: 'updated', label: '更新时间', children: formatDateTime(detail.updated_at) },
          ]}
        />
      </Card>
      <Card className="detail-card" title={<strong>相机绑定</strong>} variant="outlined">
        {detail.session_binding ? (
          <Descriptions
            column={{ xs: 1, md: 2 }}
            items={[
              {
                key: 'session',
                label: '所属 Session',
                children: (
                  <Link
                    className="text-link"
                    to={paths.session(projectId, detail.session_binding.capture_session_id)}
                  >
                    {detail.session_binding.capture_session_id}
                  </Link>
                ),
              },
              {
                key: 'camera',
                label: '相机角色',
                children: `${detail.session_binding.camera_role} · #${detail.session_binding.camera_index}`,
              },
              {
                key: 'mount',
                label: '安装位置',
                children: detail.session_binding.mount_position,
              },
              {
                key: 'recorded',
                label: '拍摄开始',
                children: formatDateTime(detail.session_binding.record_start_utc),
              },
              {
                key: 'stream',
                label: '流状态',
                children: streamStatusLabels[detail.session_binding.stream_status],
              },
              {
                key: 'quality',
                label: '流技术检测',
                children: qualityStatusLabels[detail.session_binding.quality_status],
              },
              {
                key: 'sync',
                label: '边端同步声明',
                children: declaredSyncStatusLabels[detail.session_binding.declared_sync_status],
              },
              {
                key: 'offset',
                label: '边端声明偏移',
                children: `${detail.session_binding.time_offset_ms} ms`,
              },
            ]}
          />
        ) : (
          <p className="empty-state">该资产已校验上传，但尚未绑定到 CaptureSession。</p>
        )}
      </Card>
    </>
  )
}

export function DangerZoneCard({
  isDeleting,
  onDelete,
}: {
  isDeleting: boolean
  onDelete: () => void
}) {
  return (
    <Card
      className="detail-card"
      title={<strong>删除视频</strong>}
      extra={
        <Button danger loading={isDeleting} onClick={onDelete}>
          删除视频
        </Button>
      }
      variant="outlined"
    >
      <p className="form-hint">
        从普通列表隐藏此视频并保留审计记录；不会立即删除存储对象，也不影响同一 Session 的其他视频。
      </p>
    </Card>
  )
}

function formatMediaDuration(value: number) {
  const seconds = Math.floor(value / 1_000_000)
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

function formatFrameRate(numerator: number | null, denominator: number | null) {
  if (!numerator || !denominator) return '未提供'
  return `${new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(numerator / denominator)} fps`
}

function formatBitrate(value: number | null) {
  if (!value) return '未提供'
  return `${new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 1 }).format(value / 1_000_000)} Mbps`
}
