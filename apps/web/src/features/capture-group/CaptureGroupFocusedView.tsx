import { Alert, Descriptions } from 'antd'
import { Clock3, Video } from 'lucide-react'
import { useEffect, useRef } from 'react'

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
}

export function CaptureGroupFocusedView({
  viewRows,
  task,
  viewReviews,
  viewState,
  onSelectView,
  onPlaybackTimeChange,
  isCompleted = false,
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
    selectedSync,
    resetPlayback,
    loadSelectedView,
    startPlayback,
    onLoadedMetadata,
    onPlaybackError,
  } = viewState
  const videoRef = useRef<HTMLVideoElement | null>(null)

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.code !== 'Space' && event.key !== ' ') || event.defaultPrevented || event.repeat) {
        return
      }
      const target = event.target instanceof HTMLElement ? event.target : document.activeElement
      if (
        target instanceof HTMLElement &&
        target.closest(
          'input, textarea, select, button, a, summary, video, [contenteditable="true"], [role="combobox"], [role="listbox"]',
        )
      ) {
        return
      }
      const video = videoRef.current
      if (!video || !playbackGrant || playbackState === 'error' || playbackState === 'expired') {
        return
      }
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
    }

    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [playbackGrant, playbackState])
  const isPrimary = Boolean(
    selectedStream && task?.primary_clip_asset_id === selectedStream.clip_asset_id,
  )
  const isSupporting = Boolean(selectedStream && task?.primary_clip_asset_id && !isPrimary)

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
        canRecoverMissingMediaProbe={false}
        expandToWorkspace={isCompleted}
        grant={playbackGrant}
        isRecoveringMediaProbe={false}
        isRequestingPlayback={isRequestingPlayback}
        mediaProbeMessage={
          selectedDetail?.status === 'INGEST_FAILED'
            ? '媒体检测失败是媒体事实；不改变接收状态，也不自动判定机位质量。'
            : ''
        }
        playbackMessage={playbackMessage}
        playbackState={playbackState}
        externalVideoRef={videoRef}
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
        onRecoverMissingMediaProbe={() => undefined}
        onStartPlayback={() => {
          onPlaybackTimeChange?.(null)
          void startPlayback()
        }}
      />
      {selectedDetail || selectedStream ? (
        <StatusIndicator ariaLive="polite" tone={currentCandidateTone}>
          手部检测：{currentCandidateLiveStatus}
        </StatusIndicator>
      ) : null}
      {selectedDetail || selectedStream ? (
        <details className="capture-group-focused-details">
          <summary>查看检测与媒体信息</summary>
          <div className="capture-group-focused-details-content">
            {selectedSync && viewRows.length > 1 ? (
              <div className="capture-group-sync-note capture-group-stack" role="status">
                <Clock3 aria-hidden="true" size={16} strokeWidth={1.8} />
                <div>
                  <StatusIndicator tone={selectedSync.tone}>{selectedSync.label}</StatusIndicator>
                  <p>{selectedSync.detail}</p>
                </div>
              </div>
            ) : null}
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
                  errorCode={selectedMediaReportState === 'error' ? 'MEDIA_REPORT_UNAVAILABLE' : ''}
                  errorMessage={selectedMediaReportError}
                  loadState={selectedMediaReportState}
                  onRetry={() => void loadSelectedView()}
                  report={selectedMediaReport}
                />
              ) : null}
            </div>
          </div>
        </details>
      ) : null}
    </section>
  )

  return isCompleted ? <section aria-label="视频与辅助证据">{focusedView}</section> : focusedView
}
