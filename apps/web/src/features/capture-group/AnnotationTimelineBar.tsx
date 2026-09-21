import { Tooltip } from 'antd'
import { useRef } from 'react'
import type { AnnotationLabelSchema } from '@/api/annotation'
import {
  formatHumanFriendlyTime,
  getActionDisplayName,
  getActionStyle,
  type DraftAnnotationSegment,
} from '@/features/capture-group/capture-group-detail-model'

interface AnnotationTimelineBarProps {
  totalDurationMs: number
  currentPlaybackTimeMs: number | null
  visibleDraftSegments: DraftAnnotationSegment[]
  schema: AnnotationLabelSchema | null
  hasOverlapWith: (seg: DraftAnnotationSegment, allSegments: DraftAnnotationSegment[]) => boolean
  onPlaySegment?: (startMs: number, endMs: number) => void
  onPlayAtMs?: (timeMs: number) => void
  onSeekToMs?: (timeMs: number) => void
}

export function AnnotationTimelineBar({
  totalDurationMs,
  currentPlaybackTimeMs,
  visibleDraftSegments,
  schema,
  hasOverlapWith,
  onPlaySegment,
  onPlayAtMs,
  onSeekToMs,
}: AnnotationTimelineBarProps) {
  const trackBarRef = useRef<HTMLDivElement | null>(null)

  if (totalDurationMs <= 0 || visibleDraftSegments.length === 0) {
    return null
  }

  const handleTrackClick = (e: React.MouseEvent<HTMLDivElement>) => {
    if (totalDurationMs > 0) {
      const rect = e.currentTarget.getBoundingClientRect()
      const clickX = e.clientX - rect.left
      const percent = Math.max(0, Math.min(1, clickX / rect.width))
      const targetMs = Math.round(percent * totalDurationMs)
      if (onPlayAtMs) {
        onPlayAtMs(targetMs)
      } else {
        onSeekToMs?.(targetMs)
      }
    }
  }

  const handleSliceClick = (seg: DraftAnnotationSegment) => {
    if (onPlayAtMs) {
      onPlayAtMs(seg.start_ms)
    } else {
      onSeekToMs?.(seg.start_ms)
      onPlaySegment?.(seg.start_ms, seg.end_ms)
    }
    const rowEl = document.getElementById(`segment-row-${seg.draftId}`)
    if (rowEl) {
      rowEl.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
    }
  }

  return (
    <>
      <div className="capture-group-timeline-overview" aria-label="全片动作时间轨">
        <div
          ref={trackBarRef}
          className="capture-group-timeline-track-bar"
          onClick={handleTrackClick}
        >
          {visibleDraftSegments.map((seg, idx) => {
            const startSec = (seg.start_ms || 0) / 1000
            const endSec = (seg.end_ms || 0) / 1000
            const durSec = totalDurationMs / 1000
            const leftPercent = Math.max(0, Math.min(100, (startSec / durSec) * 100))
            const widthPercent = Math.max(
              0.8,
              Math.min(100 - leftPercent, (Math.max(0, endSec - startSec) / durSec) * 100),
            )
            const style = getActionStyle(seg.label)
            const displayName = getActionDisplayName(seg.label, schema)
            const isSliceActive =
              currentPlaybackTimeMs !== null &&
              Number.isFinite(seg.start_ms) &&
              Number.isFinite(seg.end_ms) &&
              currentPlaybackTimeMs >= seg.start_ms &&
              currentPlaybackTimeMs < seg.end_ms
            const segOverlaps = hasOverlapWith(seg, visibleDraftSegments)
            return (
              <Tooltip
                key={seg.draftId}
                title={`${idx + 1}. ${displayName} (${formatHumanFriendlyTime(seg.start_ms)} ~ ${formatHumanFriendlyTime(seg.end_ms)})${segOverlaps ? ' ⚠ 与其他区间重叠' : ''} · 点击跳转播放`}
              >
                <div
                  role="button"
                  aria-label={`播放时间段 ${idx + 1}：${displayName}`}
                  tabIndex={0}
                  className={`capture-group-timeline-slice${isSliceActive ? ' is-active-slice' : ''}${segOverlaps ? ' has-overlap-warning' : ''}`}
                  style={{
                    left: `${leftPercent}%`,
                    width: `${widthPercent}%`,
                    backgroundColor: style.dot,
                  }}
                  onClick={(e) => {
                    e.stopPropagation()
                    handleSliceClick(seg)
                  }}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault()
                      e.stopPropagation()
                      handleSliceClick(seg)
                    }
                  }}
                />
              </Tooltip>
            )
          })}
          {currentPlaybackTimeMs !== null && currentPlaybackTimeMs >= 0 ? (
            <div
              className="capture-group-timeline-playhead"
              style={{
                left: `${Math.max(0, Math.min(100, (currentPlaybackTimeMs / totalDurationMs) * 100))}%`,
              }}
              title={`当前播放: ${formatHumanFriendlyTime(currentPlaybackTimeMs)}`}
            />
          ) : null}
        </div>
      </div>
      <div className="capture-group-playback-position" aria-label="当前播放位置">
        <div className="capture-group-playback-counter">
          <span className="capture-group-playback-label">当前进度</span>
          <strong className="capture-group-playback-time">
            {currentPlaybackTimeMs !== null && currentPlaybackTimeMs >= 0
              ? formatHumanFriendlyTime(currentPlaybackTimeMs)
              : '00:00'}
          </strong>
          {totalDurationMs > 0 ? (
            <span className="capture-group-playback-total">
              / {formatHumanFriendlyTime(totalDurationMs)}
            </span>
          ) : null}
        </div>
        <span
          className="capture-group-keyboard-hints"
          title="空格：播放/暂停；←/→：快退/快进 1 秒（按住 Shift 5 秒）；输入框内 ↑/↓ 步进 100ms"
        >
          <kbd className="capture-group-kbd">空格</kbd> 播放 ·{' '}
          <kbd className="capture-group-kbd">←/→</kbd> 快进退
        </span>
      </div>
    </>
  )
}
