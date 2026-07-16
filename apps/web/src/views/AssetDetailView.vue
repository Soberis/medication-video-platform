<script setup lang="ts">
import { ElAlert, ElButton, ElCard, ElDescriptions, ElDescriptionsItem, ElTag } from 'element-plus'
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useRoute } from 'vue-router'

import { CatalogApiError, getClipAsset, type ClipAssetRead } from '@/api/catalog'
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
const assetId = computed(() => String(route.params.id ?? ''))
const detail = shallowRef<ClipAssetRead | null>(null)
const loadState = ref<'loading' | 'success' | 'error'>('loading')
const errorCode = ref('')
const errorMessage = ref('')
let activeRequest: AbortController | null = null

async function loadDetail() {
  activeRequest?.abort()
  const request = new AbortController()
  activeRequest = request
  loadState.value = 'loading'
  try {
    detail.value = await getClipAsset(assetId.value, request.signal)
    if (activeRequest === request) loadState.value = 'success'
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

watch(assetId, () => void loadDetail(), { immediate: true })
onUnmounted(() => activeRequest?.abort())
</script>

<template>
  <nav class="breadcrumb" aria-label="面包屑">
    <RouterLink to="/assets">视频资产</RouterLink>
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
      <ElTag :type="statusTone(detail.status)">{{ assetStatusLabels[detail.status] }}</ElTag>
    </section>

    <ElAlert
      title="视频预览尚未开放"
      description="当前接口只返回安全的资产元数据。完成媒体处理、隐私评估和预览授权后，平台才会提供可播放的派生视频。"
      type="info"
      :closable="false"
      show-icon
    />

    <ElCard class="detail-card" shadow="never">
      <template #header><strong>资产事实</strong></template>
      <ElDescriptions :column="2" border>
        <ElDescriptionsItem label="内容类型">{{ detail.content_type }}</ElDescriptionsItem>
        <ElDescriptionsItem label="文件大小">{{ formatBytes(detail.size_bytes) }}</ElDescriptionsItem>
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
          <RouterLink class="text-link" :to="`/sessions/${detail.session_binding.capture_session_id}`">
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
