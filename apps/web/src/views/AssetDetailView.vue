<script setup lang="ts">
import {
  ElAlert,
  ElButton,
  ElCard,
  ElCheckbox,
  ElCheckboxGroup,
  ElDescriptions,
  ElDescriptionsItem,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElRadio,
  ElRadioGroup,
  ElSelect,
  ElTag,
} from 'element-plus'
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  authorizeRawReviewPlayback,
  CatalogApiError,
  deleteClipAsset,
  getClipAsset,
  type ClipAssetRead,
  type RawReviewPlaybackGrant,
} from '@/api/catalog'
import {
  assignClipAssetFolder,
  getProject,
  listAssetFolders,
  unclassifyClipAsset,
  type AssetFolder,
  type ProjectRole,
} from '@/api/projects'
import {
  assetStatusLabels,
  declaredSyncStatusLabels,
  formatBytes,
  formatDateTime,
  qualityStatusLabels,
  statusTone,
  streamStatusLabels,
} from '@/catalog-presentation'
import {
  createPrivacyAssessment,
  getPrivacyStatus,
  PrivacyApiError,
  type PrivacyFindingType,
  type PrivacyRiskLevel,
  type PrivacyStatus,
} from '@/api/privacy'
import { useAuthStore } from '@/stores/auth'

defineOptions({ name: 'AssetDetailView' })

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
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
const playbackGrant = shallowRef<RawReviewPlaybackGrant | null>(null)
const privacyStatus = shallowRef<PrivacyStatus | null>(null)
const privacyLoadState = ref<'idle' | 'loading' | 'success' | 'error'>('idle')
const privacyErrorCode = ref('')
const privacyErrorMessage = ref('')
const privacyRisk = ref<Exclude<PrivacyRiskLevel, 'UNKNOWN'>>('NONE')
const privacyFindings = ref<PrivacyFindingType[]>([])
const isSavingPrivacy = ref(false)
let activeRequest: AbortController | null = null
let activeRoleRequest: AbortController | null = null
let activePrivacyRequest: AbortController | null = null

const canDelete = computed(() => projectRole.value === 'ADMIN')
const canReviewRaw = computed(
  () =>
    auth.session?.role === 'ADMIN' &&
    projectRole.value === 'ADMIN' &&
    detail.value?.status === 'INGESTED' &&
    detail.value.content_type === 'video/mp4',
)
const canAdministerPrivacy = computed(
  () =>
    auth.session?.role === 'ADMIN' &&
    projectRole.value === 'ADMIN' &&
    detail.value?.status === 'INGESTED',
)
const privacyRequiredAction = computed(() =>
  privacyRisk.value === 'NONE'
    ? 'RELEASE_REVIEW_REQUIRED'
    : privacyRisk.value === 'SUSPECTED'
      ? 'QUARANTINE_REQUIRED'
      : 'DEIDENTIFICATION_REQUIRED',
)
const privacyReasonCode = computed(() =>
  privacyRisk.value === 'NONE'
    ? 'NO_IDENTIFIABLE_INFORMATION_OBSERVED'
    : privacyRisk.value === 'SUSPECTED'
      ? 'RISK_REQUIRES_ISOLATION'
      : 'IDENTIFIABLE_INFORMATION_OBSERVED',
)
const privacyDecisionValid = computed(() =>
  privacyRisk.value === 'NONE' ? privacyFindings.value.length === 0 : privacyFindings.value.length > 0,
)
const classificationChanged = computed(() => selectedFolderId.value !== (detail.value?.folder_id ?? ''))

async function loadDetail() {
  activeRequest?.abort()
  const request = new AbortController()
  activeRequest = request
  loadState.value = 'loading'
  try {
    detail.value = await getClipAsset(projectId.value, assetId.value, request.signal)
    if (activeRequest === request) {
      playbackGrant.value = null
      selectedFolderId.value = detail.value.folder_id ?? ''
      loadState.value = 'success'
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
      return
    }
    try {
      folders.value = await listAssetFolders(projectId.value, request.signal)
    } catch {
      if (!request.signal.aborted && activeRoleRequest === request) folders.value = []
    }
  } catch {
    // Fail closed for destructive UI. The API remains the final authorization boundary.
    if (!request.signal.aborted && activeRoleRequest === request) projectRole.value = null
  } finally {
    if (activeRoleRequest === request) activeRoleRequest = null
  }
}

watch(
  [projectId, assetId],
  () => {
    void loadDetail()
    void loadProjectRole()
  },
  { immediate: true },
)
watch(
  [projectId, assetId, canAdministerPrivacy],
  () => {
    if (!canAdministerPrivacy.value) {
      activePrivacyRequest?.abort()
      privacyStatus.value = null
      privacyLoadState.value = 'idle'
      return
    }
    void loadPrivacyStatus()
  },
  { immediate: true },
)
watch(privacyRisk, (risk) => {
  if (risk === 'NONE') privacyFindings.value = []
})
onUnmounted(() => {
  activeRequest?.abort()
  activeRoleRequest?.abort()
  activePrivacyRequest?.abort()
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

async function startRawReviewPlayback() {
  if (!canReviewRaw.value || isRequestingPlayback.value) return
  isRequestingPlayback.value = true
  actionErrorCode.value = ''
  actionErrorMessage.value = ''
  try {
    playbackGrant.value = await authorizeRawReviewPlayback(projectId.value, assetId.value)
  } catch (error) {
    playbackGrant.value = null
    actionErrorCode.value = error instanceof CatalogApiError ? error.errorCode : 'RAW_REVIEW_PLAYBACK_FAILED'
    actionErrorMessage.value = error instanceof CatalogApiError ? error.message : '无法签发审核播放地址，请稍后重试。'
  } finally {
    isRequestingPlayback.value = false
  }
}

function closeRawReviewPlayback() {
  playbackGrant.value = null
}

async function loadPrivacyStatus() {
  activePrivacyRequest?.abort()
  const request = new AbortController()
  activePrivacyRequest = request
  privacyLoadState.value = 'loading'
  privacyErrorCode.value = ''
  privacyErrorMessage.value = ''
  try {
    privacyStatus.value = await getPrivacyStatus(projectId.value, assetId.value, request.signal)
    if (activePrivacyRequest === request) privacyLoadState.value = 'success'
  } catch (error) {
    if (request.signal.aborted || activePrivacyRequest !== request) return
    privacyStatus.value = null
    privacyLoadState.value = 'error'
    privacyErrorCode.value = error instanceof PrivacyApiError ? error.errorCode : 'PRIVACY_STATUS_FAILED'
    privacyErrorMessage.value =
      error instanceof PrivacyApiError ? error.message : '暂时无法读取隐私评估状态，请稍后重试。'
  } finally {
    if (activePrivacyRequest === request) activePrivacyRequest = null
  }
}

async function savePrivacyAssessment() {
  if (!detail.value || !canAdministerPrivacy.value || !privacyDecisionValid.value || isSavingPrivacy.value) return
  isSavingPrivacy.value = true
  privacyErrorCode.value = ''
  privacyErrorMessage.value = ''
  try {
    await createPrivacyAssessment(projectId.value, {
      clip_asset_id: detail.value.clip_asset_id,
      supersedes_assessment_id: privacyStatus.value?.current_assessment?.privacy_assessment_id ?? null,
      risk_level: privacyRisk.value,
      required_action: privacyRequiredAction.value,
      reason_code: privacyReasonCode.value,
      finding_types: privacyFindings.value,
    })
    ElMessage.success('隐私评估已记录。当前视频仍保持默认阻断状态，尚未获得放行。')
    await loadPrivacyStatus()
  } catch (error) {
    privacyErrorCode.value = error instanceof PrivacyApiError ? error.errorCode : 'PRIVACY_ASSESSMENT_FAILED'
    privacyErrorMessage.value =
      error instanceof PrivacyApiError ? error.message : '隐私评估未保存，请稍后重试。'
  } finally {
    isSavingPrivacy.value = false
  }
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
        <h2 id="asset-detail-title">{{ detail.client_file_id }}</h2>
        <p class="mono-wrap">ClipAsset ID：{{ detail.clip_asset_id }}</p>
      </div>
      <div class="detail-actions">
        <ElTag :type="statusTone(detail.status)">{{ assetStatusLabels[detail.status] }}</ElTag>
        <ElButton
          v-if="canReviewRaw && !playbackGrant"
          type="primary"
          :loading="isRequestingPlayback"
          @click="startRawReviewPlayback"
        >
          审核查看原始视频
        </ElButton>
        <ElButton v-if="canDelete" type="danger" plain :loading="isDeleting" @click="confirmDelete">
          删除视频
        </ElButton>
      </div>
    </section>

    <ElAlert
      v-if="actionErrorMessage"
      class="catalog-alert"
      type="error"
      :title="actionErrorCode"
      :description="actionErrorMessage"
      show-icon
      :closable="false"
    />

    <ElAlert
      v-if="!playbackGrant"
      :title="canReviewRaw ? '原始视频仅用于管理员人工审核' : '常规视频预览尚未开放'"
      :description="canReviewRaw
        ? '点击“审核查看原始视频”后才会签发一次短期只读地址；地址到期后需重新申请，不能用于下载、标注或普通预览。'
        : '当前页面只返回安全的资产元数据。普通预览需要获准的派生视频；原始视频仅向同时具备平台和项目管理员权限的人员开放人工审核。'"
      type="info"
      :closable="false"
      show-icon
    />

    <ElCard v-if="playbackGrant" class="detail-card" shadow="never">
      <template #header><strong>原始视频人工审核</strong></template>
      <p class="form-hint">
        本次地址仅用于隐私与质量人工审核，将于 {{ formatDateTime(playbackGrant.expires_at) }} 到期。
        平台只记录授权行为，不记录浏览器逐帧播放行为。
      </p>
      <video class="review-video" controls preload="metadata" :src="playbackGrant.media_url">
        当前浏览器不支持视频播放。
      </video>
      <div class="detail-actions review-actions">
        <ElTag type="warning">{{ playbackGrant.purpose }}</ElTag>
        <ElButton plain @click="closeRawReviewPlayback">关闭视频</ElButton>
      </div>
    </ElCard>

    <ElCard v-if="canAdministerPrivacy" class="detail-card" shadow="never">
      <template #header><strong>人工隐私评估</strong></template>
      <div v-if="privacyLoadState === 'loading'" class="catalog-notice" role="status">正在读取隐私状态…</div>
      <div v-else-if="privacyLoadState === 'error'" class="catalog-error" role="alert">
        <strong>{{ privacyErrorCode }}</strong>
        <span>{{ privacyErrorMessage }}</span>
        <ElButton @click="loadPrivacyStatus">重试</ElButton>
      </div>
      <template v-else-if="privacyStatus">
        <ElAlert
          :title="`当前风险：${privacyStatus.risk_level}；放行状态：${privacyStatus.privacy_release_status}`"
          :description="privacyStatus.current_assessment
            ? `已有第 ${privacyStatus.current_assessment.assessment_version} 次人工评估。重新提交会创建新版本并替代当前版本。`
            : '尚未记录人工评估。即使评估为“未观察到风险”，视频仍默认阻断，不能直接进入标注或导出。'"
          type="warning"
          :closable="false"
          show-icon
        />
        <div class="privacy-assessment-form">
          <p class="form-hint">基于当前人工审核选择风险结论；系统会根据结论固定后续动作，不能手工改写。</p>
          <ElRadioGroup v-model="privacyRisk" aria-label="隐私风险结论">
            <ElRadio value="NONE">未观察到可识别信息</ElRadio>
            <ElRadio value="SUSPECTED">疑似存在风险，隔离</ElRadio>
            <ElRadio value="CONFIRMED">确认存在风险，后续脱敏</ElRadio>
          </ElRadioGroup>
          <ElCheckboxGroup v-model="privacyFindings" :disabled="privacyRisk === 'NONE'" aria-label="可识别信息发现项">
            <ElCheckbox value="FACE">人脸</ElCheckbox>
            <ElCheckbox value="NAME">姓名</ElCheckbox>
            <ElCheckbox value="MEDICAL_RECORD_ID">病历号</ElCheckbox>
            <ElCheckbox value="SCREEN">屏幕</ElCheckbox>
            <ElCheckbox value="BADGE">工牌</ElCheckbox>
            <ElCheckbox value="ORDER_TEXT">医嘱文本</ElCheckbox>
            <ElCheckbox value="LABEL_TEXT">标签文本</ElCheckbox>
            <ElCheckbox value="VOICE">语音</ElCheckbox>
            <ElCheckbox value="OTHER_IDENTIFIABLE_INFORMATION">其他可识别信息</ElCheckbox>
          </ElCheckboxGroup>
          <p class="form-hint">提交后动作：<code>{{ privacyRequiredAction }}</code>；放行状态仍为 <code>BLOCKED</code>。</p>
          <ElButton type="primary" :disabled="!privacyDecisionValid" :loading="isSavingPrivacy" @click="savePrivacyAssessment">
            {{ privacyStatus.current_assessment ? '重新评估并创建新版本' : '记录隐私评估' }}
          </ElButton>
        </div>
      </template>
    </ElCard>

    <ElAlert
      v-if="projectRole === 'MEMBER'"
      title="当前为普通用户权限"
      description="你可以查看视频状态；删除视频和原始视频人工审核仅对项目管理员开放。"
      type="info"
      :closable="false"
      show-icon
    />

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
  </template>
</template>
