import { Button, Tag } from 'antd'
import { useCallback, useEffect, useMemo, useState } from 'react'

import {
  authorizeClipAssetPlayback,
  CatalogApiError,
  getLatestHandPoseCandidates,
  requestHandPoseCandidates,
  type CameraStream,
  type ClipAssetPlaybackGrant,
  type HandPoseCandidate,
  type HandPoseJob,
} from '@/api/catalog'
import { PlaybackCard, type PlaybackState } from '@/components/AssetDetailSections'
import { statusTone, streamStatusLabels } from '@/catalog-presentation'

interface SessionCameraPlaybackProps {
  projectId: string
  streams: CameraStream[]
}

const QUEUED_RECOVERY_DELAY_MS = 5 * 60 * 1000

export function SessionCameraPlayback({ projectId, streams }: SessionCameraPlaybackProps) {
  const orderedStreams = useMemo(
    () => [...streams].sort((first, second) => first.camera_index - second.camera_index),
    [streams],
  )
  const [selectedStreamId, setSelectedStreamId] = useState<string | null>(null)
  const [playback, setPlayback] = useState<{
    assetId: string
    grant: ClipAssetPlaybackGrant
  } | null>(null)
  const [candidateData, setCandidateData] = useState<{
    assetId: string
    job: HandPoseJob
    candidates: HandPoseCandidate[]
  } | null>(null)
  const [playbackState, setPlaybackState] = useState<PlaybackState>('idle')
  const [playbackMessage, setPlaybackMessage] = useState('')
  const [requestingPlayback, setRequestingPlayback] = useState(false)
  const [requestingHandPose, setRequestingHandPose] = useState(false)
  const [handPoseMessage, setHandPoseMessage] = useState('')
  const [queuedRecoveryClock, setQueuedRecoveryClock] = useState(0)

  const selectedStream =
    orderedStreams.find((stream) => stream.camera_stream_id === selectedStreamId) ??
    orderedStreams[0] ??
    null
  const candidates =
    candidateData && candidateData.assetId === selectedStream?.clip_asset_id
      ? candidateData.candidates
      : []
  const handPoseJob =
    candidateData && candidateData.assetId === selectedStream?.clip_asset_id
      ? candidateData.job
      : null
  const canResubmitQueuedHandPose =
    handPoseJob?.status === 'QUEUED' &&
    queuedRecoveryClock - new Date(handPoseJob.updated_at).getTime() >= QUEUED_RECOVERY_DELAY_MS
  const grant =
    playback && playback.assetId === selectedStream?.clip_asset_id ? playback.grant : null

  useEffect(() => {
    if (!selectedStream) return
    let active = true
    void getLatestHandPoseCandidates(projectId, selectedStream.clip_asset_id)
      .then((response) => {
        if (active && response.job && Array.isArray(response.candidates)) {
          setCandidateData({
            assetId: selectedStream.clip_asset_id,
            job: response.job,
            candidates: response.candidates,
          })
        }
      })
      .catch((error) => {
        if (
          active &&
          !(error instanceof CatalogApiError && error.errorCode === 'AI_HAND_POSE_JOB_NOT_FOUND')
        ) {
          setHandPoseMessage('暂时无法读取手部检测状态，请稍后重新选择该相机。')
        }
      })
    return () => {
      active = false
    }
  }, [projectId, selectedStream])

  useEffect(() => {
    if (handPoseJob?.status !== 'QUEUED') return undefined
    const updatedAt = new Date(handPoseJob.updated_at).getTime()
    const updateClock = () => setQueuedRecoveryClock(Date.now())
    updateClock()
    const remaining = QUEUED_RECOVERY_DELAY_MS - (Date.now() - updatedAt)
    if (remaining <= 0) return undefined
    const timer = window.setTimeout(updateClock, remaining)
    return () => window.clearTimeout(timer)
  }, [handPoseJob])

  useEffect(() => {
    if (!handPoseJob || handPoseJob.status === 'SUCCEEDED' || handPoseJob.status === 'FAILED') {
      return undefined
    }
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible' && selectedStream) {
        void getLatestHandPoseCandidates(projectId, selectedStream.clip_asset_id)
          .then((response) => {
            if (response.job && Array.isArray(response.candidates)) {
              setCandidateData({
                assetId: selectedStream.clip_asset_id,
                job: response.job,
                candidates: response.candidates,
              })
            }
          })
          .catch(() => undefined)
      }
    }, 3_000)
    return () => window.clearInterval(timer)
  }, [handPoseJob, projectId, selectedStream])

  const startPlayback = useCallback(async () => {
    if (!selectedStream || requestingPlayback) return
    setRequestingPlayback(true)
    setPlaybackState('waiting')
    setPlaybackMessage('')
    try {
      const response = await authorizeClipAssetPlayback(projectId, selectedStream.clip_asset_id)
      setPlayback({ assetId: selectedStream.clip_asset_id, grant: response })
      setPlaybackState('ready')
    } catch (error) {
      setPlayback(null)
      setPlaybackState('error')
      setPlaybackMessage(
        error instanceof CatalogApiError ? error.message : '暂时无法获取该相机视频的播放授权。',
      )
    } finally {
      setRequestingPlayback(false)
    }
  }, [projectId, requestingPlayback, selectedStream])

  const requestHandPose = useCallback(async () => {
    if (!selectedStream || requestingHandPose || (handPoseJob && !canResubmitQueuedHandPose)) return
    setRequestingHandPose(true)
    setHandPoseMessage('')
    try {
      const job = await requestHandPoseCandidates(projectId, selectedStream.clip_asset_id)
      setCandidateData({ assetId: selectedStream.clip_asset_id, job, candidates: [] })
      setHandPoseMessage('已提交手部检测，完成后会自动在视频上显示骨架。')
    } catch (error) {
      setHandPoseMessage(
        error instanceof CatalogApiError ? error.message : '暂时无法提交手部检测，请稍后重试。',
      )
    } finally {
      setRequestingHandPose(false)
    }
  }, [canResubmitQueuedHandPose, handPoseJob, projectId, requestingHandPose, selectedStream])

  const selectStream = useCallback((cameraStreamId: string) => {
    setSelectedStreamId(cameraStreamId)
    setPlayback(null)
    setCandidateData(null)
    setPlaybackState('idle')
    setPlaybackMessage('')
    setHandPoseMessage('')
  }, [])

  if (orderedStreams.length === 0) return null

  return (
    <section className="session-camera-playback" aria-labelledby="session-camera-playback-title">
      <div className="card-header-row">
        <div>
          <strong id="session-camera-playback-title">相机视频与手部骨架</strong>
          <span className="card-header-note">
            一次只播放所选相机的原视频，不混合不同机位的候选结果
          </span>
        </div>
      </div>
      <div className="session-camera-selector" aria-label="选择相机视频">
        {orderedStreams.map((stream, position) => {
          const selected = stream.camera_stream_id === selectedStream?.camera_stream_id
          return (
            <Button
              aria-pressed={selected}
              key={stream.camera_stream_id}
              onClick={() => selectStream(stream.camera_stream_id)}
              type={selected ? 'primary' : 'default'}
            >
              相机 {position + 1}
            </Button>
          )
        })}
      </div>
      {selectedStream ? (
        <div className="session-camera-selection-summary">
          <strong>相机 {orderedStreams.indexOf(selectedStream) + 1}</strong>
          <span>{selectedStream.mount_position}</span>
          <Tag color={statusTone(selectedStream.stream_status)}>
            {streamStatusLabels[selectedStream.stream_status]}
          </Tag>
        </div>
      ) : null}
      {selectedStream ? (
        <div className="session-hand-pose-action" aria-live="polite">
          {handPoseJob ? (
            <span>
              手部检测：
              {handPoseJob.status === 'SUCCEEDED'
                ? candidates.length > 0
                  ? `已完成，找到 ${candidates.length} 个候选帧。`
                  : '已完成，未发现可显示的手部。'
                : handPoseJob.status === 'FAILED'
                  ? '未完成，请稍后重试。'
                  : handPoseJob.status === 'QUEUED' && canResubmitQueuedHandPose
                    ? '排队较久未开始，可重新提交。'
                    : '正在检测，页面会自动更新。'}
            </span>
          ) : (
            <span>还未对该相机视频执行手部检测。</span>
          )}
          <Button
            disabled={
              selectedStream.stream_status !== 'INGESTED' ||
              (handPoseJob !== null && !canResubmitQueuedHandPose)
            }
            loading={requestingHandPose}
            onClick={() => void requestHandPose()}
            type="primary"
          >
            {canResubmitQueuedHandPose
              ? `重新提交相机 ${orderedStreams.indexOf(selectedStream) + 1} 的检测`
              : `检测相机 ${orderedStreams.indexOf(selectedStream) + 1} 的手部姿态`}
          </Button>
          {handPoseMessage ? (
            <span className="inline-action-message">{handPoseMessage}</span>
          ) : null}
        </div>
      ) : null}
      {selectedStream ? (
        <PlaybackCard
          availability={{
            title: '可播放所选相机原视频',
            description:
              candidates.length > 0
                ? '已完成的手部候选会随视频时间同步显示骨架。'
                : '该相机尚无可显示的手部候选，原视频仍可独立播放。',
            type: candidates.length > 0 ? 'success' : 'info',
          }}
          candidateOverlay={candidates}
          canPlay
          canRecoverMissingMediaProbe={false}
          grant={grant}
          isRecoveringMediaProbe={false}
          isRequestingPlayback={requestingPlayback}
          mediaProbeMessage=""
          onClose={() => setPlayback(null)}
          onLoadedMetadata={() => setPlaybackState('ready')}
          onPlaybackError={() => {
            setPlaybackState('error')
            setPlaybackMessage('该相机视频暂时无法播放，请重新获取播放授权。')
          }}
          onRecoverMissingMediaProbe={() => undefined}
          onStartPlayback={() => void startPlayback()}
          playbackMessage={playbackMessage}
          playbackState={playbackState}
        />
      ) : null}
    </section>
  )
}
