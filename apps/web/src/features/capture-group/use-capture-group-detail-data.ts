import { App as AntdApp } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'

import {
  CaptureGroupApiError,
  finalizeCaptureGroupReceipt,
  getCaptureGroup,
  getCaptureGroupQualityReview,
  isCaptureGroupQualityForbidden,
  listCaptureGroupQualityReviewHistory,
  type CaptureGroup,
  type CaptureGroupQualityReview,
} from '@/api/capture-groups'
import {
  getAnnotationLabelSchema,
  listAnnotationTasks,
  type AnnotationLabelSchema,
  type AnnotationTask,
} from '@/api/annotation'
import { CatalogApiError } from '@/api/catalog'
import { getQualityReview, type QualityReview } from '@/api/quality'
import type {
  CaptureGroupQualityVisibility,
  CaptureGroupTaskVisibility,
} from '@/features/capture-group-presentation'

export interface CaptureGroupDetailRefreshOptions {
  focusNextPendingView?: boolean
}

export function useCaptureGroupDetailData(projectId: string, captureGroupId: string) {
  const { message } = AntdApp.useApp()
  const [group, setGroup] = useState<CaptureGroup | null>(null)
  const [groupQuality, setGroupQuality] = useState<CaptureGroupQualityReview | null>(null)
  const [groupQualityVisibility, setGroupQualityVisibility] =
    useState<CaptureGroupQualityVisibility>('loading')
  const [qualityHistory, setQualityHistory] = useState<CaptureGroupQualityReview[]>([])
  const [task, setTask] = useState<AnnotationTask | null>(null)
  const [taskVisibility, setTaskVisibility] = useState<CaptureGroupTaskVisibility>('visible')
  const [schema, setSchema] = useState<AnnotationLabelSchema | null>(null)
  const [viewReviews, setViewReviews] = useState<Record<string, QualityReview>>({})
  const [viewReviewErrors, setViewReviewErrors] = useState<Record<string, string>>({})
  const [groupLoadState, setGroupLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [groupErrorCode, setGroupErrorCode] = useState('')
  const [groupErrorMessage, setGroupErrorMessage] = useState('')
  const [qualityErrorMessage, setQualityErrorMessage] = useState('')
  const [taskErrorMessage, setTaskErrorMessage] = useState('')
  const [viewQualityLoading, setViewQualityLoading] = useState(true)
  const [isFinalizingReceipt, setIsFinalizingReceipt] = useState(false)
  const [selectedClipAssetId, setSelectedClipAssetId] = useState<string | null>(null)
  const groupRequest = useRef<AbortController | null>(null)
  const humanRequest = useRef<AbortController | null>(null)
  const requestGeneration = useRef(0)
  const loadedGroupIdentity = useRef<{ projectId: string; captureGroupId: string } | null>(null)
  const selectedClipAssetIdRef = useRef(selectedClipAssetId)

  useEffect(() => {
    selectedClipAssetIdRef.current = selectedClipAssetId
  }, [selectedClipAssetId])

  const loadHumanContext = useCallback(
    async (
      generation: number,
      nextGroup: CaptureGroup,
      focusNextPendingView: boolean,
      selectedClipAssetIdAtGroupLoad: string | null,
    ) => {
      const request = new AbortController()
      humanRequest.current = request
      const isCurrent = () =>
        !request.signal.aborted &&
        humanRequest.current === request &&
        requestGeneration.current === generation

      try {
        let taskList: Awaited<ReturnType<typeof listAnnotationTasks>> | null = null
        try {
          taskList = await listAnnotationTasks(projectId, request.signal)
        } catch {
          if (!isCurrent()) return
          setTask(null)
          setTaskVisibility('unavailable')
          setTaskErrorMessage('暂时无法读取配药动作标注任务；请刷新后重试。')
        }
        if (!isCurrent()) return

        const nextTask =
          taskList?.items.find((candidate) => candidate.capture_group_id === captureGroupId) ?? null
        const nextTaskVisibility: CaptureGroupTaskVisibility = !taskList
          ? 'unavailable'
          : nextTask || taskList.progress.scope === 'PROJECT'
            ? 'visible'
            : 'permission-limited'
        setTask(nextTask)
        setTaskVisibility(nextTaskVisibility)

        // Load quality facts for both paths: quality is the explicit opt-in gate
        // for human labeling, so it must be visible before a task exists.
        const [qualityResult, historyResult, schemaResult] = await Promise.allSettled([
          getCaptureGroupQualityReview(projectId, captureGroupId, request.signal),
          listCaptureGroupQualityReviewHistory(projectId, captureGroupId, request.signal),
          getAnnotationLabelSchema(projectId, request.signal),
        ])
        if (!isCurrent()) return

        const nextQuality = qualityResult.status === 'fulfilled' ? qualityResult.value : null
        const nextQualityVisibility: CaptureGroupQualityVisibility =
          qualityResult.status === 'fulfilled'
            ? 'loaded'
            : isCaptureGroupQualityForbidden(qualityResult.reason)
              ? 'forbidden'
              : 'unavailable'
        setGroupQuality(nextQuality)
        setGroupQualityVisibility(nextQualityVisibility)
        setQualityHistory(historyResult.status === 'fulfilled' ? historyResult.value.items : [])
        setSchema(schemaResult.status === 'fulfilled' ? schemaResult.value : null)
        if (qualityResult.status === 'rejected') {
          setQualityErrorMessage(
            nextQualityVisibility === 'forbidden'
              ? '当前账号无权读取人工标签的质量事实；详情仍保留采集记录与机位事实。'
              : '暂时无法读取人工标签的质量事实；采集记录与机器证据仍可查看。',
          )
        }
        if (nextTask && schemaResult.status === 'rejected') {
          setTaskErrorMessage('标签规范暂时无法读取，当前不能提交新的修订。')
        }

        const reviewResults = await Promise.allSettled(
          nextGroup.streams.map(async (stream) => {
            return getQualityReview(projectId, stream.clip_asset_id, request.signal)
          }),
        )
        if (!isCurrent()) return
        const nextReviews: Record<string, QualityReview> = {}
        const nextReviewErrors: Record<string, string> = {}
        reviewResults.forEach((result, index) => {
          const stream = nextGroup.streams[index]
          if (!stream) return
          if (result.status === 'fulfilled') {
            nextReviews[stream.clip_asset_id] = result.value
          } else {
            nextReviewErrors[stream.clip_asset_id] =
              result.reason instanceof Error ? result.reason.message : '当前机位质量暂时无法读取。'
          }
        })
        setViewReviews(nextReviews)
        setViewReviewErrors(nextReviewErrors)
        setViewQualityLoading(false)
        setSelectedClipAssetId((current) => {
          const presentIds = new Set(nextGroup.streams.map((stream) => stream.clip_asset_id))
          const pendingStream = nextGroup.streams.find((stream) => {
            const review = nextReviews[stream.clip_asset_id]
            return (
              stream.stream_status === 'INGESTED' &&
              !nextReviewErrors[stream.clip_asset_id] &&
              (!review || (review.decision !== 'QUALIFIED' && review.decision !== 'REJECTED'))
            )
          })
          if (focusNextPendingView && current === selectedClipAssetIdAtGroupLoad && pendingStream) {
            return pendingStream.clip_asset_id
          }
          if (current && presentIds.has(current)) return current
          if (pendingStream) return pendingStream.clip_asset_id
          if (nextTask?.primary_clip_asset_id && presentIds.has(nextTask.primary_clip_asset_id)) {
            return nextTask.primary_clip_asset_id
          }
          return nextGroup.streams.find((stream) => stream.clip_asset_id)?.clip_asset_id ?? null
        })
      } finally {
        if (humanRequest.current === request) humanRequest.current = null
      }
    },
    [captureGroupId, projectId],
  )

  const refresh = useCallback(
    async ({ focusNextPendingView = false }: CaptureGroupDetailRefreshOptions = {}) => {
      groupRequest.current?.abort()
      groupRequest.current = null
      humanRequest.current?.abort()
      humanRequest.current = null
      const generation = requestGeneration.current + 1
      requestGeneration.current = generation
      const request = new AbortController()
      groupRequest.current = request

      const loadedIdentity = loadedGroupIdentity.current
      if (
        loadedIdentity &&
        (loadedIdentity.projectId !== projectId || loadedIdentity.captureGroupId !== captureGroupId)
      ) {
        loadedGroupIdentity.current = null
        setGroup(null)
        setSelectedClipAssetId(null)
      }

      setGroupLoadState('loading')
      setGroupErrorCode('')
      setGroupErrorMessage('')
      setQualityErrorMessage('')
      setTaskErrorMessage('')
      setTask(null)
      setTaskVisibility('visible')
      setGroupQuality(null)
      setGroupQualityVisibility('loading')
      setQualityHistory([])
      setSchema(null)
      setViewReviews({})
      setViewReviewErrors({})
      setViewQualityLoading(true)

      try {
        const nextGroup = await getCaptureGroup(projectId, captureGroupId, request.signal)
        if (
          request.signal.aborted ||
          groupRequest.current !== request ||
          requestGeneration.current !== generation
        )
          return false
        loadedGroupIdentity.current = { projectId, captureGroupId }
        setGroup(nextGroup)
        const selectedClipAssetIdAtGroupLoad = selectedClipAssetIdRef.current
        setSelectedClipAssetId((current) => {
          const presentIds = new Set(nextGroup.streams.map((stream) => stream.clip_asset_id))
          if (current && presentIds.has(current)) return current
          return nextGroup.streams.find((stream) => stream.clip_asset_id)?.clip_asset_id ?? null
        })
        setGroupLoadState('success')
        void loadHumanContext(
          generation,
          nextGroup,
          focusNextPendingView,
          selectedClipAssetIdAtGroupLoad,
        )
        return true
      } catch (error) {
        if (
          request.signal.aborted ||
          groupRequest.current !== request ||
          requestGeneration.current !== generation
        )
          return false
        setGroupLoadState('error')
        setGroupErrorCode(
          error instanceof CaptureGroupApiError || error instanceof CatalogApiError
            ? error.errorCode
            : 'CAPTURE_GROUP_NOT_FOUND',
        )
        setGroupErrorMessage(
          error instanceof CaptureGroupApiError || error instanceof CatalogApiError
            ? error.message
            : error instanceof Error
              ? error.message
              : '暂时无法读取采集记录详情，请稍后重试。',
        )
        return false
      } finally {
        if (groupRequest.current === request) groupRequest.current = null
      }
    },
    [captureGroupId, loadHumanContext, projectId],
  )

  useEffect(() => {
    const timer = window.setTimeout(() => void refresh(), 0)
    return () => {
      window.clearTimeout(timer)
      requestGeneration.current += 1
      groupRequest.current?.abort()
      groupRequest.current = null
      humanRequest.current?.abort()
      humanRequest.current = null
    }
  }, [refresh])

  const finalizeReceipt = useCallback(async () => {
    if (!group || group.receipt_state !== 'RECEIVING' || isFinalizingReceipt) return false
    setIsFinalizingReceipt(true)
    try {
      await finalizeCaptureGroupReceipt(projectId, captureGroupId)
      const refreshed = await refresh()
      void (refreshed
        ? message.success('采集记录接收已结束，服务端事实已刷新。')
        : message.warning('接收已结束，但最新事实暂未刷新；请手动刷新确认。'))
      return true
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '结束接收未完成，请稍后重试。')
      return false
    } finally {
      setIsFinalizingReceipt(false)
    }
  }, [captureGroupId, group, isFinalizingReceipt, message, projectId, refresh])

  const updateViewReview = useCallback((review: QualityReview) => {
    setViewReviews((current) => ({ ...current, [review.clip_asset_id]: review }))
    setViewReviewErrors((current) => {
      if (!current[review.clip_asset_id]) return current
      const next = { ...current }
      delete next[review.clip_asset_id]
      return next
    })
  }, [])

  return {
    group,
    groupQuality,
    groupQualityVisibility,
    qualityHistory,
    task,
    taskVisibility,
    schema,
    viewReviews,
    viewReviewErrors,
    groupLoadState,
    groupErrorCode,
    groupErrorMessage,
    qualityErrorMessage,
    taskErrorMessage,
    viewQualityLoading,
    selectedClipAssetId,
    isFinalizingReceipt,
    refresh,
    finalizeReceipt,
    setTask,
    setSelectedClipAssetId,
    updateViewReview,
  }
}
