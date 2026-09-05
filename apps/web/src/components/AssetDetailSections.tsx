import { Alert, Button, Descriptions, Select } from 'antd'
import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type MutableRefObject,
} from 'react'
import { Link } from 'react-router'

import type { AnnotationTask } from '@/api/annotation'
import type {
  ClipAssetPlaybackGrant,
  ClipAssetRead,
  HandPoseCandidate,
  MediaReport,
} from '@/api/catalog'
import { HandPoseOverlay } from '@/components/HandPoseOverlay'
import { StatusIndicator, type StatusIndicatorTone } from '@/components/status/StatusIndicator'
import { LoadingState } from '@/components/WorkspaceFeedback'
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
} from '@/features/catalog/catalog-presentation'

export type PlaybackState = 'idle' | 'waiting' | 'ready' | 'error' | 'expired'

export interface PlaybackAvailability {
  title: string
  description: string
  type: 'success' | 'warning' | 'error' | 'info'
}

function formatPlaybackExpiry(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return formatDateTime(value)
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

interface PlaybackCardProps {
  availability: PlaybackAvailability | null
  candidateOverlay: HandPoseCandidate[]
  canPlay: boolean
  canRecoverMissingMediaProbe: boolean
  expandToWorkspace?: boolean
  grant: ClipAssetPlaybackGrant | null
  isRecoveringMediaProbe: boolean
  isRequestingPlayback: boolean
  mediaProbeMessage: string
  playbackMessage: string
  playbackState: PlaybackState
  externalVideoRef?: MutableRefObject<HTMLVideoElement | null>
  onPlaybackTimeChange?: (timeMs: number) => void
  onClose: () => void
  onLoadedMetadata: () => void
  onPlaybackError: () => void
  onRecoverMissingMediaProbe: () => void
  onStartPlayback: () => void
}

export function PlaybackCard({
  availability,
  candidateOverlay,
  canPlay,
  canRecoverMissingMediaProbe,
  expandToWorkspace = false,
  grant,
  isRecoveringMediaProbe,
  isRequestingPlayback,
  mediaProbeMessage,
  playbackMessage,
  playbackState,
  externalVideoRef,
  onPlaybackTimeChange,
  onClose,
  onLoadedMetadata,
  onPlaybackError,
  onRecoverMissingMediaProbe,
  onStartPlayback,
}: PlaybackCardProps) {
  const playbackFailed = playbackState === 'error' || playbackState === 'expired'
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const videoStageRef = useRef<HTMLDivElement | null>(null)
  const [playbackTimeMs, setPlaybackTimeMs] = useState(0)
  const [viewportSize, setViewportSize] = useState(() => ({
    width: typeof window === 'undefined' ? 1440 : window.innerWidth,
    height: typeof window === 'undefined' ? 900 : window.innerHeight,
  }))
  const [isFullscreen, setIsFullscreen] = useState(false)
  const [fullscreenError, setFullscreenError] = useState('')

  const stageSize = useMemo(() => {
    // Keep the review surface landscape even when the source video is portrait.
    // The video and overlay both use `contain`, so the source stays proportional
    // and is letterboxed inside the same coordinate system.
    const aspectRatio = 16 / 9
    // The stage should use the available work-column width. Active annotation
    // keeps a viewport-height cap so the editor remains close at hand; completed
    // read-only evidence can expand to the full workspace width.
    const maxWidth = Math.max(320, viewportSize.width - 80)
    const maxHeight = Math.min(600, Math.max(280, viewportSize.height - 240))
    let width = maxWidth
    let height = width / aspectRatio
    if (!expandToWorkspace && height > maxHeight) {
      height = maxHeight
      width = height * aspectRatio
    }
    return {
      aspectRatio,
      height: Math.round(height),
      width: Math.round(width),
    }
  }, [expandToWorkspace, viewportSize])

  useEffect(() => {
    const video = videoRef.current
    if (!video) return undefined
    const withVideoFrameCallback = video as HTMLVideoElement & {
      cancelVideoFrameCallback?: (handle: number) => void
      requestVideoFrameCallback?: (
        callback: (now: number, metadata: { mediaTime: number }) => void,
      ) => number
    }
    let frameHandle: number | undefined
    let animationFrame: number | undefined
    if (withVideoFrameCallback.requestVideoFrameCallback) {
      const onVideoFrame = (_now: number, metadata: { mediaTime: number }) => {
        setPlaybackTimeMs(metadata.mediaTime * 1000)
        frameHandle = withVideoFrameCallback.requestVideoFrameCallback?.(onVideoFrame)
      }
      frameHandle = withVideoFrameCallback.requestVideoFrameCallback(onVideoFrame)
    } else {
      const onAnimationFrame = () => {
        setPlaybackTimeMs(video.currentTime * 1000)
        animationFrame = window.requestAnimationFrame(onAnimationFrame)
      }
      animationFrame = window.requestAnimationFrame(onAnimationFrame)
    }
    return () => {
      if (frameHandle !== undefined) withVideoFrameCallback.cancelVideoFrameCallback?.(frameHandle)
      if (animationFrame !== undefined) window.cancelAnimationFrame(animationFrame)
    }
  }, [grant?.grant_id])

  useEffect(() => {
    const onFullscreenChange = () => {
      setIsFullscreen(document.fullscreenElement === videoStageRef.current)
    }
    document.addEventListener('fullscreenchange', onFullscreenChange)
    return () => document.removeEventListener('fullscreenchange', onFullscreenChange)
  }, [])

  useEffect(() => {
    const onResize = () => {
      setViewportSize({ width: window.innerWidth, height: window.innerHeight })
    }
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [])

  async function toggleFullscreen() {
    const stage = videoStageRef.current
    if (!stage) return
    setFullscreenError('')
    try {
      if (document.fullscreenElement === stage) {
        if (typeof document.exitFullscreen === 'function') await document.exitFullscreen()
        return
      }
      if (typeof stage.requestFullscreen !== 'function') {
        setFullscreenError('当前浏览器不支持视频与骨架叠加的全屏查看。')
        return
      }
      await stage.requestFullscreen()
    } catch {
      setFullscreenError('全屏查看未启动，请检查浏览器权限后重试。')
    }
  }

  return (
    <section className="playback-panel" aria-label="视频查看">
      {!grant && availability ? (
        <div
          className={`playback-idle-stage playback-availability-${availability.type}`}
          role="status"
        >
          <div className="playback-idle-copy">
            <strong>{availability.title}</strong>
            <span>{availability.description}</span>
          </div>
          {canPlay ? (
            <Button
              className="playback-start-action"
              size="middle"
              loading={isRequestingPlayback}
              onClick={onStartPlayback}
            >
              播放视频
            </Button>
          ) : null}
        </div>
      ) : null}
      {grant ? (
        <>
          <div className="playback-meta" aria-label="本次播放授权">
            <span className="playback-meta-label">本次播放授权</span>
            <time
              dateTime={grant.expires_at}
              title={`完整到期时间：${formatDateTime(grant.expires_at)}`}
            >
              有效至 {formatPlaybackExpiry(grant.expires_at)}
            </time>
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
            <div
              ref={videoStageRef}
              className={`video-stage${expandToWorkspace ? ' video-stage-fill' : ''}`}
              style={
                {
                  '--video-stage-height': `${stageSize.height}px`,
                  '--video-stage-ratio': stageSize.aspectRatio,
                  '--video-stage-width': `${stageSize.width}px`,
                  aspectRatio: '16 / 9',
                } as CSSProperties
              }
            >
              <video
                ref={(node) => {
                  videoRef.current = node
                  if (externalVideoRef) externalVideoRef.current = node
                }}
                className="review-video"
                controls
                controlsList="nodownload nofullscreen noremoteplayback"
                disablePictureInPicture
                preload="none"
                playsInline
                aria-label="视频预览"
                src={grant.media_url}
                onLoadedMetadata={(event) => {
                  setPlaybackTimeMs(event.currentTarget.currentTime * 1000)
                  onPlaybackTimeChange?.(event.currentTarget.currentTime * 1000)
                  onLoadedMetadata()
                }}
                onTimeUpdate={(event) => {
                  setPlaybackTimeMs(event.currentTarget.currentTime * 1000)
                  onPlaybackTimeChange?.(event.currentTarget.currentTime * 1000)
                }}
                onError={onPlaybackError}
              >
                当前浏览器不支持视频播放。
              </video>
              <HandPoseOverlay candidates={candidateOverlay} playbackTimeMs={playbackTimeMs} />
              <div className="video-stage-actions">
                <Button
                  className="video-stage-fullscreen-button"
                  size="small"
                  aria-label={isFullscreen ? '退出全屏查看' : '全屏查看视频和骨架'}
                  aria-pressed={isFullscreen}
                  onClick={() => void toggleFullscreen()}
                >
                  {isFullscreen ? '退出全屏' : '全屏查看'}
                </Button>
              </div>
            </div>
          ) : null}
          <p className="form-hint playback-fullscreen-hint" role="note">
            全屏仅用于查看；退出后返回工作台继续操作。
          </p>
          {candidateOverlay.length > 0 ? (
            <div className="playback-supporting-note" aria-label="骨架叠加说明">
              <span>
                <strong>骨架叠加</strong> · 21 个关键点与连接线
              </span>
              <span>左 / 右仅作方向辅助</span>
            </div>
          ) : null}
          {fullscreenError ? (
            <p className="form-hint playback-feedback" role="status">
              {fullscreenError}
            </p>
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
      {mediaProbeMessage ? (
        <p className="form-hint media-probe-message">{mediaProbeMessage}</p>
      ) : null}
      {canRecoverMissingMediaProbe ? (
        <div className="playback-toolbar media-probe-toolbar">
          <Button loading={isRecoveringMediaProbe} onClick={onRecoverMissingMediaProbe}>
            恢复丢失的检测任务
          </Button>
          <p className="form-hint">仅在确认原任务消息不存在时使用；将创建新的审计尝试。</p>
        </div>
      ) : null}
    </section>
  )
}

interface MediaReportCardProps {
  detail: ClipAssetRead
  errorCode: string
  errorMessage: string
  loadState: 'idle' | 'loading' | 'success' | 'error'
  onRetry: () => void
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
  onRetry,
  report,
}: MediaReportCardProps) {
  let content
  if (detail.status === 'INGESTED' && loadState === 'loading') {
    content = <LoadingState>正在读取媒体检测报告…</LoadingState>
  } else if (detail.status === 'INGESTED' && loadState === 'error') {
    content = (
      <Alert
        type="error"
        title={errorMessage}
        description={errorCode ? `错误码：${errorCode}` : undefined}
        action={
          <Button size="small" onClick={onRetry}>
            重试读取
          </Button>
        }
        showIcon
      />
    )
  } else if (detail.status === 'INGESTED' && report) {
    const warnings = report.warning_codes
    content = (
      <>
        <div className="media-report-summary">
          <StatusIndicator tone="success">完整解码已验证</StatusIndicator>
          <span className="form-hint">完成时间：{formatDateTime(report.created_at)}</span>
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
        description="该视频仍可人工查看，但不能记录质量结论或进入配药动作标注流程。"
        showIcon
      />
    )
  } else {
    content = (
      <p className="form-hint">
        {detail.status === 'INGESTING' ? '媒体检测进行中' : '媒体检测尚未开始'}
        。检测完成后将在这里显示时长、分辨率、编码和完整解码结果。
      </p>
    )
  }

  return (
    <section
      className="workbench-panel"
      aria-labelledby="media-report-title"
      aria-busy={loadState === 'loading'}
    >
      <h3 id="media-report-title">媒体检测报告</h3>
      {content}
    </section>
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
  const reviewTone: StatusIndicatorTone =
    review?.decision === 'QUALIFIED'
      ? 'success'
      : review?.decision === 'REJECTED'
        ? 'destructive'
        : review?.decision === 'NEEDS_REVIEW'
          ? 'warning'
          : 'neutral'
  const showEditorInline =
    !review || review.decision === 'UNREVIEWED' || review.decision === 'NEEDS_REVIEW'
  const reviewEditor = (
    <div className="quality-review-actions">
      <Select
        value={selectedDecision}
        aria-label="质量结论"
        onChange={(decision) =>
          onSelectionChange(decision, reasonsByDecision[decision][0] ?? 'MEETS_REQUIREMENTS')
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
  )

  return (
    <section className="workbench-panel" aria-labelledby="quality-review-title">
      <h3 id="quality-review-title">人工质量判断</h3>
      <p className="form-hint">查看画面，判断视频是否可进入配药动作标注。</p>
      {loadState === 'loading' ? <LoadingState>正在读取质量结论…</LoadingState> : null}
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
            <StatusIndicator tone={assetStatus === 'INGESTED' ? reviewTone : 'neutral'}>
              {assetStatus === 'INGESTED' ? qualityDecisionLabels[review.decision] : '待媒体检测'}
            </StatusIndicator>
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
            <p className="form-hint">
              媒体检测完成后才能记录质量结论；当前视频仍可直接播放并供人工预览。
            </p>
          ) : canReview ? (
            showEditorInline ? (
              reviewEditor
            ) : (
              <details className="quality-review-editor">
                <summary>修改质量结论</summary>
                {reviewEditor}
              </details>
            )
          ) : (
            <p className="form-hint">当前账号无权记录质量结论。</p>
          )}
        </>
      ) : null}
    </section>
  )
}

interface AnnotationTaskCardProps {
  createdTask: AnnotationTask | null
  projectId: string
}

export function AnnotationTaskCard({ createdTask, projectId }: AnnotationTaskCardProps) {
  return (
    <section className="workbench-panel" aria-labelledby="annotation-task-title">
      <h3 id="annotation-task-title">配药动作标注</h3>
      {createdTask ? (
        <>
          <div className="annotation-task-created">
            <StatusIndicator tone="success">已创建并分配</StatusIndicator>
            <span>执行人：{createdTask.assignee_actor_id}</span>
          </div>
          <p className="form-hint">
            质量合格后系统已自动分配任务。请在标注页提交时间段，项目管理员随后复核。
          </p>
          <Link className="text-link" to={paths.annotationTasks(projectId)}>
            查看配药动作标注
          </Link>
        </>
      ) : (
        <>
          <p className="form-hint">
            质量结论为合格后，系统会自动创建并分配配药动作标注任务。若当前账号没有可见任务，请刷新状态或前往任务列表查看。
          </p>
        </>
      )}
    </section>
  )
}

export function AssetFacts({ detail }: { detail: ClipAssetRead }) {
  return (
    <section className="workbench-panel" aria-labelledby="asset-facts-title">
      <h3 id="asset-facts-title">视频信息</h3>
      <h4>文件信息</h4>
      <Descriptions
        column={{ xs: 1, md: 2 }}
        items={[
          {
            key: 'asset',
            label: '视频资产 ID',
            children: <code>{detail.clip_asset_id}</code>,
          },
          { key: 'content', label: '内容类型', children: detail.content_type },
          { key: 'size', label: '文件大小', children: formatBytes(detail.size_bytes) },
          {
            key: 'batch',
            label: '批次 ID',
            children: <code>{detail.batch_id}</code>,
          },
          {
            key: 'sha',
            label: 'SHA-256',
            span: { xs: 1, md: 2 },
            children: <code className="mono-wrap">{detail.sha256}</code>,
          },
          {
            key: 'source',
            label: '源上传文件 ID',
            children: <code>{detail.source_upload_file_id}</code>,
          },
          { key: 'created', label: '创建时间', children: formatDateTime(detail.created_at) },
          { key: 'updated', label: '更新时间', children: formatDateTime(detail.updated_at) },
        ]}
      />
      <h4>相机绑定</h4>
      {detail.session_binding ? (
        <Descriptions
          column={{ xs: 1, md: 2 }}
          items={[
            {
              key: 'session',
              label: '所属会话',
              children: <code>{detail.session_binding.capture_session_id}</code>,
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
              label: '视频流检测',
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
        <p className="empty-state">该视频已完成上传校验，但尚未绑定到配药操作记录。</p>
      )}
    </section>
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
    <section className="danger-zone-inline" aria-labelledby="danger-zone-title">
      <div>
        <span className="app-eyebrow">低频高风险操作</span>
        <strong id="danger-zone-title">删除视频</strong>
        <p className="form-hint">
          从普通列表隐藏此视频并保留审计记录；不会立即删除存储对象，也不影响同一配药操作
          的其他视频。
        </p>
      </div>
      <Button danger loading={isDeleting} onClick={onDelete}>
        删除视频
      </Button>
    </section>
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
