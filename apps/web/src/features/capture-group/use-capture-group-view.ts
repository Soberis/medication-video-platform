import { useCallback, useEffect, useRef, useState } from 'react'

import {
  authorizeClipAssetPlayback,
  CatalogApiError,
  getClipAsset,
  getClipAssetMediaReport,
  getLatestHandPoseCandidates,
  type ClipAssetPlaybackGrant,
  type ClipAssetRead,
  type HandPoseCandidateResponse,
  type MediaReport,
} from '@/api/catalog'
import { PlaybackState, type PlaybackAvailability } from '@/components/AssetDetailSections'
import {
  classifyCandidateReadError,
  type CandidateLoadState,
} from '@/features/catalog/hand-pose-candidate-state'
import { mediaStatusLabels, syncSafety } from '@/features/capture-group-presentation'
import {
  candidateDescription,
  candidateTone,
  type GroupViewRow,
} from '@/features/capture-group/capture-group-detail-model'

export function useCaptureGroupView(projectId: string, selectedView: GroupViewRow | null) {
  const selectedStream = selectedView?.stream ?? null
  const selectedClipAssetId = selectedStream?.clip_asset_id ?? null
  const [selectedDetail, setSelectedDetail] = useState<ClipAssetRead | null>(null)
  const [selectedMediaReport, setSelectedMediaReport] = useState<MediaReport | null>(null)
  const [selectedMediaReportState, setSelectedMediaReportState] = useState<
    'loading' | 'success' | 'error'
  >('loading')
  const [selectedMediaReportError, setSelectedMediaReportError] = useState('')
  const [candidateResponse, setCandidateResponse] = useState<HandPoseCandidateResponse | null>(null)
  const [candidateState, setCandidateState] = useState<CandidateLoadState>('idle')
  const [playbackGrant, setPlaybackGrant] = useState<ClipAssetPlaybackGrant | null>(null)
  const [playbackState, setPlaybackState] = useState<PlaybackState>('idle')
  const [playbackMessage, setPlaybackMessage] = useState('')
  const [isRequestingPlayback, setIsRequestingPlayback] = useState(false)
  const selectedViewRequest = useRef<AbortController | null>(null)
  const playbackExpiryTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const clearPlaybackExpiryTimer = useCallback(() => {
    if (playbackExpiryTimer.current !== null) {
      clearTimeout(playbackExpiryTimer.current)
      playbackExpiryTimer.current = null
    }
  }, [])

  const resetPlayback = useCallback(() => {
    clearPlaybackExpiryTimer()
    setPlaybackGrant(null)
    setPlaybackState('idle')
    setPlaybackMessage('')
  }, [clearPlaybackExpiryTimer])

  const loadSelectedView = useCallback(async () => {
    selectedViewRequest.current?.abort()
    const request = new AbortController()
    selectedViewRequest.current = request
    resetPlayback()
    setSelectedDetail(null)
    setSelectedMediaReport(null)
    setSelectedMediaReportState('loading')
    setSelectedMediaReportError('')
    setCandidateResponse(null)
    setCandidateState('idle')
    if (!selectedClipAssetId || !selectedStream) {
      setSelectedMediaReportState('success')
      return
    }
    try {
      const detail = await getClipAsset(projectId, selectedClipAssetId, request.signal)
      if (request.signal.aborted || selectedViewRequest.current !== request) return
      setSelectedDetail(detail)
      if (detail.status !== 'INGESTED') {
        setSelectedMediaReportState('success')
        setCandidateState('unavailable')
        return
      }
      const [candidateResult, mediaReportResult] = await Promise.allSettled([
        getLatestHandPoseCandidates(projectId, selectedClipAssetId, request.signal),
        getClipAssetMediaReport(projectId, selectedClipAssetId, request.signal),
      ])
      if (request.signal.aborted || selectedViewRequest.current !== request) return
      if (candidateResult.status === 'fulfilled') {
        setCandidateResponse(candidateResult.value)
        setCandidateState('success')
      } else {
        setCandidateState(classifyCandidateReadError(candidateResult.reason))
      }
      if (mediaReportResult.status === 'fulfilled') {
        setSelectedMediaReport(mediaReportResult.value)
        setSelectedMediaReportState('success')
      } else {
        setSelectedMediaReportState('error')
        setSelectedMediaReportError(
          mediaReportResult.reason instanceof CatalogApiError
            ? mediaReportResult.reason.message
            : '媒体检测报告暂时无法读取。',
        )
      }
    } catch (error) {
      if (request.signal.aborted || selectedViewRequest.current !== request) return
      setSelectedMediaReportState('error')
      setSelectedMediaReportError(
        error instanceof CatalogApiError ? error.message : '暂时无法读取所选机位的媒体事实。',
      )
    } finally {
      if (selectedViewRequest.current === request) selectedViewRequest.current = null
    }
  }, [projectId, resetPlayback, selectedClipAssetId, selectedStream])

  useEffect(() => {
    const timer = window.setTimeout(() => void loadSelectedView(), 0)
    return () => {
      window.clearTimeout(timer)
      selectedViewRequest.current?.abort()
    }
  }, [loadSelectedView])

  useEffect(() => {
    return () => clearPlaybackExpiryTimer()
  }, [clearPlaybackExpiryTimer])

  const startPlayback = useCallback(async () => {
    if (
      !selectedClipAssetId ||
      selectedDetail?.content_type !== 'video/mp4' ||
      isRequestingPlayback
    ) {
      return
    }
    resetPlayback()
    setIsRequestingPlayback(true)
    try {
      const grant = await authorizeClipAssetPlayback(projectId, selectedClipAssetId)
      setPlaybackGrant(grant)
      setPlaybackState('waiting')
      const remaining = new Date(grant.expires_at).getTime() - Date.now()
      if (!Number.isFinite(remaining) || remaining <= 0) {
        setPlaybackState('expired')
        setPlaybackMessage('本次播放授权已到期，请重新获取后继续查看。')
      } else {
        playbackExpiryTimer.current = setTimeout(() => {
          playbackExpiryTimer.current = null
          setPlaybackState('expired')
          setPlaybackMessage('本次播放授权已到期，请重新获取后继续查看。')
        }, remaining)
      }
    } catch (error) {
      setPlaybackState('error')
      setPlaybackMessage(
        error instanceof CatalogApiError ? error.message : '无法获取播放授权，请稍后重试。',
      )
    } finally {
      setIsRequestingPlayback(false)
    }
  }, [isRequestingPlayback, projectId, resetPlayback, selectedClipAssetId, selectedDetail])

  const playbackAvailability: PlaybackAvailability | null = selectedStream
    ? selectedDetail
      ? selectedDetail.status === 'INGESTED'
        ? {
            title: '媒体已入库，可按需播放',
            description: '播放授权只在点击后获取；播放器仍只加载当前选中的一路机位。',
            type: 'success',
          }
        : selectedDetail.status === 'INGEST_FAILED'
          ? {
              title: '媒体检测失败',
              description:
                '媒体检测失败不会改变接收状态；若播放授权仍可用，可继续人工查看，但不能记录机位质量。',
              type: 'error',
            }
          : {
              title: mediaStatusLabels[selectedDetail.status] ?? '媒体尚未就绪',
              description: '媒体事实尚未完成；当前不允许记录机位质量。',
              type: 'warning',
            }
      : {
          title: '正在读取当前机位',
          description: '读取完成后才会显示播放与媒体事实。',
          type: 'info',
        }
    : {
        title: '选择一路已接收机位',
        description: '缺失机位保留在列表中，但不会加载播放器。',
        type: 'info',
      }

  return {
    selectedStream,
    selectedDetail,
    selectedMediaReport,
    selectedMediaReportState,
    selectedMediaReportError,
    candidateResponse,
    candidateState,
    candidateDescription: candidateDescription(candidateState, candidateResponse),
    candidateTone: candidateTone(candidateState, candidateResponse),
    playbackGrant,
    playbackState,
    playbackMessage,
    playbackAvailability,
    isRequestingPlayback,
    canPlay: selectedDetail?.content_type === 'video/mp4',
    selectedSync: selectedStream ? syncSafety(selectedStream) : null,
    resetPlayback,
    loadSelectedView,
    startPlayback,
    onLoadedMetadata: () => {
      setPlaybackState('ready')
      setPlaybackMessage('')
    },
    onPlaybackError: () => {
      setPlaybackState('error')
      setPlaybackMessage('视频加载失败，请重新获取播放授权后重试。')
    },
  }
}

export type CaptureGroupViewState = ReturnType<typeof useCaptureGroupView>
