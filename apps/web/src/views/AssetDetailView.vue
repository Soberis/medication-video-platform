<script setup lang="ts">
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDescriptions,
  ElDescriptionsItem,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElSelect,
  ElTag,
} from 'element-plus'
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  authorizeClipAssetPlayback,
  CatalogApiError,
  deleteClipAsset,
  getClipAsset,
  getClipAssetMediaReport,
  requestClipAssetMediaProbe,
  type ClipAssetRead,
  type ClipAssetPlaybackGrant,
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
  AnnotationTaskApiError,
  createAnnotationTask,
  type AnnotationTask,
} from '@/api/annotation'
import {
  getQualityReview,
  qualityDecisionLabels,
  qualityReasonLabels,
  QualityReviewApiError,
  recordQualityReview,
  type QualityReview,
  type QualityReviewCommandDecision,
  type QualityReviewReasonCode,
} from '@/api/quality'
import {
  assetStatusLabels,
  declaredSyncStatusLabels,
  formatBytes,
  formatDateTime,
  qualityStatusLabels,
  statusTone,
  streamStatusLabels,
} from '@/catalog-presentation'
defineOptions({ name: 'AssetDetailView' })

const route = useRoute()
const router = useRouter()
const projectId = computed(() => String(route.params.projectId ?? ''))
const assetId = computed(() => String(route.params.id ?? ''))
const detail = shallowRef<ClipAssetRead | null>(null)
const loadState = ref<'loading' | 'success' | 'error'>('loading')
const errorCode = ref('')
const errorMessage = ref('')
const actionErrorCode = ref('')
const actionErrorMessage = ref('')
const isDeleting = ref(false)
const projectRole = ref<ProjectRole | null>(null)
const folders = shallowRef<AssetFolder[]>([])
const selectedFolderId = ref('')
const isClassifying = ref(false)
const isRequestingPlayback = ref(false)
const isRequestingMediaProbe = ref(false)
const mediaProbeMessage = ref('')
const mediaProbeSubmitted = ref(false)
const mediaReport = shallowRef<Awaited<ReturnType<typeof getClipAssetMediaReport>> | null>(null)
const mediaReportLoadState = ref<'idle' | 'loading' | 'success' | 'error'>('idle')
const mediaReportErrorCode = ref('')
const mediaReportErrorMessage = ref('')
const playbackGrant = shallowRef<ClipAssetPlaybackGrant | null>(null)
const playbackState = ref<'idle' | 'waiting' | 'ready' | 'error' | 'expired'>('idle')
const playbackMessage = ref('')
const qualityReview = shallowRef<QualityReview | null>(null)
const qualityLoadState = ref<'loading' | 'success' | 'error'>('loading')
const qualityErrorCode = ref('')
const qualityErrorMessage = ref('')
const selectedQualityDecision = ref<QualityReviewCommandDecision>('QUALIFIED')
const selectedQualityReason = ref<QualityReviewReasonCode>('MEETS_REQUIREMENTS')
const isSavingQuality = ref(false)
const annotationAssignees = shallowRef<ProjectMembership[]>([])
const selectedAnnotationAssignee = ref('')
const createdAnnotationTask = shallowRef<AnnotationTask | null>(null)
const isCreatingAnnotationTask = ref(false)
let activeRequest: AbortController | null = null
let activeRoleRequest: AbortController | null = null
let activeQualityRequest: AbortController | null = null
let activeMediaReportRequest: AbortController | null = null
let playbackExpiryTimer: ReturnType<typeof setTimeout> | null = null

const canDelete = computed(() => projectRole.value === 'ADMIN')
const canPlay = computed(
  () =>
    projectRole.value !== null &&
    detail.value?.content_type === 'video/mp4',
)
const canRequestMediaProbe = computed(
  () => projectRole.value !== null && detail.value?.status === 'UPLOADED' && !mediaProbeSubmitted.value,
)
const canReviewQuality = computed(() => projectRole.value !== null && detail.value?.status === 'INGESTED')
const canCreateAnnotationTask = computed(
  () =>
    projectRole.value === 'ADMIN' &&
    detail.value?.status === 'INGESTED' &&
    qualityReview.value?.decision === 'QUALIFIED' &&
    annotationAssignees.value.length > 0,
)
const reasonsByDecision: Record<QualityReviewCommandDecision, QualityReviewReasonCode[]> = {
  QUALIFIED: ['MEETS_REQUIREMENTS'],
  REJECTED: [
    'SENSITIVE_CONTENT',
    'BLURRY_OR_OUT_OF_FOCUS',
    'BLACK_OR_CORRUPTED_FRAMES',
    'SEVERE_OCCLUSION',
    'INCOMPLETE_ACTION',
    'WRONG_SCENE',
    'DUPLICATE',
    'OTHER_QUALITY_FAILURE',
  ],
  NEEDS_REVIEW: ['UNCERTAIN_QUALITY', 'METADATA_MISMATCH'],
}
const qualityReasonOptions = computed(() => reasonsByDecision[selectedQualityDecision.value])
const qualityTagType = computed(() => {
  if (qualityReview.value?.decision === 'QUALIFIED') return 'success' as const
  if (qualityReview.value?.decision === 'REJECTED') return 'danger' as const
  if (qualityReview.value?.decision === 'NEEDS_REVIEW') return 'warning' as const
  return 'info' as const
})
const mediaReportWarnings = computed(() => mediaReport.value?.warning_codes ?? [])
const classificationChanged = computed(() => selectedFolderId.value !== (detail.value?.folder_id ?? ''))
const playbackAvailability = computed(() => {
  if (!detail.value) return null
  if (canPlay.value) {
    return {
      title: '可以直接查看视频',
      description: '视频已完成上传完整性校验。点击播放后获取短期只读地址；媒体检测不影响人工查看，也不会自动改变质量结论。',
      type: 'success' as const,
    }
  }
  if (mediaProbeSubmitted.value) {
    return {
      title: '媒体检测已提交',
      description: '任务正在队列中等待 Worker 执行。你仍可直接播放视频；检测结果用于补充时长、编码和完整解码等技术事实。',
      type: 'warning' as const,
    }
  }
  if (detail.value.status === 'UPLOADED') {
    return {
      title: '媒体检测尚未开始',
      description: '视频已经保存，可直接人工查看。媒体检测用于确认时长、编码和完整解码等技术事实。',
      type: 'info' as const,
    }
  }
  if (detail.value.status === 'INGESTING') {
    return {
      title: '正在进行媒体检测',
      description: '平台正在检查视频是否可读取和完整解码；这不影响你直接人工查看。',
      type: 'warning' as const,
    }
  }
  if (detail.value.status === 'INGEST_FAILED') {
    return {
      title: '媒体检测未通过',
      description: '技术检测未完成，但仍可人工查看并决定是否保留。该视频不能进入标注或训练数据流程。',
      type: 'error' as const,
    }
  }
  return {
    title: '当前视频暂不可播放',
    description: '请确认当前账号已加入项目，并且视频为平台支持的 MP4 格式。',
    type: 'info' as const,
  }
})

function clearPlaybackExpiryTimer() {
  if (playbackExpiryTimer !== null) {
    clearTimeout(playbackExpiryTimer)
    playbackExpiryTimer = null
  }
}

function resetPlayback() {
  clearPlaybackExpiryTimer()
  playbackGrant.value = null
  playbackState.value = 'idle'
  playbackMessage.value = ''
}

function resetMediaReport() {
  activeMediaReportRequest?.abort()
  activeMediaReportRequest = null
  mediaReport.value = null
  mediaReportLoadState.value = 'idle'
  mediaReportErrorCode.value = ''
  mediaReportErrorMessage.value = ''
}

function formatMediaDuration(value: number) {
  const seconds = Math.floor(value / 1_000_000)
  const minutes = Math.floor(seconds / 60)
  return `${minutes}:${String(seconds % 60).padStart(2, '0')}`
}

function formatFrameRate(numerator: number | null, denominator: number | null) {
  if (!numerator || !denominator) return '未提供'
  return `${new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(numerator / denominator)} fps`
}

function formatBitrate(value: number | null) {
  if (!value) return '未提供'
  return `${new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 1 }).format(value / 1_000_000)} Mbps`
}

function mediaWarningLabel(code: string) {
  const labels: Record<string, string> = {
    PTS_MISSING: '时间戳缺失',
    PTS_NON_MONOTONIC: '时间戳不连续',
    FRAME_COUNT_UNAVAILABLE: '无法统计帧数',
    DURATION_MISMATCH: '时长存在差异',
    VFR_DETECTED: '可变帧率',
    AUDIO_PRESENT: '包含音轨',
  }
  return labels[code] ?? code
}

function schedulePlaybackExpiry(expiresAt: string) {
  clearPlaybackExpiryTimer()
  const remainingMilliseconds = new Date(expiresAt).getTime() - Date.now()
  if (!Number.isFinite(remainingMilliseconds) || remainingMilliseconds <= 0) {
    expirePlayback()
    return
  }
  playbackExpiryTimer = setTimeout(expirePlayback, remainingMilliseconds)
}

function expirePlayback() {
  clearPlaybackExpiryTimer()
  playbackState.value = 'expired'
  playbackMessage.value = '本次播放授权已到期，请重新获取后继续审核。'
}

async function loadDetail() {
  activeRequest?.abort()
  activeQualityRequest?.abort()
  const request = new AbortController()
  activeRequest = request
  loadState.value = 'loading'
  try {
    detail.value = await getClipAsset(projectId.value, assetId.value, request.signal)
    if (activeRequest === request) {
      resetPlayback()
      createdAnnotationTask.value = null
      selectedFolderId.value = detail.value.folder_id ?? ''
      loadState.value = 'success'
      if (detail.value.status === 'INGESTED') {
        void loadQualityReview()
        void loadMediaReport()
      } else {
        resetMediaReport()
        qualityReview.value = {
          quality_review_id: null,
          project_id: projectId.value,
          clip_asset_id: assetId.value,
          decision: 'UNREVIEWED',
          reason_code: null,
          reviewed_by: null,
          reviewed_at: null,
          version: 0,
        }
        qualityLoadState.value = 'success'
      }
    }
  } catch (error) {
    if (request.signal.aborted || activeRequest !== request) return
    detail.value = null
    loadState.value = 'error'
    errorCode.value = error instanceof CatalogApiError ? error.errorCode : 'CATALOG_REQUEST_FAILED'
    errorMessage.value =
      error instanceof CatalogApiError ? error.message : '暂时无法读取视频资产详情，请稍后重试。'
  } finally {
    if (activeRequest === request) activeRequest = null
  }
}

async function loadMediaReport() {
  activeMediaReportRequest?.abort()
  const request = new AbortController()
  activeMediaReportRequest = request
  mediaReportLoadState.value = 'loading'
  mediaReportErrorCode.value = ''
  mediaReportErrorMessage.value = ''
  try {
    mediaReport.value = await getClipAssetMediaReport(projectId.value, assetId.value, request.signal)
    if (activeMediaReportRequest !== request) return
    mediaReportLoadState.value = 'success'
  } catch (error) {
    if (request.signal.aborted || activeMediaReportRequest !== request) return
    mediaReport.value = null
    mediaReportLoadState.value = 'error'
    mediaReportErrorCode.value = error instanceof CatalogApiError ? error.errorCode : 'MEDIA_REPORT_REQUEST_FAILED'
    mediaReportErrorMessage.value =
      error instanceof CatalogApiError ? error.message : '暂时无法读取媒体检测报告，请稍后重试。'
  } finally {
    if (activeMediaReportRequest === request) activeMediaReportRequest = null
  }
}

async function loadProjectRole() {
  activeRoleRequest?.abort()
  const request = new AbortController()
  activeRoleRequest = request
  projectRole.value = null
  try {
    const project = await getProject(projectId.value, request.signal)
    if (activeRoleRequest !== request) return
    projectRole.value = project.current_role
    if (project.current_role !== 'ADMIN') {
      folders.value = []
      annotationAssignees.value = []
      selectedAnnotationAssignee.value = ''
      return
    }
    try {
      folders.value = await listAssetFolders(projectId.value, request.signal)
    } catch {
      if (!request.signal.aborted && activeRoleRequest === request) folders.value = []
    }
    try {
      const memberships = await listProjectMemberships(projectId.value, request.signal)
      if (request.signal.aborted || activeRoleRequest !== request) return
      annotationAssignees.value = memberships
      if (!memberships.some((membership) => membership.actor_id === selectedAnnotationAssignee.value)) {
        selectedAnnotationAssignee.value = memberships[0]?.actor_id ?? ''
      }
    } catch {
      if (!request.signal.aborted && activeRoleRequest === request) {
        annotationAssignees.value = []
        selectedAnnotationAssignee.value = ''
      }
    }
  } catch {
    // Fail closed for destructive UI. The API remains the final authorization boundary.
    if (!request.signal.aborted && activeRoleRequest === request) projectRole.value = null
  } finally {
    if (activeRoleRequest === request) activeRoleRequest = null
  }
}

async function loadQualityReview() {
  activeQualityRequest?.abort()
  const request = new AbortController()
  activeQualityRequest = request
  qualityLoadState.value = 'loading'
  qualityErrorCode.value = ''
  qualityErrorMessage.value = ''
  try {
    const result = await getQualityReview(projectId.value, assetId.value, request.signal)
    if (activeQualityRequest !== request) return
    qualityReview.value = result
    if (result.decision !== 'UNREVIEWED' && result.reason_code) {
      selectedQualityDecision.value = result.decision
      selectedQualityReason.value = result.reason_code
    } else {
      selectedQualityDecision.value = 'QUALIFIED'
      selectedQualityReason.value = 'MEETS_REQUIREMENTS'
    }
    qualityLoadState.value = 'success'
  } catch (error) {
    if (request.signal.aborted || activeQualityRequest !== request) return
    qualityReview.value = null
    qualityLoadState.value = 'error'
    qualityErrorCode.value = error instanceof QualityReviewApiError ? error.errorCode : 'QUALITY_REVIEW_FAILED'
    qualityErrorMessage.value =
      error instanceof QualityReviewApiError ? error.message : '暂时无法读取质量结论，请稍后重试。'
  } finally {
    if (activeQualityRequest === request) activeQualityRequest = null
  }
}

function refreshPage() {
  mediaProbeSubmitted.value = false
  void loadDetail()
}

watch(
  [projectId, assetId],
  () => {
    mediaProbeSubmitted.value = false
    mediaProbeMessage.value = ''
    void loadDetail()
    void loadProjectRole()
  },
  { immediate: true },
)
onUnmounted(() => {
  activeRequest?.abort()
  activeRoleRequest?.abort()
  activeQualityRequest?.abort()
  activeMediaReportRequest?.abort()
  clearPlaybackExpiryTimer()
})

async function confirmDelete() {
  if (!detail.value || isDeleting.value) return
  actionErrorCode.value = ''
  actionErrorMessage.value = ''
  try {
    const { value } = await ElMessageBox.prompt(
      `将从项目中隐藏视频 ${detail.value.client_file_id}。不会立即物理删除对象或影响其他视频；请输入删除原因。`,
      '确认删除视频',
      {
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger',
        inputPattern: /\S/,
        inputErrorMessage: '请填写删除原因。',
        inputPlaceholder: '例如：上传错误、无用或质量不合格',
      },
    )
    isDeleting.value = true
    await deleteClipAsset(projectId.value, assetId.value, value.trim())
    ElMessage.success('视频已删除，并已从普通视频列表中隐藏。')
    await router.replace({ name: 'asset-list', params: { projectId: projectId.value } })
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    actionErrorCode.value = error instanceof CatalogApiError ? error.errorCode : 'CLIP_ASSET_DELETE_FAILED'
    actionErrorMessage.value = error instanceof CatalogApiError ? error.message : '视频删除未完成，请稍后重试。'
  } finally {
    isDeleting.value = false
  }
}

async function updateClassification() {
  if (!detail.value || !canDelete.value || !classificationChanged.value || isClassifying.value) return
  isClassifying.value = true
  actionErrorCode.value = ''
  actionErrorMessage.value = ''
  try {
    if (selectedFolderId.value) {
      await assignClipAssetFolder(projectId.value, assetId.value, selectedFolderId.value)
      ElMessage.success('视频已归类到所选文件夹。')
    } else {
      await unclassifyClipAsset(projectId.value, assetId.value)
      ElMessage.success('视频已取消分类。')
    }
    await loadDetail()
  } catch (error) {
    actionErrorCode.value = error instanceof CatalogApiError ? error.errorCode : 'CLIP_ASSET_CLASSIFICATION_FAILED'
    actionErrorMessage.value = error instanceof CatalogApiError ? error.message : '视频分类未更新，请稍后重试。'
  } finally {
    isClassifying.value = false
  }
}

async function startPlayback() {
  if (!canPlay.value || isRequestingPlayback.value) return
  resetPlayback()
  isRequestingPlayback.value = true
  actionErrorCode.value = ''
  actionErrorMessage.value = ''
  try {
    playbackGrant.value = await authorizeClipAssetPlayback(projectId.value, assetId.value)
    playbackState.value = 'waiting'
    schedulePlaybackExpiry(playbackGrant.value.expires_at)
  } catch (error) {
    playbackGrant.value = null
    actionErrorCode.value = error instanceof CatalogApiError ? error.errorCode : 'PLAYBACK_FAILED'
    actionErrorMessage.value = error instanceof CatalogApiError ? error.message : '无法获取播放地址，请稍后重试。'
  } finally {
    isRequestingPlayback.value = false
  }
}

async function requestMediaProbe() {
  if (!canRequestMediaProbe.value || isRequestingMediaProbe.value) return
  isRequestingMediaProbe.value = true
  actionErrorCode.value = ''
  actionErrorMessage.value = ''
  mediaProbeMessage.value = ''
  try {
    const job = await requestClipAssetMediaProbe(projectId.value, assetId.value)
    mediaProbeSubmitted.value = true
    mediaProbeMessage.value = `媒体检测已排队（任务 ${job.media_job_id}）。请稍后刷新状态。`
  } catch (error) {
    actionErrorCode.value = error instanceof CatalogApiError ? error.errorCode : 'MEDIA_PROBE_REQUEST_FAILED'
    actionErrorMessage.value = error instanceof CatalogApiError ? error.message : '媒体检测暂未提交，请稍后重试。'
  } finally {
    isRequestingMediaProbe.value = false
  }
}

async function saveQualityReview() {
  if (!qualityReview.value || !canReviewQuality.value || isSavingQuality.value) return
  isSavingQuality.value = true
  qualityErrorCode.value = ''
  qualityErrorMessage.value = ''
  try {
    qualityReview.value = await recordQualityReview(projectId.value, assetId.value, {
      decision: selectedQualityDecision.value,
      reason_code: selectedQualityReason.value,
      expected_version: qualityReview.value.version,
    })
    ElMessage.success('质量结论已保存。')
  } catch (error) {
    qualityErrorCode.value = error instanceof QualityReviewApiError ? error.errorCode : 'QUALITY_REVIEW_FAILED'
    qualityErrorMessage.value =
      error instanceof QualityReviewApiError ? error.message : '质量结论暂未保存，请稍后重试。'
  } finally {
    isSavingQuality.value = false
  }
}

async function createTask() {
  if (!canCreateAnnotationTask.value || !selectedAnnotationAssignee.value || isCreatingAnnotationTask.value) return
  isCreatingAnnotationTask.value = true
  actionErrorCode.value = ''
  actionErrorMessage.value = ''
  try {
    createdAnnotationTask.value = await createAnnotationTask(
      projectId.value,
      assetId.value,
      selectedAnnotationAssignee.value,
    )
    ElMessage.success('标注任务已创建并分配。')
  } catch (error) {
    actionErrorCode.value = error instanceof AnnotationTaskApiError ? error.errorCode : 'ANNOTATION_TASK_REQUEST_FAILED'
    actionErrorMessage.value =
      error instanceof AnnotationTaskApiError ? error.message : '标注任务暂未创建，请稍后重试。'
  } finally {
    isCreatingAnnotationTask.value = false
  }
}

watch(selectedQualityDecision, (decision) => {
  const availableReasons = reasonsByDecision[decision]
  if (!availableReasons.includes(selectedQualityReason.value)) {
    const firstReason = availableReasons[0]
    if (firstReason) selectedQualityReason.value = firstReason
  }
})

function closePlayback() {
  resetPlayback()
}

function handlePlaybackReady() {
  if (playbackState.value !== 'expired') {
    playbackState.value = 'ready'
    playbackMessage.value = ''
  }
}

function handlePlaybackError() {
  if (!playbackGrant.value) return
  const expiresAt = new Date(playbackGrant.value.expires_at).getTime()
  if (Number.isFinite(expiresAt) && expiresAt <= Date.now()) {
    expirePlayback()
    return
  }
  playbackState.value = 'error'
  playbackMessage.value = '视频加载失败。请检查对象存储连接，或重新获取播放授权后再试。'
}

</script>

<template>
  <nav class="breadcrumb" aria-label="面包屑">
    <RouterLink :to="{ name: 'asset-list', params: { projectId } }">视频资产</RouterLink>
    <span aria-hidden="true">/</span>
    <span>详情</span>
  </nav>

  <div v-if="loadState === 'loading'" class="catalog-notice" role="status">正在读取视频资产详情…</div>
  <div v-else-if="loadState === 'error'" class="catalog-error" role="alert">
    <strong>{{ errorCode }}</strong>
    <span>{{ errorMessage }}</span>
    <ElButton @click="loadDetail">重试</ElButton>
  </div>

  <template v-else-if="detail">
    <section class="page-heading detail-heading" aria-labelledby="asset-detail-title">
      <div>
        <p class="app-eyebrow">单路相机视频资产</p>
        <h1 id="asset-detail-title">视频详情</h1>
        <p class="asset-identifiers">
          <span>客户端文件标识 <code>{{ detail.client_file_id }}</code></span>
          <span>资产 ID <code>{{ detail.clip_asset_id }}</code></span>
        </p>
      </div>
      <div class="detail-actions">
        <ElTag :type="statusTone(detail.status)">{{ assetStatusLabels[detail.status] }}</ElTag>
        <ElButton @click="refreshPage">刷新状态</ElButton>
      </div>
    </section>

    <ElAlert
      v-if="actionErrorMessage"
      class="catalog-alert"
      type="error"
      :title="actionErrorMessage"
      :description="actionErrorCode ? `错误码：${actionErrorCode}` : undefined"
      show-icon
      :closable="false"
    />

    <ElCard class="detail-card playback-card" shadow="never">
      <template #header><strong>视频查看</strong></template>
      <ElAlert
        v-if="!playbackGrant && playbackAvailability"
        :title="playbackAvailability.title"
        :description="playbackAvailability.description"
        :type="playbackAvailability.type"
        :closable="false"
        show-icon
      />
      <ElButton
        v-if="canPlay && !playbackGrant"
        class="playback-primary-action"
        type="primary"
        :loading="isRequestingPlayback"
        @click="startPlayback"
      >
        播放视频
      </ElButton>
      <template v-if="playbackGrant">
        <div class="playback-meta">
          <span>授权有效至 {{ formatDateTime(playbackGrant.expires_at) }}</span>
          <ElTag type="success">人工查看</ElTag>
        </div>
        <ElAlert
          v-if="playbackMessage"
          class="playback-feedback"
          :title="playbackMessage"
          :type="playbackState === 'error' || playbackState === 'expired' ? 'error' : 'info'"
          :closable="false"
          show-icon
        />
        <div v-if="playbackState !== 'error' && playbackState !== 'expired'" class="video-stage">
          <video
            class="review-video"
            controls
            preload="none"
            playsinline
            aria-label="视频预览"
            :src="playbackGrant.media_url"
            @loadedmetadata="handlePlaybackReady"
            @error="handlePlaybackError"
          >
            当前浏览器不支持视频播放。
          </video>
        </div>
        <div class="playback-toolbar">
          <ElButton
            v-if="playbackState === 'error' || playbackState === 'expired'"
            type="primary"
            :loading="isRequestingPlayback"
            @click="startPlayback"
          >
            重新获取播放授权
          </ElButton>
          <ElButton plain @click="closePlayback">关闭视频</ElButton>
        </div>
      </template>
      <template v-if="canRequestMediaProbe">
        <div class="playback-toolbar media-probe-toolbar">
          <ElButton plain :loading="isRequestingMediaProbe" @click="requestMediaProbe">检测媒体信息</ElButton>
          <p class="form-hint">补充时长、编码和完整解码结果，不影响直接查看。</p>
        </div>
      </template>
      <p v-if="mediaProbeMessage" class="form-hint media-probe-message">{{ mediaProbeMessage }}</p>
    </ElCard>

    <ElCard class="detail-card" shadow="never">
      <template #header><strong>媒体检测报告</strong></template>
      <div v-if="detail.status === 'INGESTED' && mediaReportLoadState === 'loading'" class="catalog-notice" role="status">
        正在读取媒体检测报告…
      </div>
      <ElAlert
        v-else-if="detail.status === 'INGESTED' && mediaReportLoadState === 'error'"
        type="error"
        :title="mediaReportErrorMessage"
        :description="mediaReportErrorCode ? `错误码：${mediaReportErrorCode}` : undefined"
        :closable="false"
        show-icon
      />
      <template v-else-if="detail.status === 'INGESTED' && mediaReport">
        <div class="media-report-summary">
          <ElTag type="success">完整解码已验证</ElTag>
          <span class="form-hint">检测完成于 {{ formatDateTime(mediaReport.created_at) }}</span>
        </div>
        <ElDescriptions :column="3" border class="media-report-facts">
          <ElDescriptionsItem label="时长">{{ formatMediaDuration(mediaReport.duration_microseconds) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="画面">{{ mediaReport.width }} × {{ mediaReport.height }}</ElDescriptionsItem>
          <ElDescriptionsItem label="编码">
            {{ mediaReport.video_codec }}{{ mediaReport.video_profile ? ` · ${mediaReport.video_profile}` : '' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="帧率">
            {{ formatFrameRate(mediaReport.average_frame_rate_numerator, mediaReport.average_frame_rate_denominator) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="视频码率">{{ formatBitrate(mediaReport.primary_video_bitrate_bps) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="音轨">{{ mediaReport.audio_stream_count ? `${mediaReport.audio_stream_count} 条` : '无' }}</ElDescriptionsItem>
        </ElDescriptions>
        <ElAlert
          v-if="mediaReportWarnings.length"
          class="media-report-warnings"
          type="warning"
          title="检测发现需关注项"
          :description="mediaReportWarnings.map(mediaWarningLabel).join('；')"
          :closable="false"
          show-icon
        />
      </template>
      <ElAlert
        v-else-if="detail.status === 'INGEST_FAILED'"
        type="error"
        title="媒体检测失败"
        description="该视频仍可人工查看，但不能记录质量结论或进入标注流程。"
        :closable="false"
        show-icon
      />
      <ElAlert
        v-else
        type="info"
        :title="detail.status === 'INGESTING' ? '媒体检测进行中' : '媒体检测尚未开始'"
        description="检测完成后将在这里显示时长、分辨率、编码和完整解码结果。"
        :closable="false"
        show-icon
      />
    </ElCard>

    <ElCard class="detail-card" shadow="never">
      <template #header><strong>人工质量判断</strong></template>
      <p class="form-hint">根据实际画面判断该视频是否适合进入标注。播放本身不会自动产生质量结论。</p>
      <div v-if="qualityLoadState === 'loading'" class="catalog-notice" role="status">正在读取质量结论…</div>
      <ElAlert
        v-else-if="qualityLoadState === 'error'"
        type="error"
        :title="qualityErrorMessage"
        :description="qualityErrorCode ? `错误码：${qualityErrorCode}` : undefined"
        :closable="false"
        show-icon
      />
      <template v-else-if="qualityReview">
        <div class="quality-review-summary">
          <ElTag :type="detail.status === 'INGESTED' ? qualityTagType : 'info'">
            {{ detail.status === 'INGESTED' ? qualityDecisionLabels[qualityReview.decision] : '待技术检测' }}
          </ElTag>
          <span v-if="detail.status === 'INGESTED' && qualityReview.reason_code">
            {{ qualityReasonLabels[qualityReview.reason_code] }}
          </span>
          <span
            v-if="detail.status === 'INGESTED' && qualityReview.reviewed_by && qualityReview.reviewed_at"
            class="form-hint"
          >
            {{ qualityReview.reviewed_by }} · {{ formatDateTime(qualityReview.reviewed_at) }}
          </span>
        </div>
        <ElAlert
          v-if="detail.status !== 'INGESTED'"
          title="技术检测完成后才能记录质量结论"
          description="当前视频仍可直接播放并供人工预览；检测只补充时长、编码和完整解码等技术事实。"
          type="info"
          :closable="false"
          show-icon
        />
        <div v-else class="quality-review-actions">
          <ElSelect v-model="selectedQualityDecision" aria-label="质量结论">
            <ElOption label="合格" value="QUALIFIED" />
            <ElOption label="不合格" value="REJECTED" />
            <ElOption label="需复核" value="NEEDS_REVIEW" />
          </ElSelect>
          <ElSelect v-model="selectedQualityReason" aria-label="质量原因">
            <ElOption
              v-for="reason in qualityReasonOptions"
              :key="reason"
              :label="qualityReasonLabels[reason]"
              :value="reason"
            />
          </ElSelect>
          <ElButton type="primary" :loading="isSavingQuality" :disabled="!canReviewQuality" @click="saveQualityReview">
            保存质量结论
          </ElButton>
        </div>
      </template>
    </ElCard>

    <ElCard v-if="canCreateAnnotationTask" class="detail-card" shadow="never">
      <template #header><strong>标注任务</strong></template>
      <template v-if="createdAnnotationTask">
        <ElAlert
          title="标注任务已创建并分配"
          :description="`执行人：${createdAnnotationTask.assignee_actor_id}。请在标注任务页提交时间段标注；管理员可对提交结果进行独立复核。`"
          type="success"
          :closable="false"
          show-icon
        />
        <RouterLink class="text-link" :to="{ name: 'annotation-task-list', params: { projectId } }">
          查看标注任务
        </RouterLink>
      </template>
      <template v-else>
        <p class="form-hint">该视频已通过人工质量判断。请选择项目成员并创建标注任务。</p>
        <div class="annotation-task-actions">
          <ElSelect v-model="selectedAnnotationAssignee" aria-label="标注任务执行人">
            <ElOption
              v-for="member in annotationAssignees"
              :key="member.membership_id"
              :label="`${member.actor_id} · ${member.role === 'ADMIN' ? '管理员' : '普通用户'}`"
              :value="member.actor_id"
            />
          </ElSelect>
          <ElButton
            type="primary"
            :loading="isCreatingAnnotationTask"
            :disabled="!selectedAnnotationAssignee"
            @click="createTask"
          >
            创建标注任务
          </ElButton>
        </div>
      </template>
    </ElCard>

    <ElCard v-if="canDelete" class="detail-card" shadow="never">
      <template #header><strong>视频分类</strong></template>
      <p class="form-hint">仅在当前项目内归类；取消分类不会删除视频，也不会改变项目权限。</p>
      <div class="asset-classification-actions">
        <ElSelect v-model="selectedFolderId" aria-label="视频文件夹">
          <ElOption label="未分类" value="" />
          <ElOption
            v-for="folder in folders"
            :key="folder.folder_id"
            :label="folder.display_name"
            :value="folder.folder_id"
          />
        </ElSelect>
        <ElButton type="primary" :disabled="!classificationChanged" :loading="isClassifying" @click="updateClassification">
          更新分类
        </ElButton>
      </div>
    </ElCard>

    <ElCard class="detail-card" shadow="never">
      <template #header><strong>资产事实</strong></template>
      <ElDescriptions :column="2" border>
        <ElDescriptionsItem label="内容类型">{{ detail.content_type }}</ElDescriptionsItem>
        <ElDescriptionsItem label="文件大小">{{ formatBytes(detail.size_bytes) }}</ElDescriptionsItem>
        <ElDescriptionsItem label="文件夹">
          <code v-if="detail.folder_id">{{ detail.folder_id }}</code>
          <span v-else>未分类</span>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="批次 ID"><code>{{ detail.batch_id }}</code></ElDescriptionsItem>
        <ElDescriptionsItem label="源上传文件 ID"><code>{{ detail.source_upload_file_id }}</code></ElDescriptionsItem>
        <ElDescriptionsItem label="SHA-256" :span="2"><code class="mono-wrap">{{ detail.sha256 }}</code></ElDescriptionsItem>
        <ElDescriptionsItem label="创建时间">{{ formatDateTime(detail.created_at) }}</ElDescriptionsItem>
        <ElDescriptionsItem label="更新时间">{{ formatDateTime(detail.updated_at) }}</ElDescriptionsItem>
      </ElDescriptions>
    </ElCard>

    <ElCard class="detail-card" shadow="never">
      <template #header><strong>Session 与相机绑定</strong></template>
      <ElDescriptions v-if="detail.session_binding" :column="2" border>
        <ElDescriptionsItem label="所属 Session">
          <RouterLink
            class="text-link"
            :to="{
              name: 'session-detail',
              params: { projectId, id: detail.session_binding.capture_session_id },
            }"
          >
            {{ detail.session_binding.capture_session_id }}
          </RouterLink>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="相机角色">
          {{ detail.session_binding.camera_role }} · #{{ detail.session_binding.camera_index }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="安装位置">{{ detail.session_binding.mount_position }}</ElDescriptionsItem>
        <ElDescriptionsItem label="拍摄开始">
          {{ formatDateTime(detail.session_binding.record_start_utc) }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="流状态">
          {{ streamStatusLabels[detail.session_binding.stream_status] }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="平台质量">
          {{ qualityStatusLabels[detail.session_binding.quality_status] }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="边端同步声明">
          {{ declaredSyncStatusLabels[detail.session_binding.declared_sync_status] }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="边端声明偏移">
          {{ detail.session_binding.time_offset_ms }} ms
        </ElDescriptionsItem>
      </ElDescriptions>
      <p v-else class="empty-state">该资产已校验上传，但尚未绑定到 CaptureSession。</p>
    </ElCard>

    <ElCard v-if="canDelete" class="detail-card danger-card" shadow="never">
      <template #header><strong>危险操作</strong></template>
      <div class="danger-actions">
        <p class="form-hint">删除后视频会从普通列表隐藏，并保留审计记录；不会立即物理删除对象或影响同一 Session 的其他视频。</p>
        <ElButton type="danger" plain :loading="isDeleting" @click="confirmDelete">删除视频</ElButton>
      </div>
    </ElCard>
  </template>
</template>
