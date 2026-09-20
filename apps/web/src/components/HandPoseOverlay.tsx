import { useMemo } from 'react'

import type { HandPoseCandidate } from '@/api/catalog'

const HAND_CONNECTIONS: ReadonlyArray<readonly [number, number]> = [
  [0, 1],
  [1, 2],
  [2, 3],
  [3, 4],
  [0, 5],
  [5, 6],
  [6, 7],
  [7, 8],
  [5, 9],
  [9, 10],
  [10, 11],
  [11, 12],
  [9, 13],
  [13, 14],
  [14, 15],
  [15, 16],
  [13, 17],
  [17, 18],
  [18, 19],
  [19, 20],
  [0, 17],
]

const OVERLAY_WINDOW_MS = 90
const OVERLAY_COLOR = 'var(--app-color-media-overlay)'

interface HandPoseOverlayProps {
  candidates: HandPoseCandidate[]
  playbackTimeMs: number
}

function isFinitePoint(
  point: HandPoseCandidate['landmarks'][number] | undefined,
): point is HandPoseCandidate['landmarks'][number] {
  return point !== undefined && Number.isFinite(point.x) && Number.isFinite(point.y)
}

interface IndexedFrame {
  timestampMs: number
  candidates: HandPoseCandidate[]
}

function buildIndexedFrames(candidates: HandPoseCandidate[]): IndexedFrame[] {
  if (!candidates.length) return []
  const map = new Map<number, HandPoseCandidate[]>()
  for (const candidate of candidates) {
    const list = map.get(candidate.frame_timestamp_ms)
    if (list) {
      list.push(candidate)
    } else {
      map.set(candidate.frame_timestamp_ms, [candidate])
    }
  }
  return Array.from(map.entries())
    .map(([timestampMs, items]) => ({ timestampMs, candidates: items }))
    .sort((a, b) => a.timestampMs - b.timestampMs)
}

function findNearestFrame(frames: IndexedFrame[], playbackTimeMs: number): HandPoseCandidate[] {
  if (!frames.length) return []

  let low = 0
  let high = frames.length - 1

  // After the length check above, frames[0] and frames[high] are guaranteed to exist.
  const first = frames[0]!
  const last = frames[high]!

  if (playbackTimeMs <= first.timestampMs) {
    return first.timestampMs - playbackTimeMs <= OVERLAY_WINDOW_MS ? first.candidates : []
  }
  if (playbackTimeMs >= last.timestampMs) {
    return playbackTimeMs - last.timestampMs <= OVERLAY_WINDOW_MS ? last.candidates : []
  }

  while (low <= high) {
    const mid = (low + high) >> 1
    const entry = frames[mid]!
    const diff = entry.timestampMs - playbackTimeMs
    if (diff === 0) {
      return entry.candidates
    }
    if (diff < 0) {
      low = mid + 1
    } else {
      high = mid - 1
    }
  }

  const leftEntry = frames[high]!
  const rightEntry = frames[low]!
  const leftDist = playbackTimeMs - leftEntry.timestampMs
  const rightDist = rightEntry.timestampMs - playbackTimeMs
  const nearest = leftDist <= rightDist ? leftEntry : rightEntry
  const nearestDist = Math.min(leftDist, rightDist)

  return nearestDist <= OVERLAY_WINDOW_MS ? nearest.candidates : []
}

function handednessLabel(candidate: HandPoseCandidate) {
  if (candidate.handedness === 'LEFT') return 'Left'
  if (candidate.handedness === 'RIGHT') return 'Right'
  return '辅助方向未判定'
}

export function HandPoseOverlay({ candidates, playbackTimeMs }: HandPoseOverlayProps) {
  const indexedFrames = useMemo(() => buildIndexedFrames(candidates), [candidates])
  const frame = useMemo(
    () => findNearestFrame(indexedFrames, playbackTimeMs),
    [indexedFrames, playbackTimeMs],
  )
  const dimensions = frame[0]
    ? { width: frame[0].image_width, height: frame[0].image_height }
    : null

  if (!dimensions || dimensions.width <= 0 || dimensions.height <= 0) return null

  return (
    <svg
      className="hand-pose-overlay"
      aria-label="当前视频帧的手部检测结果"
      viewBox={`0 0 ${dimensions.width} ${dimensions.height}`}
      preserveAspectRatio="xMidYMid meet"
    >
      <title>当前视频帧的手部检测结果</title>
      {frame.map((candidate) => {
        const color = OVERLAY_COLOR
        const box = candidate.bounding_box
        const label = `手部 · ${handednessLabel(candidate)} ${candidate.handedness_score.toFixed(2)}`
        const labelX = Math.max(0, box.x * dimensions.width)
        const labelY = Math.max(18, box.y * dimensions.height - 8)
        return (
          <g key={`${candidate.frame_timestamp_ms}-${candidate.hand_index}`}>
            <rect
              className="hand-pose-bounding-box"
              x={box.x * dimensions.width}
              y={box.y * dimensions.height}
              width={box.width * dimensions.width}
              height={box.height * dimensions.height}
              stroke={color}
            />
            {HAND_CONNECTIONS.map(([start, end]) => {
              const startPoint = candidate.landmarks[start]
              const endPoint = candidate.landmarks[end]
              if (!isFinitePoint(startPoint) || !isFinitePoint(endPoint)) return null
              return (
                <line
                  className="hand-pose-bone"
                  key={`${start}-${end}`}
                  x1={startPoint.x * dimensions.width}
                  y1={startPoint.y * dimensions.height}
                  x2={endPoint.x * dimensions.width}
                  y2={endPoint.y * dimensions.height}
                  stroke={color}
                />
              )
            })}
            {candidate.landmarks.map((point, index) =>
              isFinitePoint(point) ? (
                <circle
                  className="hand-pose-landmark"
                  key={index}
                  cx={point.x * dimensions.width}
                  cy={point.y * dimensions.height}
                  r={Math.max(3, dimensions.width * 0.0035)}
                  fill={color}
                />
              ) : null,
            )}
            <text className="hand-pose-label" x={labelX} y={labelY} fill={color}>
              {label}
            </text>
          </g>
        )
      })}
    </svg>
  )
}
