import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { HandPoseOverlay } from '@/components/HandPoseOverlay'

const landmarks = Array.from({ length: 21 }, (_, index) => ({
  x: 0.1 + index * 0.01,
  y: 0.2 + index * 0.01,
  z: 0,
}))

describe('HandPoseOverlay', () => {
  it('draws one nearest frame as a boxed 21-point skeleton', () => {
    const { container } = render(
      <HandPoseOverlay
        playbackTimeMs={1_010}
        candidates={[
          {
            frame_timestamp_ms: 1_000,
            image_width: 1280,
            image_height: 720,
            hand_index: 0,
            handedness: 'LEFT',
            handedness_score: 0.91,
            bounding_box: { x: 0.1, y: 0.2, width: 0.3, height: 0.4 },
            landmarks,
            valid_landmark_count: 21,
          },
          {
            frame_timestamp_ms: 1_200,
            image_width: 1280,
            image_height: 720,
            hand_index: 0,
            handedness: 'RIGHT',
            handedness_score: 0.88,
            bounding_box: { x: 0.1, y: 0.2, width: 0.3, height: 0.4 },
            landmarks,
            valid_landmark_count: 21,
          },
        ]}
      />,
    )

    expect(screen.getByLabelText('当前视频帧的手部姿态候选')).toHaveAttribute(
      'viewBox',
      '0 0 1280 720',
    )
    expect(container.querySelectorAll('.hand-pose-bounding-box')).toHaveLength(1)
    expect(container.querySelectorAll('.hand-pose-bone')).toHaveLength(21)
    expect(container.querySelectorAll('.hand-pose-landmark')).toHaveLength(21)
    expect(container.querySelector('.hand-pose-bounding-box')).toHaveAttribute('x', '128')
    expect(container.querySelector('.hand-pose-bounding-box')).toHaveAttribute('y', '144')
    expect(container.querySelector('.hand-pose-bounding-box')).toHaveAttribute('width', '384')
    expect(container.querySelector('.hand-pose-bounding-box')).toHaveAttribute('height', '288')
    expect(screen.getByText('LEFT 0.91')).toBeInTheDocument()
  })

  it('renders both hands from the nearest sampled timestamp', () => {
    const { container } = render(
      <HandPoseOverlay
        playbackTimeMs={1_010}
        candidates={[
          {
            frame_timestamp_ms: 1_000,
            image_width: 1280,
            image_height: 720,
            hand_index: 0,
            handedness: 'LEFT',
            handedness_score: 0.91,
            bounding_box: { x: 0.1, y: 0.2, width: 0.3, height: 0.4 },
            landmarks,
            valid_landmark_count: 21,
          },
          {
            frame_timestamp_ms: 1_000,
            image_width: 1280,
            image_height: 720,
            hand_index: 1,
            handedness: 'RIGHT',
            handedness_score: 0.88,
            bounding_box: { x: 0.1, y: 0.2, width: 0.3, height: 0.4 },
            landmarks,
            valid_landmark_count: 21,
          },
          {
            frame_timestamp_ms: 1_200,
            image_width: 1280,
            image_height: 720,
            hand_index: 0,
            handedness: 'RIGHT',
            handedness_score: 0.77,
            bounding_box: { x: 0.1, y: 0.2, width: 0.3, height: 0.4 },
            landmarks,
            valid_landmark_count: 21,
          },
        ]}
      />,
    )

    expect(container.querySelectorAll('.hand-pose-bounding-box')).toHaveLength(2)
    expect(container.querySelectorAll('.hand-pose-landmark')).toHaveLength(42)
    expect(screen.getByText('LEFT 0.91')).toBeInTheDocument()
    expect(screen.getByText('RIGHT 0.88')).toBeInTheDocument()
    expect(screen.queryByText('RIGHT 0.77')).toBeNull()
  })

  it('uses milliseconds and clears a sampled frame beyond the 90 ms window', () => {
    const { container, rerender } = render(
      <HandPoseOverlay
        playbackTimeMs={1_090}
        candidates={[
          {
            frame_timestamp_ms: 1_000,
            image_width: 1280,
            image_height: 720,
            hand_index: 0,
            handedness: 'LEFT',
            handedness_score: 0.91,
            bounding_box: { x: 0.1, y: 0.2, width: 0.3, height: 0.4 },
            landmarks,
            valid_landmark_count: 21,
          },
        ]}
      />,
    )

    expect(container.querySelector('.hand-pose-bounding-box')).toBeInTheDocument()

    rerender(
      <HandPoseOverlay
        playbackTimeMs={1_091}
        candidates={[
          {
            frame_timestamp_ms: 1_000,
            image_width: 1280,
            image_height: 720,
            hand_index: 0,
            handedness: 'LEFT',
            handedness_score: 0.91,
            bounding_box: { x: 0.1, y: 0.2, width: 0.3, height: 0.4 },
            landmarks,
            valid_landmark_count: 21,
          },
        ]}
      />,
    )

    expect(container.firstChild).toBeNull()
  })
})
