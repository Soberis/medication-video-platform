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

import { CatalogApiError, deleteClipAsset, getClipAsset, type ClipAssetRead } from '@/api/catalog'
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
let activeRequest: AbortController | null = null
let activeRoleRequest: AbortController | null = null

const canDelete = computed(() => projectRole.value === 'ADMIN')
const classificationChanged = computed(() => selectedFolderId.value !== (detail.value?.folder_id ?? ''))

async function loadDetail() {
  activeRequest?.abort()
  const request = new AbortController()
  activeRequest = request
  loadState.value = 'loading'
  try {
    detail.value = await getClipAsset(projectId.value, assetId.value, request.signal)
    if (activeRequest === request) {
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
onUnmounted(() => {
  activeRequest?.abort()
  activeRoleRequest?.abort()
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
      title="视频预览尚未开放"
      description="当前接口只返回安全的资产元数据。完成媒体处理、隐私评估和预览授权后，平台才会提供可播放的派生视频。"
      type="info"
      :closable="false"
      show-icon
    />

    <ElAlert
      v-if="projectRole === 'MEMBER'"
      title="当前为普通用户权限"
      description="你可以查看视频状态；删除视频仅对项目管理员开放。"
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
