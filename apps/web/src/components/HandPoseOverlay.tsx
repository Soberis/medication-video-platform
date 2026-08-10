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

interface HandPoseOverlayProps {
  candidates: HandPoseCandidate[]
  playbackTimeMs: number
}

function isFinitePoint(
  point: HandPoseCandidate['landmarks'][number] | undefined,
): point is HandPoseCandidate['landmarks'][number] {
  return point !== undefined && Number.isFinite(point.x) && Number.isFinite(point.y)
}

function currentFrame(candidates: HandPoseCandidate[], playbackTimeMs: number) {
  const frames = new Map<number, HandPoseCandidate[]>()
  for (const candidate of candidates) {
    const frame = frames.get(candidate.frame_timestamp_ms) ?? []
    frame.push(candidate)
    frames.set(candidate.frame_timestamp_ms, frame)
  }
  let nearestTimestamp: number | null = null
  let nearestDistance = Number.POSITIVE_INFINITY
  for (const timestamp of frames.keys()) {
    const distance = Math.abs(timestamp - playbackTimeMs)
    if (distance < nearestDistance) {
      nearestTimestamp = timestamp
      nearestDistance = distance
    }
  }
  return nearestTimestamp !== null && nearestDistance <= OVERLAY_WINDOW_MS
    ? (frames.get(nearestTimestamp) ?? [])
    : []
}

function overlayColor(candidate: HandPoseCandidate) {
  return candidate.handedness === 'RIGHT' ? '#d4388d' : '#0f9f95'
}

export function HandPoseOverlay({ candidates, playbackTimeMs }: HandPoseOverlayProps) {
  const frame = useMemo(
    () => currentFrame(candidates, playbackTimeMs),
    [candidates, playbackTimeMs],
  )
  const dimensions = frame[0]
    ? { width: frame[0].image_width, height: frame[0].image_height }
    : null

  if (!dimensions || dimensions.width <= 0 || dimensions.height <= 0) return null

  return (
    <svg
      className="hand-pose-overlay"
      aria-label="当前视频帧的手部姿态候选"
      viewBox={`0 0 ${dimensions.width} ${dimensions.height}`}
      preserveAspectRatio="xMidYMid meet"
    >
      <title>当前视频帧的手部姿态候选</title>
      {frame.map((candidate) => {
        const color = overlayColor(candidate)
        const box = candidate.bounding_box
        const label = `${candidate.handedness === 'UNKNOWN' ? 'HAND' : candidate.handedness} ${candidate.handedness_score.toFixed(2)}`
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
