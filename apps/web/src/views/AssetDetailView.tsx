import { Alert, App as AntdApp, Button, Card, Tag } from 'antd'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'

import { AnnotationTaskApiError, createAnnotationTask, type AnnotationTask } from '@/api/annotation'
import {
  authorizeClipAssetPlayback,
  CatalogApiError,
  deleteClipAsset,
  getClipAsset,
  getClipAssetMediaReport,
  recoverCurrentClipAssetMediaProbeDelivery,
  requestHandPoseCandidates,
  requestClipAssetMediaProbe,
  type ClipAssetPlaybackGrant,
  type ClipAssetRead,
  type MediaReport,
  type HandPoseJob,
} from '@/api/catalog'
import {
  assignClipAssetFolder,
  getProject,
  listAssetFolders,
  listProjectMemberships,
  unclassifyClipAsset,
  type AssetFolder,
  type ProjectMembership,
  type ProjectRole,
} from '@/api/projects'
import {
  getQualityReview,
  QualityReviewApiError,
  recordQualityReview,
  type QualityReview,
  type QualityReviewCommandDecision,
  type QualityReviewReasonCode,
} from '@/api/quality'
import { paths } from '@/app/paths'
import { assetStatusLabels, statusTone } from '@/catalog-presentation'
import { AssetActivityCard } from '@/components/AssetActivityCard'
import {
  AnnotationTaskCard,
  AssetFacts,
  ClassificationCard,
  DangerZoneCard,
  MediaReportCard,
  PlaybackCard,
  type PlaybackState,
  QualityReviewCard,
} from '@/components/AssetDetailSections'
import { usePromptDialog } from '@/components/PromptDialog'

export default function AssetDetailView() {
  const { projectId = '', id: assetId = '' } = useParams()
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
  const [folders, setFolders] = useState<AssetFolder[]>([])
  const [selectedFolderId, setSelectedFolderId] = useState('')
  const [isClassifying, setIsClassifying] = useState(false)
  const [isDeleting, setIsDeleting] = useState(false)
  const [playbackGrant, setPlaybackGrant] = useState<ClipAssetPlaybackGrant | null>(null)
  const [playbackState, setPlaybackState] = useState<PlaybackState>('idle')
  const [playbackMessage, setPlaybackMessage] = useState('')
  const [isRequestingPlayback, setIsRequestingPlayback] = useState(false)
  const [isRequestingMediaProbe, setIsRequestingMediaProbe] = useState(false)
  const [isRecoveringMediaProbe, setIsRecoveringMediaProbe] = useState(false)
  const [mediaProbeMessage, setMediaProbeMessage] = useState('')
  const [mediaProbeSubmitted, setMediaProbeSubmitted] = useState(false)
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
  const [annotationAssignees, setAnnotationAssignees] = useState<ProjectMembership[]>([])
  const [selectedAnnotationAssignee, setSelectedAnnotationAssignee] = useState('')
  const [createdAnnotationTask, setCreatedAnnotationTask] = useState<AnnotationTask | null>(null)
  const [isCreatingAnnotationTask, setIsCreatingAnnotationTask] = useState(false)
  const [handPoseJob, setHandPoseJob] = useState<HandPoseJob | null>(null)
  const [isRequestingHandPose, setIsRequestingHandPose] = useState(false)
  const detailRequest = useRef<AbortController | null>(null)
  const roleRequest = useRef<AbortController | null>(null)
  const qualityRequest = useRef<AbortController | null>(null)
  const mediaReportRequest = useRef<AbortController | null>(null)
  const playbackExpiryTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const mediaProbeRecoveryIntent = useRef<{
    changeReference: string
    idempotencyKey: string
  } | null>(null)

  const canDelete = projectRole === 'ADMIN'
  const canPlay = projectRole !== null && detail?.content_type === 'video/mp4'
  const canRequestMediaProbe =
    projectRole !== null && detail?.status === 'UPLOADED' && !mediaProbeSubmitted
  const canRecoverMissingMediaProbe = projectRole === 'ADMIN' && detail?.status === 'UPLOADED'
  const canReviewQuality = projectRole !== null && detail?.status === 'INGESTED'
  const canCreateAnnotationTask =
    projectRole === 'ADMIN' &&
    detail?.status === 'INGESTED' &&
    qualityReview?.decision === 'QUALIFIED' &&
    annotationAssignees.length > 0
  const canRequestHandPose =
    projectRole !== null && detail?.status === 'INGESTED' && handPoseJob === null
  const classificationChanged = selectedFolderId !== (detail?.folder_id ?? '')
  const playbackAvailability = useMemo(() => {
    if (!detail) return null
    if (canPlay)
      return {
        title: '可以直接查看视频',
        description:
          '视频已完成上传完整性校验。点击播放后获取短期只读地址；媒体检测不影响人工查看，也不会自动改变质量结论。',
        type: 'success' as const,
      }
    if (mediaProbeSubmitted)
      return {
        title: '媒体检测已提交',
        description:
          '任务正在队列中等待 Worker 执行。你仍可直接播放视频；检测结果用于补充时长、编码和完整解码等技术事实。',
        type: 'warning' as const,
      }
    if (detail.status === 'UPLOADED')
      return {
        title: '媒体检测尚未开始',
        description:
          '视频已经保存，可直接人工查看。媒体检测用于确认时长、编码和完整解码等技术事实。',
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
          '技术检测未完成，但仍可人工查看并决定是否保留。该视频不能进入标注或训练数据流程。',
        type: 'error' as const,
      }
    return {
      title: '当前视频暂不可播放',
      description: '请确认当前账号已加入项目，并且视频为平台支持的 MP4 格式。',
      type: 'info' as const,
    }
  }, [canPlay, detail, mediaProbeSubmitted])

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
  const loadDetail = useCallback(async () => {
    detailRequest.current?.abort()
    qualityRequest.current?.abort()
    const request = new AbortController()
    detailRequest.current = request
    setLoadState('loading')
    try {
      const response = await getClipAsset(projectId, assetId, request.signal)
      if (detailRequest.current !== request) return
      setDetail(response)
      resetPlayback()
      setCreatedAnnotationTask(null)
      setHandPoseJob(null)
      setSelectedFolderId(response.folder_id ?? '')
      setLoadState('success')
      if (response.status === 'INGESTED') {
        void loadQualityReview()
        void loadMediaReport()
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
  }, [assetId, loadMediaReport, loadQualityReview, projectId, resetMediaReport, resetPlayback])
  const loadProjectRole = useCallback(async () => {
    roleRequest.current?.abort()
    const request = new AbortController()
    roleRequest.current = request
    setProjectRole(null)
    try {
      const project = await getProject(projectId, request.signal)
      if (roleRequest.current !== request) return
      setProjectRole(project.current_role)
      if (project.current_role !== 'ADMIN') {
        setFolders([])
        setAnnotationAssignees([])
        setSelectedAnnotationAssignee('')
        return
      }
      const [folderResult, membershipResult] = await Promise.allSettled([
        listAssetFolders(projectId, request.signal),
        listProjectMemberships(projectId, request.signal),
      ])
      if (request.signal.aborted || roleRequest.current !== request) return
      setFolders(folderResult.status === 'fulfilled' ? folderResult.value : [])
      const memberships = membershipResult.status === 'fulfilled' ? membershipResult.value : []
      setAnnotationAssignees(memberships)
      setSelectedAnnotationAssignee((current) =>
        memberships.some((membership) => membership.actor_id === current)
          ? current
          : (memberships[0]?.actor_id ?? ''),
      )
    } catch {
      if (!request.signal.aborted && roleRequest.current === request) setProjectRole(null)
    } finally {
      if (roleRequest.current === request) roleRequest.current = null
    }
  }, [projectId])

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setMediaProbeSubmitted(false)
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
      clearPlaybackExpiryTimer()
    }
  }, [clearPlaybackExpiryTimer, loadDetail, loadProjectRole])

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
      await navigate(paths.assets(projectId), { replace: true })
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
  async function updateClassification() {
    if (!detail || !canDelete || !classificationChanged || isClassifying) return
    setIsClassifying(true)
    setActionErrorCode('')
    setActionErrorMessage('')
    try {
      if (selectedFolderId) {
        await assignClipAssetFolder(projectId, assetId, selectedFolderId)
        void message.success('视频已归类到所选文件夹。')
      } else {
        await unclassifyClipAsset(projectId, assetId)
        void message.success('视频已取消分类。')
      }
      await loadDetail()
    } catch (error) {
      setActionErrorCode(
        error instanceof CatalogApiError ? error.errorCode : 'CLIP_ASSET_CLASSIFICATION_FAILED',
      )
      setActionErrorMessage(
        error instanceof CatalogApiError ? error.message : '视频分类未更新，请稍后重试。',
      )
    } finally {
      setIsClassifying(false)
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
  async function requestMediaProbe() {
    if (!canRequestMediaProbe || isRequestingMediaProbe) return
    setIsRequestingMediaProbe(true)
    setActionErrorCode('')
    setActionErrorMessage('')
    setMediaProbeMessage('')
    try {
      const job = await requestClipAssetMediaProbe(projectId, assetId)
      setMediaProbeSubmitted(true)
      setMediaProbeMessage(`媒体检测已排队（任务 ${job.media_job_id}）。请稍后刷新状态。`)
    } catch (error) {
      setActionErrorCode(
        error instanceof CatalogApiError ? error.errorCode : 'MEDIA_PROBE_REQUEST_FAILED',
      )
      setActionErrorMessage(
        error instanceof CatalogApiError ? error.message : '媒体检测暂未提交，请稍后重试。',
      )
    } finally {
      setIsRequestingMediaProbe(false)
    }
  }
  async function recoverMissingMediaProbe() {
    if (!canRecoverMissingMediaProbe || isRecoveringMediaProbe) return
    const changeReference = await prompt({
      title: '恢复已丢失的检测任务',
      description:
        '仅在 RabbitMQ 已确认当前检测任务的原消息不存在时继续。平台会结束旧尝试，并创建新的受审计尝试；不会自动重投或复用旧租约。请输入非敏感的事件或变更编号。',
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
      setMediaProbeSubmitted(true)
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
      setQualityReview(
        await recordQualityReview(projectId, assetId, {
          decision: selectedQualityDecision,
          reason_code: selectedQualityReason,
          expected_version: qualityReview.version,
        }),
      )
      void message.success('质量结论已保存。')
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
  async function createTask() {
    if (!canCreateAnnotationTask || !selectedAnnotationAssignee || isCreatingAnnotationTask) return
    setIsCreatingAnnotationTask(true)
    setActionErrorCode('')
    setActionErrorMessage('')
    try {
      setCreatedAnnotationTask(
        await createAnnotationTask(projectId, assetId, selectedAnnotationAssignee),
      )
      void message.success('标注任务已创建并分配。')
    } catch (error) {
      setActionErrorCode(
        error instanceof AnnotationTaskApiError
          ? error.errorCode
          : 'ANNOTATION_TASK_REQUEST_FAILED',
      )
      setActionErrorMessage(
        error instanceof AnnotationTaskApiError ? error.message : '标注任务暂未创建，请稍后重试。',
      )
    } finally {
      setIsCreatingAnnotationTask(false)
    }
  }
  async function requestHandPose() {
    if (!canRequestHandPose || isRequestingHandPose) return
    setIsRequestingHandPose(true)
    setActionErrorCode('')
    setActionErrorMessage('')
    try {
      const job = await requestHandPoseCandidates(projectId, assetId)
      setHandPoseJob(job)
      void message.success('手部姿态候选已排队。')
    } catch (error) {
      setActionErrorCode(
        error instanceof CatalogApiError ? error.errorCode : 'HAND_POSE_REQUEST_FAILED',
      )
      setActionErrorMessage(
        error instanceof CatalogApiError ? error.message : '手部姿态候选暂未提交，请稍后重试。',
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

  return (
    <>
      <nav className="breadcrumb" aria-label="面包屑">
        <Link to={paths.assets(projectId)}>视频资产</Link>
        <span aria-hidden="true">/</span>
        <span>详情</span>
      </nav>
      {loadState === 'loading' ? (
        <div className="catalog-notice" role="status">
          正在读取视频资产详情…
        </div>
      ) : null}
      {loadState === 'error' ? (
        <div className="catalog-error" role="alert">
          <strong>{errorCode}</strong>
          <span>{errorMessage}</span>
          <Button onClick={() => void loadDetail()}>重试</Button>
        </div>
      ) : null}
      {loadState === 'success' && detail ? (
        <>
          <section className="page-heading detail-heading" aria-labelledby="asset-detail-title">
            <div>
              <p className="app-eyebrow">单路相机视频资产</p>
              <h1 id="asset-detail-title">视频详情</h1>
              <p className="asset-identifiers">
                <span>
                  客户端文件标识 <code>{detail.client_file_id}</code>
                </span>
                <span>
                  资产 ID <code>{detail.clip_asset_id}</code>
                </span>
              </p>
            </div>
            <div className="detail-actions">
              <Tag color={statusTone(detail.status)}>{assetStatusLabels[detail.status]}</Tag>
              <Button
                onClick={() => {
                  setMediaProbeSubmitted(false)
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
          <PlaybackCard
            availability={playbackAvailability}
            canPlay={canPlay}
            canRecoverMissingMediaProbe={canRecoverMissingMediaProbe}
            canRequestMediaProbe={canRequestMediaProbe}
            grant={playbackGrant}
            isRecoveringMediaProbe={isRecoveringMediaProbe}
            isRequestingMediaProbe={isRequestingMediaProbe}
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
            onRequestMediaProbe={() => void requestMediaProbe()}
            onStartPlayback={() => void startPlayback()}
          />
          <MediaReportCard
            detail={detail}
            report={mediaReport}
            loadState={mediaReportLoadState}
            errorCode={mediaReportErrorCode}
            errorMessage={mediaReportErrorMessage}
          />
          {canRequestHandPose || handPoseJob ? (
            <Card
              className="detail-card"
              title={<strong id="hand-pose-title">手部姿态候选</strong>}
              variant="outlined"
              aria-labelledby="hand-pose-title"
            >
              <p className="app-eyebrow">Ego 手部姿态</p>
              <p>
                使用已批准的离线模型为此视频生成双手关键点候选。结果必须经人工审核，不会自动改变正式标注或训练数据。
              </p>
              {canRequestHandPose ? (
                <Button
                  type="primary"
                  loading={isRequestingHandPose}
                  onClick={() => void requestHandPose()}
                >
                  生成手部姿态候选
                </Button>
              ) : null}
              {handPoseJob ? (
                <Alert
                  className="playback-feedback"
                  type="info"
                  title="候选生成已排队"
                  description={
                    <>
                      任务 <code>{handPoseJob.ai_job_id}</code> 已提交。请稍后刷新；候选审核功能将在
                      Worker 回传后启用。
                    </>
                  }
                  showIcon
                />
              ) : null}
            </Card>
          ) : null}
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
          {canCreateAnnotationTask ? (
            <AnnotationTaskCard
              assignees={annotationAssignees}
              createdTask={createdAnnotationTask}
              isCreating={isCreatingAnnotationTask}
              projectId={projectId}
              selectedAssignee={selectedAnnotationAssignee}
              onCreate={() => void createTask()}
              onSelectedAssigneeChange={setSelectedAnnotationAssignee}
            />
          ) : null}
          {canDelete ? (
            <ClassificationCard
              changed={classificationChanged}
              folders={folders}
              isSaving={isClassifying}
              selectedFolderId={selectedFolderId}
              onSave={() => void updateClassification()}
              onSelectedFolderChange={setSelectedFolderId}
            />
          ) : null}
          <AssetFacts detail={detail} projectId={projectId} />
          <AssetActivityCard key={assetId} assetId={assetId} projectId={projectId} />
          {canDelete ? (
            <DangerZoneCard isDeleting={isDeleting} onDelete={() => void confirmDelete()} />
          ) : null}
        </>
      ) : null}
    </>
  )
}
