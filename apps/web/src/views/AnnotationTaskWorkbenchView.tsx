import { Alert, App as AntdApp, Button, Form, Input, InputNumber, Select } from 'antd'
import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router'

import {
  AnnotationTaskApiError,
  getAnnotationLabelSchema,
  listAnnotationTasks,
  recheckAnnotationPostCleaning,
  reviewAnnotationRevision,
  submitAnnotationRevision,
  type AnnotationLabelSchema,
  type AnnotationSegment,
  type AnnotationTask,
} from '@/api/annotation'
import {
  authorizeClipAssetPlayback,
  CatalogApiError,
  getClipAsset,
  getLatestHandPoseCandidates,
  type ClipAssetPlaybackGrant,
  type ClipAssetRead,
  type HandPoseCandidateResponse,
} from '@/api/catalog'
import { paths } from '@/app/paths'
import { PageHeader } from '@/components/PageHeader'
import { PlaybackCard, type PlaybackState } from '@/components/AssetDetailSections'
import { MetadataList } from '@/components/data/MetadataList'
import { StatusBadge, type StatusBadgeTone } from '@/components/StatusBadge'
import { StatusIndicator, type StatusIndicatorTone } from '@/components/status/StatusIndicator'
import { ErrorState, LoadingState } from '@/components/WorkspaceFeedback'
import { InspectorSection } from '@/components/workspace/InspectorSection'
import { Section } from '@/components/workspace/Section'
import {
  assetStatusLabels,
  cleaningStatusLabels,
  formatBytes,
  formatDateTime,
  shortId,
} from '@/features/catalog/catalog-presentation'
import {
  classifyCandidateReadError,
  type CandidateLoadState,
} from '@/features/catalog/hand-pose-candidate-state'

interface DraftAnnotationSegment extends AnnotationSegment {
  readonly draftId: number
}

interface SegmentValidationErrors {
  start?: string
  end?: string
  label?: string
}

let nextDraftSegmentId = 0

function createDraftSegment(segment?: AnnotationSegment): DraftAnnotationSegment {
  nextDraftSegmentId += 1
  return {
    draftId: nextDraftSegmentId,
    start_ms: segment?.start_ms ?? 0,
    end_ms: segment?.end_ms ?? 1000,
    label: segment?.label ?? '',
  }
}

function initialDraftSegmentsForTask(task: AnnotationTask): DraftAnnotationSegment[] {
  if (!task.can_submit || task.status !== 'ASSIGNED') return []
  const rejectedSegments =
    task.annotation_revision?.review?.decision === 'REJECTED'
      ? task.annotation_revision.annotation_document.segments
      : []
  return rejectedSegments.length
    ? rejectedSegments.map((segment) => createDraftSegment(segment))
    : [createDraftSegment()]
}

function taskStatusLabel(task: AnnotationTask) {
  if (task.status === 'COMPLETED') return '标注完成'
  if (task.status === 'SUBMITTED') return '待管理员复核'
  return task.annotation_revision?.review?.decision === 'REJECTED' ? '待修改' : '待提交'
}

function taskStatusTone(task: AnnotationTask): StatusIndicatorTone {
  if (task.status === 'COMPLETED') return 'success'
  if (task.annotation_revision?.review?.decision === 'REJECTED') return 'warning'
  return task.status === 'SUBMITTED' ? 'information' : 'neutral'
}

function reviewStatusTone(decision: 'APPROVED' | 'REJECTED'): StatusIndicatorTone {
  return decision === 'APPROVED' ? 'success' : 'destructive'
}

function cleaningStatusTone(outcome: 'PASS' | 'WARNING' | 'REJECT'): StatusBadgeTone {
  if (outcome === 'PASS') return 'success'
  return outcome === 'WARNING' ? 'warning' : 'error'
}

function formatPlaybackTime(value: number) {
  return `${Math.max(0, Math.round(value))} 毫秒`
}

function validateDraftSegment(segment: DraftAnnotationSegment): SegmentValidationErrors {
  const errors: SegmentValidationErrors = {}
  if (segment.start_ms < 0) errors.start = '开始时间不能小于 0 毫秒。'
  if (segment.end_ms <= segment.start_ms) errors.end = '结束时间必须晚于开始时间。'
  if (!segment.label.trim()) errors.label = '请选择标签。'
  return errors
}

function validateDraftSegments(segments: DraftAnnotationSegment[]) {
  const errors: Record<number, SegmentValidationErrors> = {}
  segments.forEach((segment) => {
    const segmentErrors = validateDraftSegment(segment)
    if (segmentErrors.start || segmentErrors.end || segmentErrors.label) {
      errors[segment.draftId] = segmentErrors
    }
  })
  return errors
}

function safeCandidateDescription(
  candidateState: CandidateLoadState,
  candidateResponse: HandPoseCandidateResponse | null,
) {
  if (candidateState === 'loading') return '正在读取手部检测结果；这不会阻止人工标注。'
  if (candidateState === 'error') return '手部检测结果暂时无法读取；这不会阻止人工标注。'
  if (candidateState === 'unavailable') return '当前没有可用的手部检测结果；这不会阻止人工标注。'
  if (!candidateResponse) return '手部检测结果仅在媒体检测完成后读取。'
  if (candidateResponse.job.status === 'FAILED') {
    return '手部检测未完成；结果仅作辅助，不影响人工质量判断或标注。'
  }
  if (candidateResponse.job.status === 'QUEUED' || candidateResponse.job.status === 'RUNNING') {
    return '手部检测进行中；结果仅作辅助，不影响人工质量判断或标注。'
  }
  if (candidateResponse.job.hand_result === 'NO_HAND_DETECTED') {
    return '未检测到手部；这不是人工流程失败，也不会阻止标注。'
  }
  return `${candidateResponse.candidates.length} 组手部关键点；结果仅作辅助，不是正式人工标注。`
}

export default function AnnotationTaskWorkbenchView() {
  const { projectId = '', taskId = '' } = useParams()
  const navigate = useNavigate()
  const { message, modal } = AntdApp.useApp()
  const [task, setTask] = useState<AnnotationTask | null>(null)
  const [schema, setSchema] = useState<AnnotationLabelSchema | null>(null)
  const [detail, setDetail] = useState<ClipAssetRead | null>(null)
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [errorCode, setErrorCode] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const [candidateResponse, setCandidateResponse] = useState<HandPoseCandidateResponse | null>(null)
  const [candidateState, setCandidateState] = useState<CandidateLoadState>('idle')
  const [draftSegments, setDraftSegments] = useState<DraftAnnotationSegment[]>([])
  const [segmentErrors, setSegmentErrors] = useState<Record<number, SegmentValidationErrors>>({})
  const [note, setNote] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [reviewingRevisionId, setReviewingRevisionId] = useState<string | null>(null)
  const [recheckingRevisionId, setRecheckingRevisionId] = useState<string | null>(null)
  const [playbackGrant, setPlaybackGrant] = useState<ClipAssetPlaybackGrant | null>(null)
  const [playbackState, setPlaybackState] = useState<PlaybackState>('idle')
  const [playbackMessage, setPlaybackMessage] = useState('')
  const [isRequestingPlayback, setIsRequestingPlayback] = useState(false)
  const [playbackTimeMs, setPlaybackTimeMs] = useState(0)
  const loadRequest = useRef<AbortController | null>(null)
  const candidateRequest = useRef<AbortController | null>(null)
  const playbackExpiryTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const videoRef = useRef<HTMLVideoElement | null>(null)

  const sortedLabels = useMemo(
    () =>
      [...(schema?.labels ?? [])].sort(
        (a, b) => a.sort_order - b.sort_order || a.label_id.localeCompare(b.label_id),
      ),
    [schema],
  )
  const revision = task?.annotation_revision ?? null
  const review = revision?.review ?? null
  const schemaMatches = Boolean(
    task &&
      schema &&
      task.label_schema_id === schema.label_schema_id &&
      task.label_schema_digest === schema.schema_digest,
  )
  const canEdit = Boolean(task?.can_submit && task.status === 'ASSIGNED')
  const canSubmit = canEdit && schemaMatches
  const canReview = Boolean(task?.can_review && revision)
  const canRecheck = Boolean(task?.can_recheck_post_cleaning && revision)
  const canPlay = detail?.content_type === 'video/mp4'
  const canControlPlayback = Boolean(
    playbackGrant && playbackState !== 'error' && playbackState !== 'expired',
  )
  const candidateOverlay = candidateResponse?.candidates ?? []

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
    setPlaybackTimeMs(0)
  }, [clearPlaybackExpiryTimer])

  const loadCandidates = useCallback(
    async (clipAssetId: string) => {
      candidateRequest.current?.abort()
      const request = new AbortController()
      candidateRequest.current = request
      setCandidateState('loading')
      setCandidateResponse(null)
      try {
        const response = await getLatestHandPoseCandidates(projectId, clipAssetId, request.signal)
        if (request.signal.aborted || candidateRequest.current !== request) return
        setCandidateResponse(response)
        setCandidateState('success')
      } catch (error) {
        if (request.signal.aborted || candidateRequest.current !== request) return
        setCandidateResponse(null)
        setCandidateState(classifyCandidateReadError(error))
      } finally {
        if (candidateRequest.current === request) candidateRequest.current = null
      }
    },
    [projectId],
  )

  const loadWorkbench = useCallback(async () => {
    loadRequest.current?.abort()
    candidateRequest.current?.abort()
    const request = new AbortController()
    loadRequest.current = request
    setLoadState('loading')
    setErrorCode('')
    setErrorMessage('')
    setTask(null)
    setSchema(null)
    setDetail(null)
    setCandidateResponse(null)
    setCandidateState('idle')
    setDraftSegments([])
    setSegmentErrors({})
    setNote('')
    resetPlayback()
    try {
      const [taskList, labelSchema] = await Promise.all([
        listAnnotationTasks(projectId, request.signal),
        getAnnotationLabelSchema(projectId, request.signal),
      ])
      if (request.signal.aborted || loadRequest.current !== request) return
      const nextTask = taskList.items.find((item) => item.annotation_task_id === taskId)
      if (!nextTask) {
        throw new AnnotationTaskApiError(
          '当前标注任务不存在，或已不属于当前项目。请返回任务列表后重试。',
          'ANNOTATION_TASK_NOT_FOUND',
        )
      }
      if (nextTask.capture_group_id) {
        void navigate(paths.captureGroup(projectId, nextTask.capture_group_id), { replace: true })
        return
      }
      if (!nextTask.clip_asset_id) {
        throw new AnnotationTaskApiError(
          '当前任务缺少可用的视频资产或 CaptureGroup 归属。请返回任务列表后重试。',
          'ANNOTATION_TASK_NOT_FOUND',
        )
      }
      const nextDetail = await getClipAsset(projectId, nextTask.clip_asset_id, request.signal)
      if (request.signal.aborted || loadRequest.current !== request) return
      setDraftSegments(initialDraftSegmentsForTask(nextTask))
      setSegmentErrors({})
      setNote('')
      setTask(nextTask)
      setSchema(labelSchema)
      setDetail(nextDetail)
      setLoadState('success')
      if (nextDetail.status === 'INGESTED') void loadCandidates(nextTask.clip_asset_id)
    } catch (error) {
      if (request.signal.aborted || loadRequest.current !== request) return
      setLoadState('error')
      setErrorCode(
        error instanceof AnnotationTaskApiError || error instanceof CatalogApiError
          ? error.errorCode
          : 'ANNOTATION_WORKBENCH_REQUEST_FAILED',
      )
      setErrorMessage(
        error instanceof AnnotationTaskApiError || error instanceof CatalogApiError
          ? error.message
          : '暂时无法读取当前标注工作台，请稍后重试。',
      )
    } finally {
      if (loadRequest.current === request) loadRequest.current = null
    }
  }, [loadCandidates, navigate, projectId, resetPlayback, taskId])

  useEffect(() => {
    const timer = window.setTimeout(() => void loadWorkbench(), 0)
    return () => {
      window.clearTimeout(timer)
      loadRequest.current?.abort()
      candidateRequest.current?.abort()
      clearPlaybackExpiryTimer()
    }
  }, [clearPlaybackExpiryTimer, loadWorkbench])

  const playbackAvailability = useMemo(() => {
    if (!detail) return null
    if (canPlay) {
      return {
        title: '视频可播放',
        description: '点击播放后获取临时只读地址；播放不会改变标注或复核状态。',
        type: 'success' as const,
      }
    }
    if (detail.status === 'UPLOADED') {
      return {
        title: '媒体检测等待处理',
        description: '媒体检测完成后才能确认可播放状态；这不改变当前标注任务事实。',
        type: 'info' as const,
      }
    }
    if (detail.status === 'INGESTING') {
      return {
        title: '正在进行媒体检测',
        description: '平台正在检查视频是否可读取和完整解码。',
        type: 'warning' as const,
      }
    }
    if (detail.status === 'INGEST_FAILED') {
      return {
        title: '媒体检测未通过',
        description: '当前视频不能进入后续训练流程；如需确认，请返回视频详情查看检测事实。',
        type: 'error' as const,
      }
    }
    return {
      title: '当前视频暂不可播放',
      description: '当前仅支持播放 MP4 格式的视频。',
      type: 'info' as const,
    }
  }, [canPlay, detail])

  async function startPlayback() {
    if (!canPlay || isRequestingPlayback) return
    resetPlayback()
    setIsRequestingPlayback(true)
    try {
      const grant = await authorizeClipAssetPlayback(projectId, detail?.clip_asset_id ?? '')
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
      setPlaybackGrant(null)
      setPlaybackState('error')
      setPlaybackMessage(
        error instanceof CatalogApiError ? error.message : '无法获取播放地址，请稍后重试。',
      )
    } finally {
      setIsRequestingPlayback(false)
    }
  }

  function handlePlaybackError() {
    setPlaybackState('error')
    setPlaybackMessage('视频播放失败，请重新获取播放授权后重试。')
  }

  function updateSegment(index: number, patch: Partial<AnnotationSegment>) {
    const segment = draftSegments[index]
    setDraftSegments((current) =>
      current.map((currentSegment, currentIndex) =>
        currentIndex === index ? { ...currentSegment, ...patch } : currentSegment,
      ),
    )
    if (segment) {
      const nextSegment = { ...segment, ...patch }
      setSegmentErrors((current) => {
        const nextSegmentErrors = validateDraftSegment(nextSegment)
        const rest = { ...current }
        if (Object.keys(nextSegmentErrors).length === 0) {
          delete rest[segment.draftId]
        } else {
          rest[segment.draftId] = nextSegmentErrors
        }
        return rest
      })
    }
  }

  function addSegment() {
    setDraftSegments((current) => [...current, createDraftSegment()])
  }

  function removeSegment(index: number) {
    const segment = draftSegments[index]
    setDraftSegments((current) => current.filter((_, currentIndex) => currentIndex !== index))
    if (segment) {
      setSegmentErrors((current) => {
        const rest = { ...current }
        delete rest[segment.draftId]
        return rest
      })
    }
  }

  function seekVideo(timeMs: number) {
    if (!videoRef.current || !canControlPlayback) return
    const nextTimeMs = Math.max(0, Math.round(timeMs))
    videoRef.current.currentTime = nextTimeMs / 1000
    setPlaybackTimeMs(nextTimeMs)
  }

  function setSegmentTime(index: number, field: 'start_ms' | 'end_ms') {
    if (!canControlPlayback) return
    updateSegment(index, { [field]: Math.max(0, Math.round(playbackTimeMs)) })
  }

  async function submit(event?: FormEvent<HTMLFormElement>) {
    event?.preventDefault()
    if (!task || !schema || !canSubmit || isSubmitting) return
    if (!schemaMatches) {
      void message.error('任务绑定的标签规范与当前项目不一致，请刷新后重试。')
      return
    }
    const validSegments = draftSegments.map(({ start_ms, end_ms, label }) => ({
      start_ms,
      end_ms,
      label: label.trim(),
    }))
    const nextSegmentErrors = validateDraftSegments(draftSegments)
    if (!validSegments.length || Object.keys(nextSegmentErrors).length) {
      setSegmentErrors(nextSegmentErrors)
      void message.error('请修正标红的标注时间段后再提交。')
      return
    }
    setSegmentErrors({})
    setIsSubmitting(true)
    try {
      await submitAnnotationRevision(projectId, task.annotation_task_id, validSegments, note)
      setDraftSegments([])
      setNote('')
      void message.success('配药动作标注已提交，等待管理员复核。')
      await loadWorkbench()
    } catch (error) {
      void message.error(
        error instanceof AnnotationTaskApiError
          ? error.message
          : '配药动作标注暂未提交，请稍后重试。',
      )
    } finally {
      setIsSubmitting(false)
    }
  }

  function reviewRevision(decision: 'APPROVED' | 'REJECTED') {
    if (!task || !revision || !task.can_review || reviewingRevisionId) return
    const approved = decision === 'APPROVED'
    let reviewNote = ''
    modal.confirm({
      title: approved ? '通过配药动作标注' : '退回配药动作标注',
      content: approved ? (
        '确认时间段标注与视频画面一致，并将其作为正式人工配药动作标注？'
      ) : (
        <div className="annotation-review-confirmation">
          <p>退回这次配药动作标注，要求标注人修改后重新提交。</p>
          <Input.TextArea
            aria-label="退回修改说明（可选）"
            maxLength={1000}
            rows={3}
            placeholder="补充需要修改的内容（可选）"
            onChange={(event) => {
              reviewNote = event.target.value
            }}
          />
        </div>
      ),
      okText: approved ? '通过复核' : '退回修改',
      cancelText: '取消',
      okButtonProps: { danger: !approved },
      async onOk() {
        setReviewingRevisionId(revision.annotation_revision_id)
        try {
          await reviewAnnotationRevision(
            projectId,
            revision.annotation_revision_id,
            decision,
            reviewNote,
          )
          void message.success(approved ? '配药动作标注已通过复核。' : '已退回配药动作标注。')
          await loadWorkbench()
        } catch (error) {
          void message.error(
            error instanceof AnnotationTaskApiError ? error.message : '复核暂未提交，请稍后重试。',
          )
          throw error
        } finally {
          setReviewingRevisionId(null)
        }
      },
    })
  }

  function recheckCleaning() {
    if (!task || !revision || !task.can_recheck_post_cleaning || recheckingRevisionId) return
    modal.confirm({
      title: '重新核对清洗',
      content: '按当前人工质量、视频和标注事实重新核对清洗结果；历史结果会保留。',
      okText: '重新核对',
      cancelText: '取消',
      async onOk() {
        setRecheckingRevisionId(revision.annotation_revision_id)
        try {
          const previousResult = revision.post_cleaning_result
          const result = await recheckAnnotationPostCleaning(
            projectId,
            revision.annotation_revision_id,
          )
          const sameResult = previousResult?.cleaning_result_id === result.cleaning_result_id
          if (sameResult) {
            void message.info(
              `当前事实未变化，清洗结论保持为“${cleaningStatusLabels[result.outcome]}”。`,
            )
          } else {
            const resultMessage = `已按当前事实重新核对清洗：${cleaningStatusLabels[result.outcome]}。`
            if (result.outcome === 'PASS') void message.success(resultMessage)
            else void message.warning(resultMessage)
          }
          await loadWorkbench()
        } catch (error) {
          void message.error(
            error instanceof AnnotationTaskApiError
              ? error.message
              : '暂时无法重新核对清洗，请稍后重试。',
          )
          throw error
        } finally {
          setRecheckingRevisionId(null)
        }
      },
    })
  }

  function renderRevisionEvidence() {
    if (!revision) {
      return <p className="form-hint">尚未提交正式人工修订。</p>
    }
    return (
      <div className="annotation-workbench-evidence">
        <div className="annotation-evidence-summary">
          <StatusIndicator tone="information">
            第 {revision.revision_number} 次正式修订 ·{' '}
            {revision.annotation_document.segments.length} 个时间段
          </StatusIndicator>
          <span className="form-hint">提交于 {formatDateTime(revision.submitted_at)}</span>
        </div>
        <ol className="annotation-readonly-segments">
          {revision.annotation_document.segments.map((segment, index) => {
            const label =
              schema?.labels.find((item) => item.label_id === segment.label)?.display_name ??
              segment.label
            return (
              <li key={`${segment.start_ms}-${segment.end_ms}-${segment.label}`}>
                <span>时间段 {index + 1}</span>
                <strong>
                  {segment.start_ms}–{segment.end_ms} 毫秒
                </strong>
                <span>{label}</span>
              </li>
            )
          })}
        </ol>
        {revision.annotation_document.note ? (
          <p className="task-annotation-note">标注备注：{revision.annotation_document.note}</p>
        ) : null}
      </div>
    )
  }

  if (loadState === 'error') {
    return (
      <>
        <nav className="breadcrumb" aria-label="面包屑导航">
          <Link to={paths.videos(projectId)}>视频管理</Link>
          <span aria-hidden="true">/</span>
          <Link to={paths.annotationTasks(projectId)}>配药动作标注</Link>
          <span aria-hidden="true">/</span>
          <span>工作台</span>
        </nav>
        <PageHeader
          eyebrow="单任务人工工作台"
          title="配药动作标注工作台"
          description="读取当前任务的服务端状态后，再进行视频查看、人工标注或复核。"
          actions={
            <Button onClick={() => void navigate(paths.annotationTasks(projectId))}>
              返回任务队列
            </Button>
          }
        />
        <ErrorState code={errorCode} onRetry={() => void loadWorkbench()}>
          {errorMessage}
        </ErrorState>
      </>
    )
  }

  return (
    <>
      <nav className="breadcrumb" aria-label="面包屑导航">
        <Link to={paths.videos(projectId)}>视频管理</Link>
        <span aria-hidden="true">/</span>
        <Link to={paths.annotationTasks(projectId)}>配药动作标注</Link>
        <span aria-hidden="true">/</span>
        <span>工作台</span>
      </nav>
      <PageHeader
        eyebrow="单任务人工工作台"
        title="配药动作标注工作台"
        description="以当前视频为依据记录正式人工动作时间段；手部检测结果只作查看辅助。"
        actions={
          <>
            <Button onClick={() => void navigate(paths.annotationTasks(projectId))}>
              返回任务队列
            </Button>
            <Button
              aria-label="刷新"
              size="small"
              loading={loadState === 'loading'}
              onClick={() => void loadWorkbench()}
            >
              刷新
            </Button>
          </>
        }
      />
      {loadState === 'loading' ? <LoadingState>正在读取当前标注任务…</LoadingState> : null}
      {loadState === 'success' && task && detail ? (
        <div className="annotation-workbench-layout workbench-grid">
          <section className="workbench-primary" aria-label="视频与人工标注">
            <PlaybackCard
              availability={playbackAvailability}
              candidateOverlay={candidateOverlay}
              canPlay={canPlay}
              canRecoverMissingMediaProbe={false}
              externalVideoRef={videoRef}
              grant={playbackGrant}
              isRecoveringMediaProbe={false}
              isRequestingPlayback={isRequestingPlayback}
              mediaProbeMessage=""
              onPlaybackTimeChange={setPlaybackTimeMs}
              playbackMessage={playbackMessage}
              playbackState={playbackState}
              onClose={resetPlayback}
              onLoadedMetadata={() => {
                setPlaybackState('ready')
                setPlaybackMessage('')
              }}
              onPlaybackError={handlePlaybackError}
              onRecoverMissingMediaProbe={() => undefined}
              onStartPlayback={() => void startPlayback()}
            />
            <section className="annotation-workbench-auxiliary" aria-label="手部检测辅助信息">
              <div className="annotation-workbench-auxiliary-heading">
                <StatusIndicator tone={candidateOverlay.length ? 'success' : 'neutral'}>
                  手部检测
                </StatusIndicator>
                <span>{safeCandidateDescription(candidateState, candidateResponse)}</span>
              </div>
              {candidateOverlay.length ? (
                <span className="form-hint">
                  播放器中的 Skeleton Overlay 与视频同步显示；Left / Right 仅作方向辅助。
                </span>
              ) : null}
            </section>
            <Section
              className="annotation-workbench-editor"
              title="人工动作标注"
              titleId="annotation-workbench-editor-title"
            >
              {review?.decision === 'REJECTED' && review.review_note ? (
                <Alert
                  className="annotation-rework-notice"
                  type="warning"
                  showIcon
                  title="需要根据上次复核意见修改"
                  description={review.review_note}
                />
              ) : null}
              {canEdit ? (
                <>
                  <p className="form-hint">
                    按当前项目标签规范记录时间段。可播放视频时，使用当前播放位置辅助取值；未提交的草稿只保存在当前页面。
                  </p>
                  {!schemaMatches ? (
                    <Alert
                      type="error"
                      showIcon
                      title="标签规范已变化"
                      description="当前任务绑定的标签规范与服务端返回的项目规范不一致，请刷新后再提交。"
                    />
                  ) : null}
                  <div className="annotation-playback-position" role="status">
                    <span>当前播放位置：{formatPlaybackTime(playbackTimeMs)}</span>
                    <Button
                      size="small"
                      disabled={!canControlPlayback}
                      onClick={() => seekVideo(playbackTimeMs)}
                    >
                      重新定位
                    </Button>
                  </div>
                  <Form
                    layout="vertical"
                    aria-label="提交配药动作时间段"
                    onFinish={() => void submit()}
                  >
                    {draftSegments.map((segment, index) => {
                      const segmentError = segmentErrors[segment.draftId]
                      return (
                        <fieldset
                          key={segment.draftId}
                          className="annotation-workbench-segment-row"
                        >
                          <legend>标注时间段 {index + 1}</legend>
                          <Form.Item
                            label="开始（毫秒）"
                            validateStatus={segmentError?.start ? 'error' : undefined}
                            help={
                              segmentError?.start ? (
                                <span role="alert">{segmentError.start}</span>
                              ) : undefined
                            }
                          >
                            <InputNumber
                              aria-label={`标注时间段 ${index + 1} 的开始时间`}
                              aria-invalid={Boolean(segmentError?.start)}
                              min={0}
                              step={100}
                              value={segment.start_ms}
                              onChange={(value) => updateSegment(index, { start_ms: value ?? 0 })}
                            />
                            <Button
                              size="small"
                              disabled={!canControlPlayback}
                              onClick={() => setSegmentTime(index, 'start_ms')}
                            >
                              取当前
                            </Button>
                          </Form.Item>
                          <Form.Item
                            label="结束（毫秒）"
                            validateStatus={segmentError?.end ? 'error' : undefined}
                            help={
                              segmentError?.end ? (
                                <span role="alert">{segmentError.end}</span>
                              ) : undefined
                            }
                          >
                            <InputNumber
                              aria-label={`标注时间段 ${index + 1} 的结束时间`}
                              aria-invalid={Boolean(segmentError?.end)}
                              min={1}
                              step={100}
                              value={segment.end_ms}
                              onChange={(value) => updateSegment(index, { end_ms: value ?? 1 })}
                            />
                            <Button
                              size="small"
                              disabled={!canControlPlayback}
                              onClick={() => setSegmentTime(index, 'end_ms')}
                            >
                              取当前
                            </Button>
                          </Form.Item>
                          <Form.Item
                            label="标签"
                            validateStatus={segmentError?.label ? 'error' : undefined}
                            help={
                              segmentError?.label ? (
                                <span role="alert">{segmentError.label}</span>
                              ) : undefined
                            }
                          >
                            <Select
                              value={segment.label || undefined}
                              placeholder="选择步骤标签"
                              aria-label={`标注时间段 ${index + 1} 的标签`}
                              aria-invalid={Boolean(segmentError?.label)}
                              onChange={(value) => updateSegment(index, { label: value })}
                              options={sortedLabels.map((label) => ({
                                value: label.label_id,
                                label: label.display_name,
                              }))}
                            />
                          </Form.Item>
                          <Button
                            size="small"
                            aria-label={`跳转到标注时间段 ${index + 1} 的开始时间`}
                            disabled={!canControlPlayback}
                            onClick={() => seekVideo(segment.start_ms)}
                          >
                            跳转
                          </Button>
                          <Button
                            size="small"
                            aria-label={`移除标注时间段 ${index + 1}`}
                            disabled={draftSegments.length === 1}
                            onClick={() => removeSegment(index)}
                          >
                            移除
                          </Button>
                        </fieldset>
                      )
                    })}
                    <Button onClick={addSegment}>新增时间段</Button>
                    <Form.Item label="备注（可选）">
                      <Input.TextArea
                        aria-label="备注（可选）"
                        value={note}
                        maxLength={1000}
                        rows={3}
                        onChange={(event) => setNote(event.target.value)}
                      />
                    </Form.Item>
                    <div className="detail-actions annotation-submit-actions">
                      <Button
                        htmlType="submit"
                        type="primary"
                        loading={isSubmitting}
                        disabled={!schemaMatches}
                      >
                        提交标注
                      </Button>
                    </div>
                  </Form>
                </>
              ) : (
                <>
                  <p className="form-hint">
                    {task.status === 'SUBMITTED'
                      ? '正式人工标注已提交，等待管理员复核。'
                      : task.status === 'COMPLETED'
                        ? '正式人工标注已完成复核。'
                        : '当前账号不能提交这个任务；以下为服务端已保存的修订事实。'}
                  </p>
                  {renderRevisionEvidence()}
                </>
              )}
            </Section>
          </section>
          <aside
            className="workbench-sidebar annotation-workbench-inspector"
            aria-label="任务检查器"
          >
            <InspectorSection title="任务状态" titleId="annotation-workbench-task-status">
              <div className="annotation-workbench-status-line">
                <StatusIndicator tone={taskStatusTone(task)}>
                  {taskStatusLabel(task)}
                </StatusIndicator>
                {task.status === 'ASSIGNED' && task.can_submit ? (
                  <span className="form-hint">当前账号可提交</span>
                ) : null}
              </div>
              <MetadataList
                ariaLabel="任务事实"
                items={[
                  {
                    id: 'task-id',
                    label: '任务 ID',
                    value: (
                      <code title={task.annotation_task_id}>
                        {shortId(task.annotation_task_id)}
                      </code>
                    ),
                  },
                  {
                    id: 'video-id',
                    label: '视频资产',
                    value: (
                      <Link
                        className="text-link"
                        to={paths.asset(projectId, task.clip_asset_id ?? '')}
                      >
                        {shortId(task.clip_asset_id ?? task.annotation_task_id)}
                      </Link>
                    ),
                  },
                  { id: 'assignee', label: '执行人', value: task.assignee_actor_id },
                  { id: 'created-by', label: '创建者', value: task.created_by },
                  { id: 'updated-at', label: '更新时间', value: formatDateTime(task.updated_at) },
                ]}
              />
            </InspectorSection>
            <InspectorSection title="标签规范" titleId="annotation-workbench-schema">
              <MetadataList
                ariaLabel="标签规范事实"
                items={[
                  {
                    id: 'schema-name',
                    label: '规范',
                    value: schema ? `${schema.schema_key} · ${schema.schema_version}` : '读取中',
                  },
                  {
                    id: 'schema-digest',
                    label: '摘要',
                    value: <code>{shortId(task.label_schema_digest)}</code>,
                  },
                ]}
              />
              <details className="annotation-label-guide">
                <summary>查看标签说明</summary>
                <ul>
                  {sortedLabels.map((label) => (
                    <li key={label.label_id}>
                      <strong>{label.display_name}</strong>
                      <span>{label.description}</span>
                    </li>
                  ))}
                </ul>
              </details>
            </InspectorSection>
            <InspectorSection title="修订与复核" titleId="annotation-workbench-review">
              {revision ? (
                <MetadataList
                  ariaLabel="修订与复核事实"
                  items={[
                    {
                      id: 'revision',
                      label: '当前修订',
                      value: `第 ${revision.revision_number} 次 · ${revision.annotation_document.segments.length} 个时间段`,
                    },
                    { id: 'submitted-by', label: '提交人', value: revision.submitted_by },
                    {
                      id: 'submitted-at',
                      label: '提交时间',
                      value: formatDateTime(revision.submitted_at),
                    },
                    {
                      id: 'review',
                      label: '复核状态',
                      value: revision.review ? (
                        <StatusIndicator tone={reviewStatusTone(revision.review.decision)}>
                          {revision.review.decision === 'APPROVED' ? '已通过' : '已退回'}
                        </StatusIndicator>
                      ) : (
                        <StatusIndicator tone="information">待复核</StatusIndicator>
                      ),
                    },
                  ]}
                />
              ) : (
                <p className="form-hint">尚未形成正式人工修订。</p>
              )}
              {review?.review_note ? (
                <Alert type="warning" showIcon title="复核意见" description={review.review_note} />
              ) : null}
              {canReview ? (
                <div className="annotation-workbench-review-actions" aria-label="管理员复核操作">
                  <Button
                    type="primary"
                    loading={reviewingRevisionId === revision?.annotation_revision_id}
                    onClick={() => reviewRevision('APPROVED')}
                  >
                    通过复核
                  </Button>
                  <Button
                    type="link"
                    danger
                    disabled={reviewingRevisionId !== null}
                    onClick={() => reviewRevision('REJECTED')}
                  >
                    退回修改
                  </Button>
                </div>
              ) : null}
            </InspectorSection>
            <InspectorSection title="清洗事实" titleId="annotation-workbench-cleaning">
              {revision?.post_cleaning_result ? (
                <>
                  <StatusBadge tone={cleaningStatusTone(revision.post_cleaning_result.outcome)}>
                    {cleaningStatusLabels[revision.post_cleaning_result.outcome]}
                  </StatusBadge>
                  <p className="form-hint">
                    核对时间：{formatDateTime(revision.post_cleaning_result.evaluated_at)}
                  </p>
                  {revision.post_cleaning_result.reason_codes.length ? (
                    <p className="form-hint">
                      原因：{revision.post_cleaning_result.reason_codes.join('；')}
                    </p>
                  ) : null}
                </>
              ) : (
                <p className="form-hint">
                  {review?.decision === 'APPROVED'
                    ? '人工复核已通过，等待清洗结果。'
                    : '人工复核通过后生成清洗结果。'}
                </p>
              )}
              {canRecheck ? (
                <Button
                  type="link"
                  loading={recheckingRevisionId === revision?.annotation_revision_id}
                  onClick={recheckCleaning}
                >
                  重新核对清洗
                </Button>
              ) : null}
            </InspectorSection>
            <InspectorSection title="视频事实" titleId="annotation-workbench-video-facts">
              <MetadataList
                ariaLabel="视频事实"
                items={[
                  {
                    id: 'media-status',
                    label: '媒体状态',
                    value: assetStatusLabels[detail.status],
                  },
                  { id: 'content-type', label: '内容类型', value: detail.content_type },
                  { id: 'size', label: '文件大小', value: formatBytes(detail.size_bytes) },
                  {
                    id: 'received-at',
                    label: '接收时间',
                    value: formatDateTime(detail.created_at),
                  },
                ]}
              />
            </InspectorSection>
          </aside>
        </div>
      ) : null}
    </>
  )
}
