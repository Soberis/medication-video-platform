import { App as AntdApp } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'

import {
  CaptureGroupApiError,
  finalizeCaptureGroupReceipt,
  getCaptureGroup,
  type CaptureGroup,
} from '@/api/capture-groups'
import {
  getAnnotationLabelSchema,
  listAnnotationTasks,
  type AnnotationLabelSchema,
  type AnnotationTask,
} from '@/api/annotation'
import { CatalogApiError } from '@/api/catalog'
import type { CaptureGroupTaskVisibility } from '@/features/capture-group-presentation'

export interface CaptureGroupDetailRefreshOptions {
  focusNextPendingView?: boolean
}

export const RECEIPT_POLL_INTERVAL_MS = 5_000

export function useCaptureGroupDetailData(projectId: string, captureGroupId: string) {
  const { message } = AntdApp.useApp()
  const [group, setGroup] = useState<CaptureGroup | null>(null)
  const [task, setTask] = useState<AnnotationTask | null>(null)
  const [taskVisibility, setTaskVisibility] = useState<CaptureGroupTaskVisibility>('visible')
  const [schema, setSchema] = useState<AnnotationLabelSchema | null>(null)
  const [groupLoadState, setGroupLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [groupErrorCode, setGroupErrorCode] = useState('')
  const [groupErrorMessage, setGroupErrorMessage] = useState('')
  const [taskErrorMessage, setTaskErrorMessage] = useState('')
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

        const [schemaResult] = await Promise.allSettled([
          getAnnotationLabelSchema(projectId, request.signal),
        ])
        if (!isCurrent()) return

        setSchema(schemaResult.status === 'fulfilled' ? schemaResult.value : null)
        if (nextTask && schemaResult.status === 'rejected') {
          setTaskErrorMessage('标签规范暂时无法读取，当前不能提交新的修订。')
        }

        // 机位处置进度是唯一的机位状态事实：默认选中第一路仍待处理的已入库机位。
        setSelectedClipAssetId((current) => {
          const presentIds = new Set(nextGroup.streams.map((stream) => stream.clip_asset_id))
          const ingestableStreams = nextGroup.streams.filter(
            (stream) => stream.stream_status === 'INGESTED',
          )
          const pendingStream = ingestableStreams.find((stream) => {
            const view = nextTask?.camera_views?.find(
              (candidate) => candidate.clip_asset_id === stream.clip_asset_id,
            )
            return !view || view.state === 'PENDING' || view.state === 'DRAFT'
          })
          if (focusNextPendingView && current === selectedClipAssetIdAtGroupLoad && pendingStream) {
            return pendingStream.clip_asset_id
          }
          if (current && presentIds.has(current)) return current
          if (pendingStream) return pendingStream.clip_asset_id
          return ingestableStreams[0]?.clip_asset_id ?? nextGroup.streams[0]?.clip_asset_id ?? null
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
      setTaskErrorMessage('')
      setTask(null)
      setTaskVisibility('visible')
      setSchema(null)

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

  useEffect(() => {
    if (group?.receipt_state !== 'RECEIVING') return undefined

    let isDisposed = false
    let timer: number | null = null

    const scheduleNext = () => {
      if (isDisposed) return
      timer = window.setTimeout(async () => {
        timer = null
        if (isDisposed) return
        if (!document.hidden) {
          await refresh()
        }
        if (!isDisposed && group?.receipt_state === 'RECEIVING') {
          scheduleNext()
        }
      }, RECEIPT_POLL_INTERVAL_MS)
    }

    scheduleNext()

    const handleVisibilityChange = () => {
      if (!document.hidden && group?.receipt_state === 'RECEIVING') {
        void refresh()
      }
    }
    document.addEventListener('visibilitychange', handleVisibilityChange)

    return () => {
      isDisposed = true
      if (timer !== null) {
        window.clearTimeout(timer)
        timer = null
      }
      document.removeEventListener('visibilitychange', handleVisibilityChange)
    }
  }, [group?.receipt_state, refresh])

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

  return {
    group,
    task,
    taskVisibility,
    schema,
    groupLoadState,
    groupErrorCode,
    groupErrorMessage,
    taskErrorMessage,
    selectedClipAssetId,
    isFinalizingReceipt,
    refresh,
    finalizeReceipt,
    setTask,
    setSelectedClipAssetId,
  }
}
