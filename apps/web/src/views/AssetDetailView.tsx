import { Alert, App as AntdApp, Button } from 'antd'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router'

import { listAnnotationTasks, type AnnotationTask } from '@/api/annotation'
import {
  authorizeClipAssetPlayback,
  CatalogApiError,
  deleteClipAsset,
  getClipAsset,
  getClipAssetMediaReport,
  getLatestHandPoseCandidates,
  recoverCurrentClipAssetMediaProbeDelivery,
  requestHandPoseCandidates,
  retryHandPoseCandidates,
  type ClipAssetPlaybackGrant,
  type ClipAssetRead,
  type MediaReport,
  type HandPoseJob,
  type HandPoseCandidateResponse,
} from '@/api/catalog'
import { getProject, type ProjectRole } from '@/api/projects'
import {
  getQualityReview,
  QualityReviewApiError,
  recordQualityReview,
  qualityDecisionLabels,
  type QualityReview,
  type QualityReviewCommandDecision,
  type QualityReviewReasonCode,
} from '@/api/quality'
import { paths } from '@/app/paths'
import {
  assetStatusLabels,
  formatDateTime,
  shortId,
  statusTone,
} from '@/features/catalog/catalog-presentation'
import { AssetActivityCard } from '@/components/AssetActivityCard'
import {
  AnnotationTaskCard,
  AssetFacts,
  DangerZoneCard,
  MediaReportCard,
  PlaybackCard,
  type PlaybackState,
  QualityReviewCard,
} from '@/components/AssetDetailSections'
import { usePromptDialog } from '@/components/PromptDialog'
import { StatusBadge, type StatusBadgeTone } from '@/components/StatusBadge'
import { ErrorState, LoadingState } from '@/components/WorkspaceFeedback'

type WorkflowStepState = 'complete' | 'current' | 'waiting' | 'blocked'

interface WorkflowStep {
  label: string
  state: WorkflowStepState
  status: string
  detail: string
}

interface DetailNextStep {
  title: string
  description: string
  buttonLabel: string
  kind: 'hand-pose' | 'media-recovery' | 'scroll' | 'navigate' | 'none'
  interactive: boolean
}

function workflowStepTone(state: WorkflowStepState): StatusBadgeTone {
  if (state === 'complete') return 'success'
  if (state === 'blocked') return 'error'
  return state === 'current' ? 'warning' : 'default'
}

function handPoseStatusTone(tone: 'idle' | 'failed' | 'succeeded' | 'running'): StatusBadgeTone {
  if (tone === 'failed') return 'error'
  if (tone === 'succeeded') return 'success'
  return tone === 'running' ? 'processing' : 'default'
}

function conciseAssetIdentifier(value: string): string {
  return value.length > 24 ? shortId(value) : value
}

export default function AssetDetailView() {
  const { projectId = '', id: assetId = '' } = useParams()
  const location = useLocation()
  const navigate = useNavigate()
  const { message } = AntdApp.useApp()
  const prompt = usePromptDialog()
  const [detail, setDetail] = useState<ClipAssetRead | null>(null)
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [errorCode, setErrorCode] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const [actionErrorCode, setActionErrorCode] = useState('')
  const [actionErrorMessage, setActionErrorMessage] = useState('')
  const [projectRole, setProjectRole] = useState<ProjectRole | null>(null)
  const [isDeleting, setIsDeleting] = useState(false)
  const [playbackGrant, setPlaybackGrant] = useState<ClipAssetPlaybackGrant | null>(null)
  const [playbackState, setPlaybackState] = useState<PlaybackState>('idle')
  const [playbackMessage, setPlaybackMessage] = useState('')
  const [isRequestingPlayback, setIsRequestingPlayback] = useState(false)
  const [isRecoveringMediaProbe, setIsRecoveringMediaProbe] = useState(false)
  const [mediaProbeMessage, setMediaProbeMessage] = useState('')
  const [mediaReport, setMediaReport] = useState<MediaReport | null>(null)
  const [mediaReportLoadState, setMediaReportLoadState] = useState<
    'idle' | 'loading' | 'success' | 'error'
  >('idle')
  const [mediaReportErrorCode, setMediaReportErrorCode] = useState('')
  const [mediaReportErrorMessage, setMediaReportErrorMessage] = useState('')
  const [qualityReview, setQualityReview] = useState<QualityReview | null>(null)
  const [qualityLoadState, setQualityLoadState] = useState<'loading' | 'success' | 'error'>(
    'loading',
  )
  const [qualityErrorCode, setQualityErrorCode] = useState('')
  const [qualityErrorMessage, setQualityErrorMessage] = useState('')
  const [selectedQualityDecision, setSelectedQualityDecision] =
    useState<QualityReviewCommandDecision>('QUALIFIED')
  const [selectedQualityReason, setSelectedQualityReason] =
    useState<QualityReviewReasonCode>('MEETS_REQUIREMENTS')
  const [isSavingQuality, setIsSavingQuality] = useState(false)
  const [annotationTask, setAnnotationTask] = useState<AnnotationTask | null>(null)
  const [annotationTaskLoadState, setAnnotationTaskLoadState] = useState<
    'idle' | 'loading' | 'success' | 'error'
  >('idle')
  const [handPoseJob, setHandPoseJob] = useState<HandPoseJob | null>(null)
  const [handPoseCandidates, setHandPoseCandidates] = useState<HandPoseCandidateResponse | null>(
    null,
  )
  const [isRequestingHandPose, setIsRequestingHandPose] = useState(false)
  const detailRef = useRef<ClipAssetRead | null>(null)
  const handPoseJobRef = useRef<HandPoseJob | null>(null)
  const detailRequest = useRef<AbortController | null>(null)
  const roleRequest = useRef<AbortController | null>(null)
  const qualityRequest = useRef<AbortController | null>(null)
  const mediaReportRequest = useRef<AbortController | null>(null)
  const annotationTaskRequest = useRef<AbortController | null>(null)
  const handPoseRequest = useRef<AbortController | null>(null)
  const transientStateRequest = useRef<AbortController | null>(null)
  const playbackExpiryTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const mediaProbeRecoveryIntent = useRef<{
    changeReference: string
    idempotencyKey: string
  } | null>(null)

  const canDelete = projectRole === 'ADMIN'
  const canPlay = projectRole !== null && detail?.content_type === 'video/mp4'
  const canRecoverMissingMediaProbe = projectRole === 'ADMIN' && detail?.status === 'UPLOADED'
  const canReviewQuality = projectRole !== null && detail?.status === 'INGESTED'
  const hasCurrentQualifiedQuality =
    qualityLoadState === 'success' && qualityReview?.decision === 'QUALIFIED'
  const canShowAnnotationTaskCard =
    detail?.status === 'INGESTED' &&
    hasCurrentQualifiedQuality &&
    annotationTaskLoadState === 'success'
  const canRequestHandPose =
    projectRole !== null &&
    detail?.status === 'INGESTED' &&
    detail.session_binding?.enabled_for_hand_detection !== false &&
    handPoseJob?.status === 'FAILED' &&
    handPoseJob.run_summary?.retryable === true

  useEffect(() => {
    detailRef.current = detail
  }, [detail])

  useEffect(() => {
    handPoseJobRef.current = handPoseJob
  }, [handPoseJob])
  const workflowSteps = useMemo<WorkflowStep[]>(() => {
    if (!detail) return []

    const mediaStep: WorkflowStep =
      detail.status === 'INGESTED'
        ? {
            label: '媒体',
            state: 'complete',
            status: '已完成',
            detail: '视频可播放并可进入后续人工流程',
          }
        : detail.status === 'INGEST_FAILED'
          ? {
              label: '媒体',
              state: 'blocked',
              status: '检测失败',
              detail: '需先处理媒体检测问题',
            }
          : {
              label: '媒体',
              state: 'current',
              status: detail.status === 'INGESTING' ? '检测中' : '待检测',
              detail: '完成后才能记录质量结论',
            }

    const annotationStep: WorkflowStep =
      qualityReview?.decision === 'REJECTED'
        ? {
            label: '人工标注',
            state: 'blocked',
            status: '不进入',
            detail: '当前质量结论不允许进入标注',
          }
        : qualityReview?.decision !== 'QUALIFIED'
          ? {
              label: '人工标注',
              state: 'current',
              status: '待质量判断',
              detail: '先完成人工质量判断',
            }
          : annotationTaskLoadState === 'loading' || annotationTaskLoadState === 'idle'
            ? {
                label: '人工标注',
                state: 'waiting',
                status: '读取中',
                detail: '正在读取服务端标注任务状态',
              }
            : annotationTaskLoadState === 'error'
              ? {
                  label: '人工标注',
                  state: 'waiting',
                  status: '状态未读取',
                  detail: '请刷新后确认，不使用前端推断结果',
                }
              : annotationTask === null
                ? {
                    label: '人工标注',
                    state: 'waiting',
                    status: '已自动分配',
                    detail:
                      '质量合格后系统自动分配给质量确认人；请在任务列表查看当前账号可见任务。',
                  }
                : annotationTask.annotation_revision?.review?.decision === 'REJECTED'
                  ? {
                      label: '人工标注',
                      state: 'current',
                      status: '待修改',
                      detail: '人工复核未通过，等待执行人修订',
                    }
                  : annotationTask.status === 'ASSIGNED'
                    ? {
                        label: '人工标注',
                        state: 'current',
                        status: '待提交',
                        detail: '等待执行人提交配药动作时间段和动作标签',
                      }
                    : {
                        label: '人工标注',
                        state: 'complete',
                        status: annotationTask.status === 'SUBMITTED' ? '已提交' : '已完成',
                        detail:
                          annotationTask.status === 'SUBMITTED'
                            ? '正式动作标签已提交，等待人工复核'
                            : '正式动作标签已经人工复核',
                      }

    const annotationReview = annotationTask?.annotation_revision?.review
    const reviewStep: WorkflowStep =
      annotationReview?.decision === 'REJECTED'
        ? {
            label: '人工复核',
            state: 'blocked',
            status: '未通过',
            detail: '已退回执行人修改，不会自动通过',
          }
        : annotationTask?.status === 'SUBMITTED'
          ? {
              label: '人工复核',
              state: 'current',
              status: '待复核',
              detail: '等待项目管理员人工复核，AI 不会自动通过',
            }
          : annotationReview?.decision === 'APPROVED'
            ? {
                label: '人工复核',
                state: 'complete',
                status: '已通过',
                detail: '正式动作标签已经人工确认',
              }
            : {
                label: '人工复核',
                state: 'waiting',
                status: annotationTask ? '待提交' : '待标注',
                detail: annotationTask ? '等待正式标注提交' : '等待人工标注任务',
              }

    const cleaningResult = annotationTask?.annotation_revision?.post_cleaning_result
    const cleaningStep: WorkflowStep =
      cleaningResult?.outcome === 'PASS'
        ? {
            label: '清洗',
            state: 'complete',
            status: '已通过清洗',
            detail: hasCurrentQualifiedQuality
              ? '已通过清洗，可参与训练数据导出'
              : '历史清洗已通过；当前质量结论未确认或未通过，暂不能参与训练数据导出',
          }
        : cleaningResult?.outcome === 'WARNING' || cleaningResult?.outcome === 'REJECT'
          ? {
              label: '清洗',
              state: 'blocked',
              status: cleaningResult.outcome === 'WARNING' ? '需处理' : '未通过',
              detail: '未通过 Cleaning 的数据不会进入训练导出',
            }
          : annotationReview?.decision === 'APPROVED'
            ? {
                label: '清洗',
                state: 'current',
                status: '等待结果',
                detail: '人工复核已通过，等待服务端清洗结果',
              }
            : {
                label: '清洗',
                state: 'waiting',
                status: '待复核',
                detail: '只有人工复核通过后才会产生清洗结果',
              }

    return [mediaStep, annotationStep, reviewStep, cleaningStep]
  }, [annotationTask, annotationTaskLoadState, detail, hasCurrentQualifiedQuality, qualityReview])

  const canParticipateInTrainingDataExport =
    hasCurrentQualifiedQuality &&
    annotationTaskLoadState === 'success' &&
    annotationTask?.annotation_revision?.post_cleaning_result?.outcome === 'PASS'

  const nextStep = useMemo<DetailNextStep>(() => {
    if (!detail) {
      return {
        title: '读取视频状态',
        description: '正在读取当前视频的处理状态。',
        buttonLabel: '读取状态',
        kind: 'none',
        interactive: false,
      }
    }
    if (detail.status === 'UPLOADED' || detail.status === 'INGESTING') {
      return {
        title: '视频检测中',
        description: '后台检测中，不影响当前人工查看；检测完成后页面会自动更新。',
        buttonLabel: '视频检测中',
        kind: 'none',
        interactive: false,
      }
    }
    if (detail.status === 'INGEST_FAILED') {
      return {
        title: '视频检测失败',
        description: '视频不能进入质量判断或人工标注，请查看下方媒体检测信息。',
        buttonLabel: '视频检测失败',
        kind: 'none',
        interactive: false,
      }
    }
    if (qualityLoadState !== 'success') {
      return {
        title: qualityLoadState === 'error' ? '暂时无法读取质量结论' : '读取人工质量判断',
        description: '需先确认当前人工质量结论，不使用前端缓存状态推进流程。',
        buttonLabel: '读取质量结论',
        kind: 'none',
        interactive: false,
      }
    }
    if (qualityReview?.decision === 'REJECTED') {
      return {
        title: '质量判断未通过',
        description: `当前质量结论：${qualityDecisionLabels[qualityReview.decision]}。`,
        buttonLabel: '查看质量结论',
        kind: 'scroll',
        interactive: true,
      }
    }
    if (qualityReview?.decision === 'UNREVIEWED' || qualityReview?.decision === 'NEEDS_REVIEW') {
      return {
        title: '进行人工质量判断',
        description: '确认视频是否适合进入配药动作标注。',
        buttonLabel: '下一步：人工质量判断',
        kind: 'scroll',
        interactive: true,
      }
    }
    if (canParticipateInTrainingDataExport) {
      return {
        title: '已通过清洗，可参与训练数据导出',
        description: '实际可导出的训练行数以训练数据导出预览为准。',
        buttonLabel: '导出训练数据',
        kind: 'navigate',
        interactive: projectRole === 'ADMIN',
      }
    }
    if (qualityReview?.decision === 'QUALIFIED' && annotationTaskLoadState !== 'success') {
      return {
        title: annotationTaskLoadState === 'error' ? '暂时无法确认标注任务' : '正在确认标注任务',
        description:
          annotationTaskLoadState === 'error'
            ? '质量结论已合格，请刷新状态后确认系统自动分配的标注任务。'
            : '质量结论已合格，系统正在读取自动分配的标注任务。',
        buttonLabel: '确认标注任务',
        kind: 'none',
        interactive: false,
      }
    }
    if (qualityReview?.decision === 'QUALIFIED' && annotationTask === null) {
      return {
        title: '标注任务已自动分配',
        description:
          '质量合格后系统会自动分配任务给质量确认人；可进入任务列表查看当前账号可见的任务。',
        buttonLabel: '查看标注任务',
        kind: 'navigate',
        interactive: true,
      }
    }
    if (annotationTask) {
      const reviewDecision = annotationTask.annotation_revision?.review?.decision
      const cleaningOutcome = annotationTask.annotation_revision?.post_cleaning_result?.outcome
      if (reviewDecision === 'REJECTED' || annotationTask.status === 'ASSIGNED') {
        return {
          title: '继续人工标注',
          description: '进入任务列表完成或修改配药动作时间段和动作标签。',
          buttonLabel: '继续人工标注',
          kind: 'navigate',
          interactive: true,
        }
      }
      if (annotationTask.status === 'SUBMITTED') {
        return annotationTask.can_review
          ? {
              title: '等待人工复核',
              description: '正式动作标签已提交，当前账号可以进入现有复核入口。',
              buttonLabel: '下一步：人工复核',
              kind: 'navigate',
              interactive: true,
            }
          : {
              title: '等待人工复核',
              description: '正式动作标签已提交，等待项目管理员人工复核。',
              buttonLabel: '等待人工复核',
              kind: 'none',
              interactive: false,
            }
      }
      if (reviewDecision === 'APPROVED') {
        if (cleaningOutcome === 'WARNING' || cleaningOutcome === 'REJECT') {
          return annotationTask.can_recheck_post_cleaning
            ? {
                title: '需要重新核对清洗',
                description: '清洗结论需要通过现有清洗复核入口重新核对。',
                buttonLabel: '重新核对清洗',
                kind: 'navigate',
                interactive: true,
              }
            : {
                title: '等待数据清洗',
                description: '人工复核已通过，等待具备权限的用户处理清洗结论。',
                buttonLabel: '等待数据清洗',
                kind: 'none',
                interactive: false,
              }
        }
        return {
          title: '等待数据清洗',
          description: '人工复核已通过，等待服务端完成数据清洗。',
          buttonLabel: '等待数据清洗',
          kind: 'none',
          interactive: false,
        }
      }
      return {
        title: '查看标注处理结果',
        description: '请进入现有标注任务页面查看服务端状态。',
        buttonLabel: '查看标注任务',
        kind: 'navigate',
        interactive: true,
      }
    }
    return {
      title: '查看处理流程',
      description: '按右侧流程继续处理当前视频。',
      buttonLabel: '查看处理流程',
      kind: 'scroll',
      interactive: true,
    }
  }, [
    canParticipateInTrainingDataExport,
    annotationTask,
    annotationTaskLoadState,
    detail,
    qualityLoadState,
    qualityReview,
    projectRole,
  ])
  const handPoseStatus = useMemo(() => {
    if (!handPoseJob) {
      return {
        tone: 'idle' as const,
        title: '等待自动手部检测',
        description: 'MediaPipe 只提供辅助候选，不会阻断人工质量判断、标注、复核或清洗。',
      }
    }
    if (handPoseJob.status === 'FAILED') {
      return {
        tone: 'failed' as const,
        title: '手部检测失败',
        description: [
          handPoseJob.run_summary?.failure_reason,
          handPoseJob.run_summary?.retryable === true
            ? '可以重新检测。'
            : '当前失败不可重试，可继续人工处理。',
        ]
          .filter(Boolean)
          .join(' '),
      }
    }
    if (handPoseJob.status === 'SUCCEEDED') {
      if (handPoseJob.hand_result === 'NO_HAND_DETECTED') {
        return {
          tone: 'succeeded' as const,
          title: '未发现手部，可继续人工查看和标注',
          description: '本次视频已完整分析，没有发现可用手部候选；原视频仍可继续人工查看和标注。',
        }
      }
      return {
        tone: 'succeeded' as const,
        title: handPoseJob.hand_result === 'HAND_DETECTED' ? '已发现手部候选' : '手部姿态检测完成',
        description: `已收到 ${handPoseCandidates?.candidates.length ?? 0} 条手部候选。打开视频后可查看叠加效果；候选仍须人工审核。`,
      }
    }
    return {
      tone: 'running' as const,
      title: '手部检测中',
      description: '系统正在分析该路视频。页面会自动刷新状态，关闭页面不会取消后台任务。',
    }
  }, [handPoseCandidates, handPoseJob])
  const playbackAvailability = useMemo(() => {
    if (!detail) return null
    if (canPlay)
      return {
        title: '可以直接查看视频',
        description:
          '视频已完成上传完整性校验。点击播放后获取短期只读地址；媒体检测不影响人工查看，也不会自动改变质量结论。',
        type: 'success' as const,
      }
    if (detail.status === 'UPLOADED')
      return {
        title: '媒体检测等待处理',
        description:
          '上传完成后平台已自动创建或复用检测任务。视频可直接人工查看；检测会补充时长、编码和完整解码等技术事实。',
        type: 'info' as const,
      }
    if (detail.status === 'INGESTING')
      return {
        title: '正在进行媒体检测',
        description: '平台正在检查视频是否可读取和完整解码；这不影响你直接人工查看。',
        type: 'warning' as const,
      }
    if (detail.status === 'INGEST_FAILED')
      return {
        title: '媒体检测未通过',
        description:
          '技术检测未完成，但仍可人工查看并决定是否保留。该视频不能进入配药动作标注或训练数据流程。',
        type: 'error' as const,
      }
    return {
      title: '当前视频暂不可播放',
      description: '请确认当前账号已加入项目，并且视频为平台支持的 MP4 格式。',
      type: 'info' as const,
    }
  }, [canPlay, detail])

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
  const resetMediaReport = useCallback(() => {
    mediaReportRequest.current?.abort()
    mediaReportRequest.current = null
    setMediaReport(null)
    setMediaReportLoadState('idle')
    setMediaReportErrorCode('')
    setMediaReportErrorMessage('')
  }, [])
  const loadHandPoseCandidates = useCallback(async () => {
    handPoseRequest.current?.abort()
    const request = new AbortController()
    handPoseRequest.current = request
    try {
      const response = await getLatestHandPoseCandidates(projectId, assetId, request.signal)
      if (handPoseRequest.current !== request) return
      if (!response.job || !Array.isArray(response.candidates)) {
        handPoseJobRef.current = null
        setHandPoseJob(null)
        setHandPoseCandidates(null)
        return
      }
      const previousStatus = handPoseJobRef.current?.status
      if (
        (previousStatus === 'QUEUED' || previousStatus === 'RUNNING') &&
        response.job.status === 'SUCCEEDED'
      ) {
        void message.success('手部检测完成')
      }
      if (
        (previousStatus === 'QUEUED' || previousStatus === 'RUNNING') &&
        response.job.status === 'FAILED'
      ) {
        void message.error('手部检测失败，可继续人工处理。')
      }
      handPoseJobRef.current = response.job
      setHandPoseJob(response.job)
      setHandPoseCandidates(response)
    } catch (error) {
      if (request.signal.aborted || handPoseRequest.current !== request) return
      if (error instanceof CatalogApiError && error.errorCode === 'AI_HAND_POSE_JOB_NOT_FOUND') {
        handPoseJobRef.current = null
        setHandPoseJob(null)
        setHandPoseCandidates(null)
        return
      }
      setHandPoseCandidates(null)
    } finally {
      if (handPoseRequest.current === request) handPoseRequest.current = null
    }
  }, [assetId, message, projectId])

  const loadMediaReport = useCallback(async () => {
    mediaReportRequest.current?.abort()
    const request = new AbortController()
    mediaReportRequest.current = request
    setMediaReportLoadState('loading')
    setMediaReportErrorCode('')
    setMediaReportErrorMessage('')
    try {
      const response = await getClipAssetMediaReport(projectId, assetId, request.signal)
      if (mediaReportRequest.current !== request) return
      setMediaReport(response)
      setMediaReportLoadState('success')
    } catch (error) {
      if (request.signal.aborted || mediaReportRequest.current !== request) return
      setMediaReport(null)
      setMediaReportLoadState('error')
      setMediaReportErrorCode(
        error instanceof CatalogApiError ? error.errorCode : 'MEDIA_REPORT_REQUEST_FAILED',
      )
      setMediaReportErrorMessage(
        error instanceof CatalogApiError ? error.message : '暂时无法读取媒体检测报告，请稍后重试。',
      )
    } finally {
      if (mediaReportRequest.current === request) mediaReportRequest.current = null
    }
  }, [assetId, projectId])
  const loadQualityReview = useCallback(async () => {
    qualityRequest.current?.abort()
    const request = new AbortController()
    qualityRequest.current = request
    setQualityLoadState('loading')
    setQualityErrorCode('')
    setQualityErrorMessage('')
    try {
      const result = await getQualityReview(projectId, assetId, request.signal)
      if (qualityRequest.current !== request) return
      setQualityReview(result)
      if (result.decision !== 'UNREVIEWED' && result.reason_code) {
        setSelectedQualityDecision(result.decision)
        setSelectedQualityReason(result.reason_code)
      } else {
        setSelectedQualityDecision('QUALIFIED')
        setSelectedQualityReason('MEETS_REQUIREMENTS')
      }
      setQualityLoadState('success')
    } catch (error) {
      if (request.signal.aborted || qualityRequest.current !== request) return
      setQualityReview(null)
      setQualityLoadState('error')
      setQualityErrorCode(
        error instanceof QualityReviewApiError ? error.errorCode : 'QUALITY_REVIEW_FAILED',
      )
      setQualityErrorMessage(
        error instanceof QualityReviewApiError
          ? error.message
          : '暂时无法读取质量结论，请稍后重试。',
      )
    } finally {
      if (qualityRequest.current === request) qualityRequest.current = null
    }
  }, [assetId, projectId])
  const loadAnnotationTask = useCallback(async () => {
    annotationTaskRequest.current?.abort()
    const request = new AbortController()
    annotationTaskRequest.current = request
    setAnnotationTaskLoadState('loading')
    try {
      const result = await listAnnotationTasks(projectId, request.signal)
      if (annotationTaskRequest.current !== request) return
      setAnnotationTask(result.items.find((task) => task.clip_asset_id === assetId) ?? null)
      setAnnotationTaskLoadState('success')
    } catch {
      if (request.signal.aborted || annotationTaskRequest.current !== request) return
      setAnnotationTask(null)
      setAnnotationTaskLoadState('error')
    } finally {
      if (annotationTaskRequest.current === request) annotationTaskRequest.current = null
    }
  }, [assetId, projectId])
  const loadDetail = useCallback(async () => {
    detailRequest.current?.abort()
    qualityRequest.current?.abort()
    annotationTaskRequest.current?.abort()
    handPoseRequest.current?.abort()
    const request = new AbortController()
    detailRequest.current = request
    setLoadState('loading')
    try {
      const response = await getClipAsset(projectId, assetId, request.signal)
      if (detailRequest.current !== request) return
      detailRef.current = response
      setDetail(response)
      resetPlayback()
      setAnnotationTask(null)
      setAnnotationTaskLoadState(response.status === 'INGESTED' ? 'loading' : 'idle')
      handPoseJobRef.current = null
      setHandPoseJob(null)
      setHandPoseCandidates(null)
      setLoadState('success')
      if (response.status === 'INGESTED') {
        void loadQualityReview()
        void loadAnnotationTask()
        void loadMediaReport()
        void loadHandPoseCandidates()
      } else {
        resetMediaReport()
        setQualityReview({
          quality_review_id: null,
          project_id: projectId,
          clip_asset_id: assetId,
          decision: 'UNREVIEWED',
          reason_code: null,
          reviewed_by: null,
          reviewed_at: null,
          version: 0,
        })
        setQualityLoadState('success')
      }
    } catch (error) {
      if (request.signal.aborted || detailRequest.current !== request) return
      setDetail(null)
      setLoadState('error')
      setErrorCode(error instanceof CatalogApiError ? error.errorCode : 'CATALOG_REQUEST_FAILED')
      setErrorMessage(
        error instanceof CatalogApiError ? error.message : '暂时无法读取视频资产详情，请稍后重试。',
      )
    } finally {
      if (detailRequest.current === request) detailRequest.current = null
    }
  }, [
    assetId,
    loadHandPoseCandidates,
    loadAnnotationTask,
    loadMediaReport,
    loadQualityReview,
    projectId,
    resetMediaReport,
    resetPlayback,
  ])

  const refreshTransientState = useCallback(async () => {
    if (transientStateRequest.current) return
    const request = new AbortController()
    transientStateRequest.current = request
    try {
      const response = await getClipAsset(projectId, assetId, request.signal)
      if (transientStateRequest.current !== request) return
      const previousStatus = detailRef.current?.status
      detailRef.current = response
      setDetail(response)
      if (
        (previousStatus === 'UPLOADED' || previousStatus === 'INGESTING') &&
        response.status === 'INGESTED'
      ) {
        void message.success('视频检测完成')
        void loadQualityReview()
        void loadAnnotationTask()
        void loadMediaReport()
        void loadHandPoseCandidates()
      } else if (response.status === 'INGESTED' && handPoseJobRef.current) {
        void loadHandPoseCandidates()
      }
    } catch {
      // Background refresh is best effort; the existing manual refresh remains available.
    } finally {
      if (transientStateRequest.current === request) transientStateRequest.current = null
    }
  }, [
    assetId,
    loadAnnotationTask,
    loadHandPoseCandidates,
    loadMediaReport,
    loadQualityReview,
    message,
    projectId,
  ])
  const loadProjectRole = useCallback(async () => {
    roleRequest.current?.abort()
    const request = new AbortController()
    roleRequest.current = request
    setProjectRole(null)
    try {
      const project = await getProject(projectId, request.signal)
      if (roleRequest.current !== request) return
      setProjectRole(project.current_role)
    } catch {
      if (!request.signal.aborted && roleRequest.current === request) setProjectRole(null)
    } finally {
      if (roleRequest.current === request) roleRequest.current = null
    }
  }, [projectId])

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setMediaProbeMessage('')
      void loadDetail()
      void loadProjectRole()
    }, 0)
    return () => {
      window.clearTimeout(timer)
      detailRequest.current?.abort()
      roleRequest.current?.abort()
      qualityRequest.current?.abort()
      mediaReportRequest.current?.abort()
      annotationTaskRequest.current?.abort()
      handPoseRequest.current?.abort()
      transientStateRequest.current?.abort()
      clearPlaybackExpiryTimer()
    }
  }, [clearPlaybackExpiryTimer, loadDetail, loadProjectRole])

  const shouldPollTransientState =
    detail?.status === 'UPLOADED' ||
    detail?.status === 'INGESTING' ||
    handPoseJob?.status === 'QUEUED' ||
    handPoseJob?.status === 'RUNNING'

  useEffect(() => {
    if (!shouldPollTransientState) return undefined
    let disposed = false
    let inFlight = false
    let timer: number | null = null

    const clearTimer = () => {
      if (timer !== null) {
        window.clearTimeout(timer)
        timer = null
      }
    }
    const schedule = () => {
      if (disposed || document.visibilityState !== 'visible' || timer !== null) return
      timer = window.setTimeout(() => {
        timer = null
        if (disposed || document.visibilityState !== 'visible' || inFlight) {
          schedule()
          return
        }
        inFlight = true
        void refreshTransientState().finally(() => {
          inFlight = false
          schedule()
        })
      }, 2_500)
    }
    const handleVisibilityChange = () => {
      clearTimer()
      if (document.visibilityState === 'visible') schedule()
    }

    document.addEventListener('visibilitychange', handleVisibilityChange)
    schedule()
    return () => {
      disposed = true
      clearTimer()
      document.removeEventListener('visibilitychange', handleVisibilityChange)
    }
  }, [refreshTransientState, shouldPollTransientState])

  async function confirmDelete() {
    if (!detail || isDeleting) return
    setActionErrorCode('')
    setActionErrorMessage('')
    const reason = await prompt({
      title: '确认删除视频',
      description: `将从项目中隐藏视频 ${detail.client_file_id}。不会立即物理删除对象或影响其他视频；请输入删除原因。`,
      confirmText: '确认删除',
      placeholder: '例如：上传错误、无用或质量不合格',
      validate: (value) => (value.trim() ? null : '请填写删除原因。'),
    })
    if (reason === null) return
    setIsDeleting(true)
    try {
      await deleteClipAsset(projectId, assetId, reason.trim())
      void message.success('视频已删除，并已从普通视频列表中隐藏。')
      await navigate(paths.videos(projectId), { replace: true })
    } catch (error) {
      setActionErrorCode(
        error instanceof CatalogApiError ? error.errorCode : 'CLIP_ASSET_DELETE_FAILED',
      )
      setActionErrorMessage(
        error instanceof CatalogApiError ? error.message : '视频删除未完成，请稍后重试。',
      )
    } finally {
      setIsDeleting(false)
    }
  }
  async function startPlayback() {
    if (!canPlay || isRequestingPlayback) return
    resetPlayback()
    setIsRequestingPlayback(true)
    setActionErrorCode('')
    setActionErrorMessage('')
    try {
      const grant = await authorizeClipAssetPlayback(projectId, assetId)
      setPlaybackGrant(grant)
      setPlaybackState('waiting')
      const remaining = new Date(grant.expires_at).getTime() - Date.now()
      if (!Number.isFinite(remaining) || remaining <= 0) {
        setPlaybackState('expired')
        setPlaybackMessage('本次播放授权已到期，请重新获取后继续审核。')
      } else {
        playbackExpiryTimer.current = setTimeout(() => {
          playbackExpiryTimer.current = null
          setPlaybackState('expired')
          setPlaybackMessage('本次播放授权已到期，请重新获取后继续审核。')
        }, remaining)
      }
    } catch (error) {
      setPlaybackGrant(null)
      setActionErrorCode(error instanceof CatalogApiError ? error.errorCode : 'PLAYBACK_FAILED')
      setActionErrorMessage(
        error instanceof CatalogApiError ? error.message : '无法获取播放地址，请稍后重试。',
      )
    } finally {
      setIsRequestingPlayback(false)
    }
  }
  async function recoverMissingMediaProbe() {
    if (!canRecoverMissingMediaProbe || isRecoveringMediaProbe) return
    const changeReference = await prompt({
      title: '恢复已丢失的检测任务',
      description:
        '仅在已确认当前检测任务的原消息不存在时继续。平台会结束旧尝试，并创建新的受审计尝试；不会自动重投或复用旧租约。请输入非敏感的事件或变更编号。',
      confirmText: '确认恢复',
      placeholder: '例如：INC-20260805-001',
      validate: (value) =>
        /^[A-Za-z0-9][A-Za-z0-9._:/#-]{2,127}$/.test(value.trim())
          ? null
          : '请输入 3–128 个允许字符组成的事件或变更编号。',
    })
    if (changeReference === null) return
    const normalizedReference = changeReference.trim()
    const intent =
      mediaProbeRecoveryIntent.current?.changeReference === normalizedReference
        ? mediaProbeRecoveryIntent.current
        : { changeReference: normalizedReference, idempotencyKey: crypto.randomUUID() }
    mediaProbeRecoveryIntent.current = intent
    setIsRecoveringMediaProbe(true)
    setActionErrorCode('')
    setActionErrorMessage('')
    try {
      const recovery = await recoverCurrentClipAssetMediaProbeDelivery(
        projectId,
        assetId,
        normalizedReference,
        intent.idempotencyKey,
      )
      mediaProbeRecoveryIntent.current = null
      setMediaProbeMessage(`媒体检测已恢复为第 ${recovery.new_attempt} 次尝试。请稍后刷新状态。`)
      void message.success('媒体检测恢复命令已记录并排队。')
    } catch (error) {
      setActionErrorCode(
        error instanceof CatalogApiError ? error.errorCode : 'MEDIA_PROBE_DELIVERY_RECOVERY_FAILED',
      )
      setActionErrorMessage(
        error instanceof CatalogApiError
          ? error.message
          : '媒体检测恢复未完成，请确认任务状态后重试。',
      )
    } finally {
      setIsRecoveringMediaProbe(false)
    }
  }
  async function saveQualityReview() {
    if (!qualityReview || !canReviewQuality || isSavingQuality) return
    setIsSavingQuality(true)
    setQualityErrorCode('')
    setQualityErrorMessage('')
    try {
      const saved = await recordQualityReview(projectId, assetId, {
        decision: selectedQualityDecision,
        reason_code: selectedQualityReason,
        expected_version: qualityReview.version,
      })
      setQualityReview(saved)
      if (saved.decision === 'QUALIFIED') {
        await loadAnnotationTask()
        void message.success('质量结论已保存，标注任务已自动创建并分配。')
      } else {
        void message.success('质量结论已保存。')
      }
    } catch (error) {
      setQualityErrorCode(
        error instanceof QualityReviewApiError ? error.errorCode : 'QUALITY_REVIEW_FAILED',
      )
      setQualityErrorMessage(
        error instanceof QualityReviewApiError ? error.message : '质量结论暂未保存，请稍后重试。',
      )
    } finally {
      setIsSavingQuality(false)
    }
  }
  async function requestHandPose() {
    if (!canRequestHandPose || isRequestingHandPose) return
    setIsRequestingHandPose(true)
    setActionErrorCode('')
    setActionErrorMessage('')
    try {
      const job =
        handPoseJob?.status === 'FAILED' && handPoseJob.run_summary?.retryable === true
          ? await retryHandPoseCandidates(projectId, assetId)
          : await requestHandPoseCandidates(projectId, assetId)
      handPoseJobRef.current = job
      setHandPoseJob(job)
      setHandPoseCandidates({ job, candidates: [] })
      void message.success(
        handPoseJob?.status === 'FAILED' ? '手部姿态检测已重新排队。' : '手部姿态检测已开始。',
      )
    } catch (error) {
      setActionErrorCode(
        error instanceof CatalogApiError ? error.errorCode : 'HAND_POSE_REQUEST_FAILED',
      )
      setActionErrorMessage(
        error instanceof CatalogApiError ? error.message : '手部姿态检测暂未提交，请稍后重试。',
      )
    } finally {
      setIsRequestingHandPose(false)
    }
  }
  function handlePlaybackError() {
    if (!playbackGrant) return
    const expiresAt = new Date(playbackGrant.expires_at).getTime()
    if (Number.isFinite(expiresAt) && expiresAt <= Date.now()) {
      clearPlaybackExpiryTimer()
      setPlaybackState('expired')
      setPlaybackMessage('本次播放授权已到期，请重新获取后继续审核。')
    } else {
      setPlaybackState('error')
      setPlaybackMessage('视频加载失败。请检查对象存储连接，或重新获取播放授权后再试。')
    }
  }

  function handleNextStep() {
    if (nextStep.kind === 'hand-pose') {
      void requestHandPose()
      return
    }
    if (nextStep.kind === 'media-recovery') {
      void recoverMissingMediaProbe()
      return
    }
    if (nextStep.kind === 'navigate') {
      navigate(paths.annotationTasks(projectId))
      return
    }
    document.getElementById('detail-next-step')?.scrollIntoView({
      behavior: 'smooth',
      block: 'start',
    })
  }

  return (
    <>
      <nav className="breadcrumb" aria-label="面包屑">
        <Link to={{ pathname: paths.videos(projectId), search: location.search }}>视频管理</Link>
        <span aria-hidden="true">/</span>
        <span>详情</span>
      </nav>
      {loadState === 'loading' ? <LoadingState>正在读取视频资产详情…</LoadingState> : null}
      {loadState === 'error' ? (
        <ErrorState code={errorCode} onRetry={() => void loadDetail()}>
          {errorMessage}
        </ErrorState>
      ) : null}
      {loadState === 'success' && detail ? (
        <>
          <section
            className="page-heading detail-heading workbench-heading"
            aria-labelledby="asset-detail-title"
          >
            <div className="workbench-heading-copy">
              <div className="workbench-title-row">
                <h1 id="asset-detail-title">视频详情</h1>
                <StatusBadge tone={statusTone(detail.status)}>
                  {assetStatusLabels[detail.status]}
                </StatusBadge>
              </div>
              <p className="workbench-video-name">
                <span>视频资产</span>
                <code title={detail.client_file_id}>
                  {conciseAssetIdentifier(detail.client_file_id)}
                </code>
              </p>
              {detail.session_binding ? (
                <p className="workbench-video-meta">
                  <span>
                    相机 {detail.session_binding.camera_role} #{detail.session_binding.camera_index}
                    {detail.session_binding.mount_position
                      ? ` · ${detail.session_binding.mount_position}`
                      : ''}
                  </span>
                  <span>拍摄开始 {formatDateTime(detail.session_binding.record_start_utc)}</span>
                </p>
              ) : null}
            </div>
            <div className="detail-actions">
              <Button
                onClick={() => {
                  void loadDetail()
                }}
              >
                刷新状态
              </Button>
            </div>
          </section>
          {actionErrorMessage ? (
            <Alert
              className="catalog-alert"
              type="error"
              title={actionErrorMessage}
              description={actionErrorCode ? `错误码：${actionErrorCode}` : undefined}
              showIcon
            />
          ) : null}
          <div className="video-workbench">
            <div className="workbench-grid">
              <section className="workbench-primary" aria-label="视频与人工处理">
                <PlaybackCard
                  availability={playbackAvailability}
                  candidateOverlay={handPoseCandidates?.candidates ?? []}
                  canPlay={canPlay}
                  canRecoverMissingMediaProbe={canRecoverMissingMediaProbe}
                  grant={playbackGrant}
                  isRecoveringMediaProbe={isRecoveringMediaProbe}
                  isRequestingPlayback={isRequestingPlayback}
                  mediaProbeMessage={mediaProbeMessage}
                  playbackMessage={playbackMessage}
                  playbackState={playbackState}
                  onClose={resetPlayback}
                  onLoadedMetadata={() => {
                    setPlaybackState('ready')
                    setPlaybackMessage('')
                  }}
                  onPlaybackError={handlePlaybackError}
                  onRecoverMissingMediaProbe={() => void recoverMissingMediaProbe()}
                  onStartPlayback={() => void startPlayback()}
                />
                <section
                  className="workbench-under-player"
                  id="detail-next-step"
                  aria-labelledby="detail-next-step-title"
                >
                  <h2 id="detail-next-step-title">人工处理</h2>
                  <QualityReviewCard
                    assetStatus={detail.status}
                    canReview={canReviewQuality}
                    errorCode={qualityErrorCode}
                    errorMessage={qualityErrorMessage}
                    isSaving={isSavingQuality}
                    loadState={qualityLoadState}
                    review={qualityReview}
                    selectedDecision={selectedQualityDecision}
                    selectedReason={selectedQualityReason}
                    onSave={() => void saveQualityReview()}
                    onSelectionChange={(decision, reason) => {
                      setSelectedQualityDecision(decision)
                      setSelectedQualityReason(reason)
                    }}
                  />
                  {canShowAnnotationTaskCard ? (
                    <AnnotationTaskCard createdTask={annotationTask} projectId={projectId} />
                  ) : null}
                </section>
              </section>
              <aside className="workbench-sidebar" aria-label="视频处理流程">
                <section
                  className="next-action-panel semantic-panel"
                  aria-labelledby="next-action-title"
                >
                  <span className="app-eyebrow">
                    {canParticipateInTrainingDataExport ? '当前结果' : '下一步'}
                  </span>
                  <strong id="next-action-title">{nextStep.title}</strong>
                  <p>{nextStep.description}</p>
                  {nextStep.interactive ? (
                    <Button type="primary" onClick={handleNextStep}>
                      {nextStep.buttonLabel}
                    </Button>
                  ) : null}
                </section>
                <section className="workflow-facts" aria-labelledby="workflow-facts-title">
                  <div className="workflow-facts-heading">
                    <span className="app-eyebrow">服务端事实</span>
                    <h2 id="workflow-facts-title">流程状态</h2>
                  </div>
                  <ol className="workflow-facts-list">
                    {workflowSteps.map((step) => (
                      <li
                        className={`workflow-facts-item workflow-facts-item-${step.state}`}
                        key={step.label}
                      >
                        <strong>{step.label}</strong>
                        <StatusBadge tone={workflowStepTone(step.state)}>{step.status}</StatusBadge>
                      </li>
                    ))}
                  </ol>
                  <details className="workflow-fact-details">
                    <summary>查看流程依据</summary>
                    <ul>
                      {workflowSteps.map((step) => (
                        <li key={step.label}>
                          <strong>{step.label}</strong>
                          <span>{step.detail}</span>
                        </li>
                      ))}
                    </ul>
                  </details>
                  {detail.status === 'INGESTED' ? (
                    <details className="hand-pose-disclosure" aria-labelledby="hand-pose-title">
                      <summary>
                        <span className="hand-pose-disclosure-title">
                          <strong id="hand-pose-title">手部候选</strong>
                          <small>MediaPipe · 仅辅助候选</small>
                        </span>
                        <StatusBadge tone={handPoseStatusTone(handPoseStatus.tone)}>
                          {handPoseStatus.title}
                        </StatusBadge>
                      </summary>
                      <div className="hand-pose-disclosure-content">
                        <p className="form-hint">
                          {handPoseStatus.description} MediaPipe Candidate 不是 Ground
                          Truth，不会自动改变正式动作标注或训练数据。
                        </p>
                        {canRequestHandPose ? (
                          <Button
                            loading={isRequestingHandPose}
                            onClick={() => void requestHandPose()}
                          >
                            重新检测
                          </Button>
                        ) : null}
                        {handPoseCandidates?.candidates?.[0] ? (
                          <p className="form-hint hand-pose-candidate-note">
                            已找到手部候选。点击“播放视频”即可在对应时间点查看骨架叠加。
                          </p>
                        ) : null}
                      </div>
                    </details>
                  ) : null}
                </section>
              </aside>
            </div>
            <details className="detail-disclosure">
              <summary>
                <strong>更多信息</strong>
                <span>媒体检测、文件技术信息、相机绑定与操作记录</span>
              </summary>
              <div className="detail-disclosure-content">
                <MediaReportCard
                  detail={detail}
                  report={mediaReport}
                  loadState={mediaReportLoadState}
                  errorCode={mediaReportErrorCode}
                  errorMessage={mediaReportErrorMessage}
                />
                <AssetFacts detail={detail} />
                <AssetActivityCard key={assetId} assetId={assetId} projectId={projectId} />
              </div>
            </details>
            {canDelete ? (
              <details className="detail-disclosure detail-more-actions">
                <summary>
                  <strong>更多操作</strong>
                  <span>删除视频等低频高风险操作</span>
                </summary>
                <div className="detail-disclosure-content">
                  <DangerZoneCard isDeleting={isDeleting} onDelete={() => void confirmDelete()} />
                </div>
              </details>
            ) : null}
          </div>
        </>
      ) : null}
    </>
  )
}
