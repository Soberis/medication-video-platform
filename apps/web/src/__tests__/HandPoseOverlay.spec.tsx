import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { HandPoseOverlay } from '@/components/HandPoseOverlay'
import { PlaybackCard } from '@/components/AssetDetailSections'

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

    expect(screen.getByLabelText('当前视频帧的手部检测结果')).toHaveAttribute(
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
    expect(screen.getByText('手部 · Left 0.91')).toBeInTheDocument()
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
    expect(screen.getByText('手部 · Left 0.91')).toBeInTheDocument()
    expect(screen.getByText('手部 · Right 0.88')).toBeInTheDocument()
    expect(screen.queryByText('手部 · Right 0.77')).toBeNull()
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

  it('uses the shared video stage for fullscreen so the overlay stays with the video', () => {
    const { container } = render(
      <PlaybackCard
        availability={null}
        candidateOverlay={[
          {
            frame_timestamp_ms: 0,
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
        canPlay
        grant={{
          grant_id: 'grant-fullscreen',
          purpose: 'DATA_REVIEW',
          media_url: '/synthetic/authorized-hand.mp4',
          expires_at: '2026-08-12T10:00:00Z',
          content_type: 'video/mp4',
        }}
        isRequestingPlayback={false}
        mediaProbeMessage=""
        playbackMessage=""
        playbackState="ready"
        onClose={() => undefined}
        onLoadedMetadata={() => undefined}
        onPlaybackError={() => undefined}
        onStartPlayback={() => undefined}
      />,
    )
    const stage = container.querySelector('.video-stage') as HTMLDivElement
    const requestFullscreen = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(stage, 'requestFullscreen', {
      configurable: true,
      value: requestFullscreen,
    })

    fireEvent.click(screen.getByRole('button', { name: '全屏查看视频和骨架' }))

    expect(requestFullscreen).toHaveBeenCalledTimes(1)
    expect(stage.querySelector('video')).toBe(screen.getByLabelText('视频预览'))
    expect(stage.querySelector('.hand-pose-overlay')).toBeInTheDocument()
    expect(screen.getByLabelText('视频预览')).toHaveAttribute(
      'controlslist',
      'nodownload nofullscreen noremoteplayback',
    )
    expect(screen.getByLabelText('视频预览')).toHaveAttribute('disablepictureinpicture')
  })

  it('keeps a landscape review stage for portrait video', () => {
    const { container } = render(
      <PlaybackCard
        availability={null}
        candidateOverlay={[
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
        canPlay
        grant={{
          grant_id: 'grant-1',
          purpose: 'DATA_REVIEW',
          media_url: '/synthetic/authorized-hand.mp4',
          expires_at: '2026-08-12T10:00:00Z',
          content_type: 'video/mp4',
        }}
        isRequestingPlayback={false}
        mediaProbeMessage=""
        playbackMessage=""
        playbackState="ready"
        onClose={() => undefined}
        onLoadedMetadata={() => undefined}
        onPlaybackError={() => undefined}
        onStartPlayback={() => undefined}
      />,
    )
    const video = screen.getByLabelText('视频预览')
    Object.defineProperties(video, {
      currentTime: { configurable: true, value: 1 },
      videoWidth: { configurable: true, value: 1080 },
      videoHeight: { configurable: true, value: 1920 },
    })

    fireEvent.loadedMetadata(video)
    const stage = container.querySelector('.video-stage') as HTMLDivElement
    expect(stage).toHaveStyle('aspect-ratio: 16 / 9')
    expect(
      Number.parseInt(stage.style.getPropertyValue('--video-stage-width'), 10),
    ).toBeGreaterThan(Number.parseInt(stage.style.getPropertyValue('--video-stage-height'), 10))
    fireEvent.play(video)
    fireEvent.pause(video)
    fireEvent.timeUpdate(video)
    expect(container.querySelector('.hand-pose-overlay')).toBeInTheDocument()

    Object.defineProperty(video, 'currentTime', { configurable: true, value: 1.091 })
    fireEvent.timeUpdate(video)
    expect(container.querySelector('.hand-pose-overlay')).toBeNull()
  })
})
