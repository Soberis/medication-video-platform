import { Alert, Button, Descriptions } from 'antd'
import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type MutableRefObject,
} from 'react'

import type {
  ClipAssetPlaybackGrant,
  ClipAssetRead,
  HandPoseCandidate,
  MediaReport,
} from '@/api/catalog'
import { HandPoseOverlay } from '@/components/HandPoseOverlay'
import { StatusBadge } from '@/components/StatusBadge'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import { LoadingState } from '@/components/WorkspaceFeedback'

export type PlaybackState = 'idle' | 'waiting' | 'ready' | 'error' | 'expired'

export interface PlaybackAvailability {
  title: string
  description: string
  type: 'success' | 'warning' | 'error' | 'info'
}

interface PlaybackCardProps {
  availability: PlaybackAvailability | null
  candidateOverlay: HandPoseCandidate[]
  canPlay: boolean
  expandToWorkspace?: boolean
  grant: ClipAssetPlaybackGrant | null
  isRequestingPlayback: boolean
  mediaProbeMessage: string
  playbackMessage: string
  playbackState: PlaybackState
  externalVideoRef?: MutableRefObject<HTMLVideoElement | null>
  initialPlaybackTimeMs?: number | null
  onPlaybackTimeChange?: (timeMs: number) => void
  onClose: () => void
  onLoadedMetadata: () => void
  onPlaybackError: () => void
  onStartPlayback: () => void
}

export function PlaybackCard({
  availability,
  candidateOverlay,
  canPlay,
  expandToWorkspace = false,
  grant,
  isRequestingPlayback,
  mediaProbeMessage,
  playbackMessage,
  playbackState,
  externalVideoRef,
  initialPlaybackTimeMs,
  onPlaybackTimeChange,
  onClose,
  onLoadedMetadata,
  onPlaybackError,
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
                preload="auto"
                playsInline
                aria-label="视频预览"
                src={grant.media_url}
                onLoadedMetadata={(event) => {
                  if (initialPlaybackTimeMs && initialPlaybackTimeMs > 0) {
                    const seekSec = Math.max(0, initialPlaybackTimeMs / 1000)
                    const targetSec = event.currentTarget.duration
                      ? Math.min(seekSec, event.currentTarget.duration)
                      : seekSec
                    event.currentTarget.currentTime = targetSec
                    const resolvedMs = Math.round(targetSec * 1000)
                    setPlaybackTimeMs(resolvedMs)
                    onPlaybackTimeChange?.(resolvedMs)
                  } else {
                    setPlaybackTimeMs(event.currentTarget.currentTime * 1000)
                    onPlaybackTimeChange?.(event.currentTarget.currentTime * 1000)
                  }
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
          {fullscreenError ? (
            <p className="form-hint playback-feedback" role="status">
              {fullscreenError}
            </p>
          ) : null}
          <div className="playback-toolbar">
            {playbackFailed ? (
              <Button type="primary" loading={isRequestingPlayback} onClick={onStartPlayback}>
                重新加载视频
              </Button>
            ) : null}
            <Button onClick={onClose}>关闭视频</Button>
          </div>
        </>
      ) : null}
      {mediaProbeMessage ? (
        <p className="form-hint media-probe-message">{mediaProbeMessage}</p>
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
          <div className="media-report-warning-tags" aria-label="媒体附加信息">
            <span className="media-report-warning-label">附加信息</span>
            <div className="media-report-warning-list">
              {warnings.map((code) => (
                <StatusBadge key={code} tone="default">
                  {mediaWarningLabels[code] ?? code}
                </StatusBadge>
              ))}
            </div>
          </div>
        ) : null}
      </>
    )
  } else if (detail.status === 'INGEST_FAILED') {
    content = (
      <Alert
        type="error"
        title="媒体检测失败"
        description="该视频仍可人工查看，但不能记录质量结论或开始配药动作标注。"
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
