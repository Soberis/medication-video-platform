import { App as AntdApp, Button, Descriptions, Tooltip } from 'antd'
import { Keyboard, RotateCcw, Video } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

import { retryHandPoseDetection } from '@/api/capture-groups'
import { MediaReportCard, PlaybackCard } from '@/components/AssetDetailSections'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import { Section } from '@/components/workspace/Section'
import type { GroupViewRow } from '@/features/capture-group/capture-group-detail-model'
import type { CaptureGroupViewState } from '@/features/capture-group/use-capture-group-view'
import { mediaStatusLabels } from '@/features/capture-group-presentation'

import type { AnnotationTask } from '@/api/annotation'
import { getCameraProcessingStatus } from '@/features/capture-group/capture-group-detail-model'

interface CaptureGroupFocusedViewProps {
  viewRows: GroupViewRow[]
  viewState: CaptureGroupViewState
  task?: AnnotationTask | null
  onSelectView: (clipAssetId: string) => void
  onPlaybackTimeChange?: (timeMs: number | null) => void
  isCompleted?: boolean
  externalVideoRef?: React.RefObject<HTMLVideoElement | null>
  initialPlaybackTimeMs?: number | null
  projectId?: string
}

export function CaptureGroupFocusedView({
  viewRows,
  viewState,
  task = null,
  onSelectView,
  onPlaybackTimeChange,
  isCompleted = false,
  externalVideoRef,
  initialPlaybackTimeMs,
  projectId = '',
}: CaptureGroupFocusedViewProps) {
  const { message } = AntdApp.useApp()
  const [isRetryingHandPose, setIsRetryingHandPose] = useState(false)
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

  const handleRetryHandPose = async () => {
    const clipId = selectedDetail?.clip_asset_id || selectedStream?.clip_asset_id
    if (!clipId) return
    setIsRetryingHandPose(true)
    try {
      await retryHandPoseDetection({ clipAssetId: clipId, projectId })
      void message.success('已重新发起手部检测，后台正在分析中…')
      void loadSelectedView()
    } catch (err) {
      void message.error(`重试失败: ${err instanceof Error ? err.message : String(err)}`)
    } finally {
      setIsRetryingHandPose(false)
    }
  }

  useEffect(() => {
    function isEditableTarget(target: EventTarget | null): boolean {
      if (!target || !(target instanceof HTMLElement)) return false
      const tagName = target.tagName.toLowerCase()
      if (tagName === 'textarea' || tagName === 'select') return true
      if (tagName === 'input') {
        const type = (target as HTMLInputElement).type.toLowerCase()
        return !['checkbox', 'radio', 'range', 'button', 'submit', 'reset'].includes(type)
      }
      if (target.isContentEditable) return true
      if (
        target.closest(
          'input:not([type="checkbox"]):not([type="radio"]):not([type="button"]):not([type="submit"]), textarea, [contenteditable="true"], .ant-select-dropdown, .ant-picker-dropdown, .ant-modal, .ant-popconfirm',
        )
      ) {
        return true
      }
      return false
    }

    const onKeyDown = (event: KeyboardEvent) => {
      if (isEditableTarget(event.target)) {
        return
      }

      const isSpace = event.code === 'Space' || event.key === ' '
      const isArrowLeft = event.key === 'ArrowLeft'
      const isArrowRight = event.key === 'ArrowRight'
      const isArrowUp = event.key === 'ArrowUp'
      const isArrowDown = event.key === 'ArrowDown'
      const isKeyJ = event.code === 'KeyJ' || event.key === 'j' || event.key === 'J'
      const isKeyK = event.code === 'KeyK' || event.key === 'k' || event.key === 'K'
      const isKeyL = event.code === 'KeyL' || event.key === 'l' || event.key === 'L'
      const isKeyM = event.code === 'KeyM' || event.key === 'm' || event.key === 'M'
      const isKeyF = event.code === 'KeyF' || event.key === 'f' || event.key === 'F'
      const isKeyI = event.code === 'KeyI' || event.key === 'i' || event.key === 'I'
      const isKeyO = event.code === 'KeyO' || event.key === 'o' || event.key === 'O'
      const isComma = event.code === 'Comma' || event.key === ',' || event.key === '<'
      const isPeriod = event.code === 'Period' || event.key === '.' || event.key === '>'
      const isHome = event.key === 'Home'
      const isEnd = event.key === 'End'
      const isDigit =
        /^[0-9]$/.test(event.key) &&
        !event.ctrlKey &&
        !event.altKey &&
        !event.metaKey &&
        !event.shiftKey

      const isHandledKey =
        isSpace ||
        isArrowLeft ||
        isArrowRight ||
        isArrowUp ||
        isArrowDown ||
        isKeyJ ||
        isKeyK ||
        isKeyL ||
        isKeyM ||
        isKeyF ||
        isKeyI ||
        isKeyO ||
        isComma ||
        isPeriod ||
        isHome ||
        isEnd ||
        isDigit

      if (!isHandledKey) {
        return
      }

      const activeEl = document.activeElement
      if (activeEl instanceof HTMLElement && activeEl.closest('button, a, summary, [role="button"]')) {
        activeEl.blur()
      }

      const video = videoRef.current
      if (!video || !playbackGrant || playbackState === 'error' || playbackState === 'expired') {
        if (isSpace && canPlay && !isRequestingPlayback && playbackState === 'idle') {
          event.preventDefault()
          void startPlayback()
        }
        return
      }

      // 1. 空格 / K：播放 / 暂停
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

      // 2. 左箭头 / J：快退（普通 1 秒，按住 Shift 5 秒）
      if (isArrowLeft || isKeyJ) {
        event.preventDefault()
        const deltaSec = event.shiftKey ? 5 : 1
        video.currentTime = Math.max(0, video.currentTime - deltaSec)
        return
      }

      // 3. 右箭头 / L：快进（普通 1 秒，按住 Shift 5 秒）
      if (isArrowRight || isKeyL) {
        event.preventDefault()
        const deltaSec = event.shiftKey ? 5 : 1
        const maxSec =
          Number.isFinite(video.duration) && video.duration > 0 ? video.duration : Infinity
        video.currentTime = Math.min(maxSec, video.currentTime + deltaSec)
        return
      }

      // 4. 逗号（,）/ 句号（.）：逐帧步退 / 步进（单帧约 0.04 秒 / 40ms）
      if (isComma) {
        event.preventDefault()
        video.currentTime = Math.max(0, video.currentTime - 0.04)
        return
      }
      if (isPeriod) {
        event.preventDefault()
        const maxSec =
          Number.isFinite(video.duration) && video.duration > 0 ? video.duration : Infinity
        video.currentTime = Math.min(maxSec, video.currentTime + 0.04)
        return
      }

      // 5. 上箭头 / 下箭头：音量调节（±10%）
      if (isArrowUp) {
        event.preventDefault()
        video.muted = false
        video.volume = Math.min(1, Math.round((video.volume + 0.1) * 10) / 10)
        return
      }
      if (isArrowDown) {
        event.preventDefault()
        video.volume = Math.max(0, Math.round((video.volume - 0.1) * 10) / 10)
        return
      }

      // 6. M 键：静音 / 取消静音
      if (isKeyM) {
        event.preventDefault()
        video.muted = !video.muted
        return
      }

      // 7. F 键：全屏 / 退出全屏
      if (isKeyF) {
        event.preventDefault()
        const stage = video.closest('.video-stage') as HTMLElement | null
        if (document.fullscreenElement) {
          if (typeof document.exitFullscreen === 'function') void document.exitFullscreen()
        } else if (stage && typeof stage.requestFullscreen === 'function') {
          void stage.requestFullscreen()
        }
        return
      }

      // 8. 数字键 0 ~ 9：跳转至对应百分比（0% ~ 90%）
      if (isDigit && Number.isFinite(video.duration) && video.duration > 0) {
        event.preventDefault()
        const percent = parseInt(event.key, 10) * 0.1
        video.currentTime = video.duration * percent
        return
      }

      // 9. Home / End：跳至开头 / 结尾
      if (isHome) {
        event.preventDefault()
        video.currentTime = 0
        return
      }
      if (isEnd && Number.isFinite(video.duration) && video.duration > 0) {
        event.preventDefault()
        video.currentTime = Math.max(0, video.duration - 0.1)
        return
      }

      // 10. I / O：动作标注打入点 / 打出点
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

    const onKeyUp = (event: KeyboardEvent) => {
      const isSpace = event.code === 'Space' || event.key === ' '
      if (isSpace && !isEditableTarget(event.target)) {
        event.preventDefault()
      }
    }

    window.addEventListener('keydown', onKeyDown)
    window.addEventListener('keyup', onKeyUp)
    return () => {
      window.removeEventListener('keydown', onKeyDown)
      window.removeEventListener('keyup', onKeyUp)
    }
  }, [canPlay, isRequestingPlayback, playbackGrant, playbackState, startPlayback, videoRef])

  const [isDetailsOpen, setIsDetailsOpen] = useState(false)

  const focusedView = (
    <section
      className="capture-group-focused-workspace"
      aria-labelledby="capture-group-focused-title"
    >
      <h2 id="capture-group-focused-title" className="visually-hidden">
        视频
      </h2>
      {viewRows.length > 1 ? (
        <div className="capture-group-compare-strip" role="group" aria-label="采集记录机位选择">
          <div className="capture-group-compare-list">
            {viewRows.map((view) => {
              const stream = view.stream
              const isCurrent = Boolean(
                stream && stream.clip_asset_id === selectedStream?.clip_asset_id,
              )
              const cameraStatus = task ? getCameraProcessingStatus(view.cameraNumber, task) : null
              const isExcluded = cameraStatus?.status === 'EXCLUDED'
              const statusLabel = cameraStatus
                ? cameraStatus.status === 'SUBMITTED' && cameraStatus.segmentCount !== null
                  ? `${cameraStatus.label} ${cameraStatus.segmentCount} 段`
                  : cameraStatus.label
                : null
              const mediaLabel = stream
                ? (mediaStatusLabels[stream.stream_status] ?? '媒体状态暂不可用')
                : '缺失'
              const isStreamError =
                !stream ||
                stream.stream_status === 'INGEST_FAILED' ||
                stream.stream_status === 'UNAVAILABLE'
              return (
                <button
                  className={`capture-group-compare-item${isCurrent ? ' is-current' : ''}${isExcluded ? ' is-excluded' : ''}${cameraStatus ? ` is-camera-${cameraStatus.status.toLowerCase()}` : ''}`}
                  type="button"
                  key={view.cameraNumber}
                  disabled={!stream}
                  aria-pressed={isCurrent}
                  aria-current={isCurrent ? 'true' : undefined}
                  aria-label={[`机位 ${view.cameraNumber}`, statusLabel, mediaLabel]
                    .filter(Boolean)
                    .join(' · ')}
                  title={[
                    `机位 ${view.cameraNumber}`,
                    mediaLabel,
                    cameraStatus ? `标注：${statusLabel}` : null,
                  ]
                    .filter(Boolean)
                    .join(' · ')}
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
                  <span
                    className="capture-group-compare-thumbnail capture-group-compare-icon"
                    aria-hidden="true"
                  >
                    <Video size={14} strokeWidth={1.75} />
                  </span>
                  <span className="capture-group-compare-name">机位 {view.cameraNumber}</span>
                  {isCurrent && (isExcluded || cameraStatus) ? (
                    <span
                      className={`capture-group-compare-badge is-${isExcluded ? 'excluded' : cameraStatus!.status.toLowerCase()}`}
                    >
                      {isExcluded ? '已排除' : cameraStatus!.label}
                    </span>
                  ) : null}
                  {isStreamError ? (
                    <span className="capture-group-compare-status is-error">
                      {!stream ? '缺失' : mediaLabel}
                    </span>
                  ) : null}
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
      {selectedDetail || selectedStream ? (
        <details
          className="capture-group-focused-details"
          open={isDetailsOpen}
          onToggle={(event) => setIsDetailsOpen(event.currentTarget.open)}
        >
          <summary className="capture-group-focused-summary">
            <div className="capture-group-candidate-status-left">
              <StatusIndicator ariaLive="polite" tone={currentCandidateTone}>
                手部检测：{currentCandidateLiveStatus}
              </StatusIndicator>
              {currentCandidateTone === 'destructive' ||
              candidateResponse?.job.status === 'FAILED' ||
              candidateResponse?.job.hand_result === 'NO_HAND_DETECTED' ? (
                <Button
                  type="text"
                  size="small"
                  icon={<RotateCcw size={14} strokeWidth={1.8} />}
                  loading={isRetryingHandPose}
                  onClick={(e) => {
                    e.stopPropagation()
                    void handleRetryHandPose()
                  }}
                >
                  重新检测
                </Button>
              ) : null}
            </div>
            <div className="capture-group-candidate-status-right">
              {playbackState === 'ready' ? (
                <span onClick={(e) => e.stopPropagation()}>
                  <Tooltip
                    title={
                      <div style={{ lineHeight: 1.6, fontSize: '12px' }}>
                        <div><b>空格</b> / <b>K</b>：播放 / 暂停</div>
                        <div><b>←</b> / <b>→</b>：快退 / 快进 1 秒</div>
                        <div><b>J</b> / <b>L</b>：步进 1 秒（Shift 5 秒）</div>
                      </div>
                    }
                  >
                    <span className="capture-group-shortcut-pill">
                      <Keyboard size={13} strokeWidth={1.8} style={{ marginRight: 4, verticalAlign: 'middle' }} />
                      快捷键
                    </span>
                  </Tooltip>
                </span>
              ) : null}
              <span className="capture-group-details-toggle-label">查看检测与媒体信息</span>
            </div>
          </summary>
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
      ) : null}
    </section>
  )

  return isCompleted ? <section aria-label="视频与辅助证据">{focusedView}</section> : focusedView
}
