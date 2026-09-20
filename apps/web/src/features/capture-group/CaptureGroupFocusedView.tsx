import { Alert, Descriptions } from 'antd'
import { Video } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

import type { AnnotationTask } from '@/api/annotation'
import type { QualityReview } from '@/api/quality'
import { MediaReportCard, PlaybackCard } from '@/components/AssetDetailSections'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import { Section } from '@/components/workspace/Section'
import type { GroupViewRow } from '@/features/capture-group/capture-group-detail-model'
import type { CaptureGroupViewState } from '@/features/capture-group/use-capture-group-view'
import { mediaStatusLabels, viewQualityLabels } from '@/features/capture-group-presentation'

interface CaptureGroupFocusedViewProps {
  viewRows: GroupViewRow[]
  task: AnnotationTask | null
  viewReviews: Record<string, QualityReview>
  viewState: CaptureGroupViewState
  onSelectView: (clipAssetId: string) => void
  onPlaybackTimeChange?: (timeMs: number | null) => void
  isCompleted?: boolean
  externalVideoRef?: React.RefObject<HTMLVideoElement | null>
  initialPlaybackTimeMs?: number | null
}

export function CaptureGroupFocusedView({
  viewRows,
  task,
  viewReviews,
  viewState,
  onSelectView,
  onPlaybackTimeChange,
  isCompleted = false,
  externalVideoRef,
  initialPlaybackTimeMs,
}: CaptureGroupFocusedViewProps) {
  const {
    selectedStream,
    selectedDetail,
    selectedMediaReport,
    selectedMediaReportState,
    selectedMediaReportError,
    candidateResponse,
    candidateDescription: currentCandidateDescription,
    candidateTone: currentCandidateTone,
    candidateLiveStatus: currentCandidateLiveStatus,
    playbackGrant,
    playbackState,
    playbackMessage,
    playbackAvailability,
    isRequestingPlayback,
    canPlay,
    resetPlayback,
    loadSelectedView,
    startPlayback,
    onLoadedMetadata,
    onPlaybackError,
  } = viewState
  const internalVideoRef = useRef<HTMLVideoElement | null>(null)
  const videoRef = externalVideoRef ?? internalVideoRef

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const isSpace = event.code === 'Space' || event.key === ' '
      const isArrowLeft = event.key === 'ArrowLeft'
      const isArrowRight = event.key === 'ArrowRight'
      const isKeyJ = event.code === 'KeyJ'
      const isKeyK = event.code === 'KeyK'
      const isKeyL = event.code === 'KeyL'
      const isKeyI = event.code === 'KeyI'
      const isKeyO = event.code === 'KeyO'

      if (!isSpace && !isArrowLeft && !isArrowRight && !isKeyJ && !isKeyK && !isKeyL && !isKeyI && !isKeyO) {
        return
      }

      const target = event.target instanceof HTMLElement ? event.target : document.activeElement
      if (
        target instanceof HTMLElement &&
        target.closest('input, textarea, [contenteditable="true"]')
      ) {
        return
      }

      if (target instanceof HTMLElement && target.closest('button, a, summary')) {
        if (isSpace || isArrowLeft || isArrowRight) {
          event.preventDefault()
          target.blur()
        }
      }

      const video = videoRef.current
      if (!video || !playbackGrant || playbackState === 'error' || playbackState === 'expired') {
        if (isSpace && canPlay && !isRequestingPlayback && playbackState === 'idle') {
          event.preventDefault()
          void startPlayback()
        }
        return
      }

      if (isSpace || isKeyK) {
        event.preventDefault()
        if (video.paused) {
          try {
            void video.play().catch(() => undefined)
          } catch {
            // Keep native playback errors in the existing player state.
          }
        } else {
          video.pause()
        }
        return
      }

      if (isArrowLeft || isKeyJ) {
        event.preventDefault()
        const deltaSec = event.shiftKey ? 5 : 1
        video.currentTime = Math.max(0, video.currentTime - deltaSec)
        return
      }

      if (isArrowRight || isKeyL) {
        event.preventDefault()
        const deltaSec = event.shiftKey ? 5 : 1
        const maxSec =
          Number.isFinite(video.duration) && video.duration > 0 ? video.duration : Infinity
        video.currentTime = Math.min(maxSec, video.currentTime + deltaSec)
        return
      }

      if (isKeyI || isKeyO) {
        event.preventDefault()
        const timeMs = Math.round(video.currentTime * 1000)
        if (Number.isFinite(timeMs)) {
          window.dispatchEvent(
            new CustomEvent(isKeyI ? 'annotation:mark-in' : 'annotation:mark-out', {
              detail: { timeMs },
            }),
          )
        }
        return
      }
    }

    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [canPlay, isRequestingPlayback, playbackGrant, playbackState, startPlayback, videoRef])
  const isPrimary = Boolean(
    selectedStream && task?.primary_clip_asset_id === selectedStream.clip_asset_id,
  )
  const isSupporting = Boolean(selectedStream && task?.primary_clip_asset_id && !isPrimary)
  const [isDetailsOpen, setIsDetailsOpen] = useState(false)

  const focusedView = (
    <section
      className="capture-group-focused-workspace"
      aria-labelledby="capture-group-focused-title"
    >
      <h2 id="capture-group-focused-title" className="visually-hidden">
        视频
      </h2>
      {isSupporting ? (
        <Alert
          className="capture-group-supporting-warning"
          type="info"
          showIcon
          title="当前为参考视图"
          description="标注时间仍以主视图为准。"
        />
      ) : null}
      {viewRows.length > 1 ? (
        <div className="capture-group-compare-strip" role="group" aria-label="采集记录机位选择">
          <div className="capture-group-compare-list">
            {viewRows.map((view) => {
              const stream = view.stream
              const isCurrent = Boolean(
                stream && stream.clip_asset_id === selectedStream?.clip_asset_id,
              )
              const decision = viewReviews[stream?.clip_asset_id ?? ''] ?? null
              const qualityLabel = stream
                ? `机位质量 · ${viewQualityLabels[decision?.decision ?? 'UNREVIEWED']}`
                : null
              const mediaLabel = stream
                ? (mediaStatusLabels[stream.stream_status] ?? '媒体状态暂不可用')
                : '缺失'
              return (
                <button
                  className={`capture-group-compare-item${isCurrent ? ' is-current' : ''}`}
                  type="button"
                  key={view.cameraNumber}
                  disabled={!stream}
                  aria-pressed={isCurrent}
                  aria-current={isCurrent ? 'true' : undefined}
                  aria-label={[
                    `机位 ${view.cameraNumber}`,
                    mediaLabel,
                    qualityLabel,
                    stream && stream.clip_asset_id === task?.primary_clip_asset_id
                      ? '标注基准'
                      : null,
                  ]
                    .filter(Boolean)
                    .join(' · ')}
                  title={[mediaLabel, qualityLabel].filter(Boolean).join(' · ')}
                  onClick={() => stream && onSelectView(stream.clip_asset_id)}
                  onKeyDown={(event) => {
                    if (
                      event.key !== 'ArrowLeft' &&
                      event.key !== 'ArrowRight' &&
                      event.key !== 'ArrowUp' &&
                      event.key !== 'ArrowDown'
                    ) {
                      return
                    }
                    const available = viewRows.filter((v) => Boolean(v.stream))
                    const currentIndex = available.findIndex(
                      (v) => v.stream?.clip_asset_id === stream?.clip_asset_id,
                    )
                    if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
                      event.preventDefault()
                      if (currentIndex >= 0 && currentIndex < available.length - 1) {
                        const next = available[currentIndex + 1]
                        if (next?.stream) onSelectView(next.stream.clip_asset_id)
                      }
                    } else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
                      event.preventDefault()
                      if (currentIndex > 0) {
                        const prev = available[currentIndex - 1]
                        if (prev?.stream) onSelectView(prev.stream.clip_asset_id)
                      }
                    }
                  }}
                >
                  <span className="capture-group-compare-thumbnail" aria-hidden="true">
                    <Video size={16} strokeWidth={1.7} />
                  </span>
                  <span>
                    <strong>机位 {view.cameraNumber}</strong>
                    {stream && stream.clip_asset_id === task?.primary_clip_asset_id ? (
                      <small>· 标注基准</small>
                    ) : null}
                    {stream?.stream_status !== 'INGESTED' ? <small>{mediaLabel}</small> : null}
                  </span>
                </button>
              )
            })}
          </div>
        </div>
      ) : null}
      <PlaybackCard
        availability={playbackAvailability}
        candidateOverlay={candidateResponse?.candidates ?? []}
        canPlay={Boolean(canPlay)}
        expandToWorkspace={isCompleted}
        grant={playbackGrant}
        isRequestingPlayback={isRequestingPlayback}
        mediaProbeMessage={selectedDetail?.status === 'INGEST_FAILED' ? '媒体检测失败。' : ''}
        playbackMessage={playbackMessage}
        playbackState={playbackState}
        externalVideoRef={videoRef}
        initialPlaybackTimeMs={initialPlaybackTimeMs}
        onPlaybackTimeChange={onPlaybackTimeChange ?? undefined}
        onClose={() => {
          onPlaybackTimeChange?.(null)
          resetPlayback()
        }}
        onLoadedMetadata={onLoadedMetadata}
        onPlaybackError={() => {
          onPlaybackTimeChange?.(null)
          onPlaybackError()
        }}
        onStartPlayback={() => {
          onPlaybackTimeChange?.(null)
          void startPlayback()
        }}
      />
      {playbackState === 'ready' ? (
        <div className="capture-group-playback-shortcuts" aria-label="播放器快捷键提示">
          <span>
            快捷键：<kbd className="capture-group-kbd">空格</kbd> /{' '}
            <kbd className="capture-group-kbd">K</kbd> 播放暂停 ·{' '}
            <kbd className="capture-group-kbd">←</kbd> / <kbd className="capture-group-kbd">→</kbd>{' '}
            快退快进 · <kbd className="capture-group-kbd">J</kbd> /{' '}
            <kbd className="capture-group-kbd">L</kbd> 步进 1 秒（按住{' '}
            <kbd className="capture-group-kbd">Shift</kbd> 5 秒）
          </span>
        </div>
      ) : null}
      {selectedDetail || selectedStream ? (
        <>
          <StatusIndicator ariaLive="polite" tone={currentCandidateTone}>
            手部检测：{currentCandidateLiveStatus}
          </StatusIndicator>
          <details
            className="capture-group-focused-details"
            open={isDetailsOpen}
            onToggle={(event) => setIsDetailsOpen(event.currentTarget.open)}
          >
            <summary>查看检测与媒体信息</summary>
            <div className="capture-group-focused-details-content">
              <div className="capture-group-focused-facts">
                <Section title="手部检测" titleId="capture-group-candidate-title">
                  <div className="capture-group-candidate-facts capture-group-stack">
                    <p>{currentCandidateDescription}</p>
                    {candidateResponse?.job.run_summary ? (
                      <Descriptions
                        column={{ xs: 1, sm: 2 }}
                        items={[
                          {
                            key: 'frames',
                            label: '分析帧数',
                            children: candidateResponse.job.run_summary.inference_frame_count,
                          },
                          {
                            key: 'hands',
                            label: '发现手部帧',
                            children: candidateResponse.job.run_summary.frames_with_hand,
                          },
                          {
                            key: 'left',
                            label: '左手检测次数',
                            children: candidateResponse.job.run_summary.left_hand_detection_count,
                          },
                          {
                            key: 'right',
                            label: '右手检测次数',
                            children: candidateResponse.job.run_summary.right_hand_detection_count,
                          },
                          ...(candidateResponse.job.run_summary.failure_reason
                            ? [
                                {
                                  key: 'failure',
                                  label: '失败原因',
                                  children: candidateResponse.job.run_summary.failure_reason,
                                },
                              ]
                            : []),
                        ]}
                      />
                    ) : null}
                  </div>
                </Section>
                {selectedDetail ? (
                  <MediaReportCard
                    detail={selectedDetail}
                    errorCode={
                      selectedMediaReportState === 'error' ? 'MEDIA_REPORT_UNAVAILABLE' : ''
                    }
                    errorMessage={selectedMediaReportError}
                    loadState={selectedMediaReportState}
                    onRetry={() => void loadSelectedView()}
                    report={selectedMediaReport}
                  />
                ) : null}
              </div>
            </div>
          </details>
        </>
      ) : null}
    </section>
  )

  return isCompleted ? <section aria-label="视频与辅助证据">{focusedView}</section> : focusedView
}
