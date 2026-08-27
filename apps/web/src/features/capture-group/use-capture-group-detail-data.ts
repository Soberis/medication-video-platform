import { App as AntdApp } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'

import {
  CaptureGroupApiError,
  finalizeCaptureGroupReceipt,
  getCaptureGroup,
  getCaptureGroupQualityReview,
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
import type { CaptureGroupTaskVisibility } from '@/features/capture-group-presentation'

export function useCaptureGroupDetailData(projectId: string, captureGroupId: string) {
  const { message } = AntdApp.useApp()
  const [group, setGroup] = useState<CaptureGroup | null>(null)
  const [groupQuality, setGroupQuality] = useState<CaptureGroupQualityReview | null>(null)
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
  const [refreshVersion, setRefreshVersion] = useState(0)
  const [isFinalizingReceipt, setIsFinalizingReceipt] = useState(false)
  const [selectedClipAssetId, setSelectedClipAssetId] = useState<string | null>(null)
  const groupRequest = useRef<AbortController | null>(null)

  const refresh = useCallback(async () => {
    groupRequest.current?.abort()
    const request = new AbortController()
    groupRequest.current = request
    setGroupLoadState('loading')
    setGroupErrorCode('')
    setGroupErrorMessage('')
    setQualityErrorMessage('')
    setTaskErrorMessage('')
    setViewQualityLoading(true)
    try {
      const nextGroup = await getCaptureGroup(projectId, captureGroupId, request.signal)
      if (request.signal.aborted || groupRequest.current !== request) return
      const [qualityResult, historyResult, taskResult, schemaResult] = await Promise.allSettled([
        getCaptureGroupQualityReview(projectId, captureGroupId, request.signal),
        listCaptureGroupQualityReviewHistory(projectId, captureGroupId, request.signal),
        listAnnotationTasks(projectId, request.signal),
        getAnnotationLabelSchema(projectId, request.signal),
      ])
      if (request.signal.aborted || groupRequest.current !== request) return

      const nextQuality = qualityResult.status === 'fulfilled' ? qualityResult.value : null
      const nextHistory = historyResult.status === 'fulfilled' ? historyResult.value.items : []
      const taskList = taskResult.status === 'fulfilled' ? taskResult.value.items : []
      const nextTask =
        taskList.find((candidate) => candidate.capture_group_id === captureGroupId) ?? null
      const nextTaskVisibility: CaptureGroupTaskVisibility =
        taskResult.status === 'rejected'
          ? 'unavailable'
          : nextTask || taskResult.value.progress.scope === 'PROJECT'
            ? 'visible'
            : 'permission-limited'
      const nextSchema = schemaResult.status === 'fulfilled' ? schemaResult.value : null
      setGroup(nextGroup)
      setGroupQuality(nextQuality)
      setQualityHistory(nextHistory)
      setTask(nextTask)
      setTaskVisibility(nextTaskVisibility)
      setSchema(nextSchema)
      if (qualityResult.status === 'rejected') {
        setQualityErrorMessage(
          '暂时无法读取当前 Group Quality；详情仍保留 CaptureGroup 与视图事实。',
        )
      }
      if (taskResult.status === 'rejected') {
        setTaskErrorMessage('暂时无法读取唯一 CaptureGroup 任务；请刷新后重试。')
      }
      if (nextTask && schemaResult.status === 'rejected') {
        setTaskErrorMessage('标签规范暂时无法读取，当前不能提交新的修订。')
      }

      const reviewEntries = await Promise.all(
        nextGroup.streams.map(async (stream) => {
          try {
            return [
              stream.clip_asset_id,
              await getQualityReview(projectId, stream.clip_asset_id, request.signal),
            ] as const
          } catch (error) {
            return [
              stream.clip_asset_id,
              error instanceof Error ? error : new Error('VIEW_QUALITY_REQUEST_FAILED'),
            ] as const
          }
        }),
      )
      if (request.signal.aborted || groupRequest.current !== request) return
      const nextReviews: Record<string, QualityReview> = {}
      const nextErrors: Record<string, string> = {}
      for (const [clipAssetId, result] of reviewEntries) {
        if ('decision' in result) {
          nextReviews[clipAssetId] = result
        } else {
          nextErrors[clipAssetId] = result.message
        }
      }
      setViewReviews(nextReviews)
      setViewReviewErrors(nextErrors)
      setViewQualityLoading(false)
      setSelectedClipAssetId((current) => {
        const presentIds = new Set(nextGroup.streams.map((stream) => stream.clip_asset_id))
        if (current && presentIds.has(current)) return current
        if (nextTask?.primary_clip_asset_id && presentIds.has(nextTask.primary_clip_asset_id)) {
          return nextTask.primary_clip_asset_id
        }
        return nextGroup.streams[0]?.clip_asset_id ?? null
      })
      setRefreshVersion((current) => current + 1)
      setGroupLoadState('success')
    } catch (error) {
      if (request.signal.aborted || groupRequest.current !== request) return
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
            : '暂时无法读取 CaptureGroup 详情，请稍后重试。',
      )
    } finally {
      if (groupRequest.current === request) groupRequest.current = null
    }
  }, [captureGroupId, projectId])

  useEffect(() => {
    const timer = window.setTimeout(() => void refresh(), 0)
    return () => {
      window.clearTimeout(timer)
      groupRequest.current?.abort()
    }
  }, [refresh])

  const finalizeReceipt = useCallback(async () => {
    if (!group || group.receipt_state !== 'RECEIVING' || isFinalizingReceipt) return
    setIsFinalizingReceipt(true)
    try {
      await finalizeCaptureGroupReceipt(projectId, captureGroupId)
      void message.success('CaptureGroup 回执已关闭，正在刷新服务端事实。')
      await refresh()
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '回执关闭未完成，请稍后重试。')
    } finally {
      setIsFinalizingReceipt(false)
    }
  }, [captureGroupId, group, isFinalizingReceipt, message, projectId, refresh])

  const updateViewReview = useCallback((review: QualityReview) => {
    setViewReviews((current) => ({ ...current, [review.clip_asset_id]: review }))
  }, [])

  return {
    group,
    groupQuality,
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
    refreshVersion,
    selectedClipAssetId,
    isFinalizingReceipt,
    refresh,
    finalizeReceipt,
    setTask,
    setSelectedClipAssetId,
    updateViewReview,
  }
}
