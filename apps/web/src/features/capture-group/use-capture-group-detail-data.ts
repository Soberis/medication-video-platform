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
  const [groupLoadState, setGroupLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [groupErrorCode, setGroupErrorCode] = useState('')
  const [groupErrorMessage, setGroupErrorMessage] = useState('')
  const [qualityErrorMessage, setQualityErrorMessage] = useState('')
  const [taskErrorMessage, setTaskErrorMessage] = useState('')
  const [refreshVersion, setRefreshVersion] = useState(0)
  const [isFinalizingReceipt, setIsFinalizingReceipt] = useState(false)
  const [selectedClipAssetId, setSelectedClipAssetId] = useState<string | null>(null)
  const groupRequest = useRef<AbortController | null>(null)

  const refresh = useCallback(
    async ({ focusNextPendingView = false }: CaptureGroupDetailRefreshOptions = {}) => {
      groupRequest.current?.abort()
      const request = new AbortController()
      groupRequest.current = request
      setGroupLoadState('loading')
      setGroupErrorCode('')
      setGroupErrorMessage('')
      setQualityErrorMessage('')
      setTaskErrorMessage('')
      setGroupQuality(null)
      setGroupQualityVisibility('loading')
      setQualityHistory([])
      setSchema(null)
      setViewReviews({})

      try {
        const nextGroup = await getCaptureGroup(projectId, captureGroupId, request.signal)
        if (request.signal.aborted || groupRequest.current !== request) return false
        setGroup(nextGroup)

        // Task lookup is the only optional human-label lookup on the default
        // data-group surface. Machine evidence never waits for quality/task APIs.
        const taskResult = await listAnnotationTasks(projectId, request.signal)
          .then((value) => ({ status: 'fulfilled' as const, value }))
          .catch((reason) => ({ status: 'rejected' as const, reason }))
        if (request.signal.aborted || groupRequest.current !== request) return false
        const taskList = taskResult.status === 'fulfilled' ? taskResult.value.items : []
        const nextTask =
          taskList.find((candidate) => candidate.capture_group_id === captureGroupId) ?? null
        const nextTaskVisibility: CaptureGroupTaskVisibility =
          taskResult.status === 'rejected'
            ? 'unavailable'
            : nextTask || taskResult.value.progress.scope === 'PROJECT'
              ? 'visible'
              : 'permission-limited'
        setTask(nextTask)
        setTaskVisibility(nextTaskVisibility)

        if (!nextTask) {
          setGroupQualityVisibility('loaded')
          setSelectedClipAssetId((current) => {
            const presentIds = new Set(nextGroup.streams.map((stream) => stream.clip_asset_id))
            return current && presentIds.has(current)
              ? current
              : (nextGroup.streams[0]?.clip_asset_id ?? null)
          })
          setRefreshVersion((current) => current + 1)
          setGroupLoadState('success')
          return true
        }

        // Existing task: load the human-label facts needed by its deep link.
        const [qualityResult, historyResult, schemaResult] = await Promise.allSettled([
          getCaptureGroupQualityReview(projectId, captureGroupId, request.signal),
          listCaptureGroupQualityReviewHistory(projectId, captureGroupId, request.signal),
          getAnnotationLabelSchema(projectId, request.signal),
        ])
        if (request.signal.aborted || groupRequest.current !== request) return false

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
              ? '当前账号无权读取人工标签的质量事实；详情仍保留数据组与机位事实。'
              : '暂时无法读取人工标签的质量事实；数据组与机器证据仍可查看。',
          )
        }
        if (schemaResult.status === 'rejected') {
          setTaskErrorMessage('标签规范暂时无法读取，当前不能提交新的修订。')
        }

        const reviewResults = await Promise.allSettled(
          nextGroup.streams.map(async (stream) => {
            return getQualityReview(projectId, stream.clip_asset_id, request.signal)
          }),
        )
        if (request.signal.aborted || groupRequest.current !== request) return false
        const nextReviews: Record<string, QualityReview> = {}
        reviewResults.forEach((result, index) => {
          const stream = nextGroup.streams[index]
          if (stream && result.status === 'fulfilled')
            nextReviews[stream.clip_asset_id] = result.value
        })
        setViewReviews(nextReviews)
        setSelectedClipAssetId((current) => {
          const presentIds = new Set(nextGroup.streams.map((stream) => stream.clip_asset_id))
          const pendingStream = nextGroup.streams.find((stream) => {
            const review = nextReviews[stream.clip_asset_id]
            return (
              stream.stream_status === 'INGESTED' &&
              (!review || (review.decision !== 'QUALIFIED' && review.decision !== 'REJECTED'))
            )
          })
          if (focusNextPendingView && pendingStream) return pendingStream.clip_asset_id
          if (current && presentIds.has(current)) return current
          if (pendingStream) return pendingStream.clip_asset_id
          if (nextTask.primary_clip_asset_id && presentIds.has(nextTask.primary_clip_asset_id)) {
            return nextTask.primary_clip_asset_id
          }
          return nextGroup.streams[0]?.clip_asset_id ?? null
        })
        setRefreshVersion((current) => current + 1)
        setGroupLoadState('success')
        return true
      } catch (error) {
        if (request.signal.aborted || groupRequest.current !== request) return false
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
              : '暂时无法读取数据组详情，请稍后重试。',
        )
        return false
      } finally {
        if (groupRequest.current === request) groupRequest.current = null
      }
    },
    [captureGroupId, projectId],
  )

  useEffect(() => {
    const timer = window.setTimeout(() => void refresh(), 0)
    return () => {
      window.clearTimeout(timer)
      groupRequest.current?.abort()
    }
  }, [refresh])

  const finalizeReceipt = useCallback(async () => {
    if (!group || group.receipt_state !== 'RECEIVING' || isFinalizingReceipt) return false
    setIsFinalizingReceipt(true)
    try {
      await finalizeCaptureGroupReceipt(projectId, captureGroupId)
      const refreshed = await refresh()
      void (refreshed
        ? message.success('数据组接收已结束，服务端事实已刷新。')
        : message.warning('接收已结束，但最新事实暂未刷新；请手动刷新确认。'))
      return true
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '结束接收未完成，请稍后重试。')
      return false
    } finally {
      setIsFinalizingReceipt(false)
    }
  }, [captureGroupId, group, isFinalizingReceipt, message, projectId, refresh])

  return {
    group,
    groupQuality,
    groupQualityVisibility,
    qualityHistory,
    task,
    taskVisibility,
    schema,
    viewReviews,
    groupLoadState,
    groupErrorCode,
    groupErrorMessage,
    qualityErrorMessage,
    taskErrorMessage,
    refreshVersion,
    selectedClipAssetId,
    isFinalizingReceipt,
    refresh,
    finalizeReceipt,
    setTask,
    setSelectedClipAssetId,
  }
}
