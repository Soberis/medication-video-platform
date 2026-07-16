<script setup lang="ts">
import {
  ElButton,
  ElCard,
  ElDatePicker,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import { onMounted, onUnmounted, ref, shallowRef } from 'vue'

import {
  CatalogApiError,
  listClipAssets,
  type ClipAssetFilters,
  type ClipAssetRead,
  type ClipAssetStatus,
} from '@/api/catalog'
import {
  assetStatusLabels,
  formatBytes,
  formatDateTime,
  statusLabel,
  statusTone,
} from '@/catalog-presentation'

defineOptions({ name: 'AssetListView' })

const captureSessionId = ref('')
const batchId = ref('')
const deviceId = ref('')
const cameraRole = ref('')
const assetStatus = ref<ClipAssetStatus | ''>('')
const recordedRange = shallowRef<[Date, Date] | null>(null)
const activeFilters = shallowRef<ClipAssetFilters>({})
const items = shallowRef<ClipAssetRead[]>([])
const nextCursor = ref<string | null>(null)
const loadState = ref<'loading' | 'success' | 'error'>('loading')
const isAppending = ref(false)
const errorCode = ref('')
const errorMessage = ref('')
let activeRequest: AbortController | null = null

async function loadPage(append = false) {
  activeRequest?.abort()
  const request = new AbortController()
  activeRequest = request
  isAppending.value = append
  if (!append && items.value.length === 0) loadState.value = 'loading'
  errorCode.value = ''
  errorMessage.value = ''
  try {
    const page = await listClipAssets(
      activeFilters.value,
      append ? nextCursor.value : null,
      request.signal,
    )
    if (activeRequest !== request) return
    items.value = append ? [...items.value, ...page.items] : page.items
    nextCursor.value = page.next_cursor
    loadState.value = 'success'
  } catch (error) {
    if (request.signal.aborted || activeRequest !== request) return
    loadState.value = 'error'
    if (error instanceof CatalogApiError) {
      errorCode.value = error.errorCode
      errorMessage.value = error.message
    } else {
      errorCode.value = 'CATALOG_REQUEST_FAILED'
      errorMessage.value = '暂时无法读取视频资产，请稍后重试。'
    }
  } finally {
    if (activeRequest === request) activeRequest = null
    isAppending.value = false
  }
}

function applyFilters() {
  activeFilters.value = {
    captureSessionId: captureSessionId.value.trim() || undefined,
    batchId: batchId.value.trim() || undefined,
    deviceId: deviceId.value.trim() || undefined,
    cameraRole: cameraRole.value.trim().toUpperCase() || undefined,
    status: assetStatus.value || undefined,
    recordedFrom: recordedRange.value?.[0].toISOString(),
    recordedBefore: recordedRange.value?.[1].toISOString(),
  }
  items.value = []
  nextCursor.value = null
  void loadPage()
}

function resetFilters() {
  captureSessionId.value = ''
  batchId.value = ''
  deviceId.value = ''
  cameraRole.value = ''
  assetStatus.value = ''
  recordedRange.value = null
  applyFilters()
}

onMounted(() => void loadPage())
onUnmounted(() => activeRequest?.abort())
</script>

<template>
  <section class="page-heading" aria-labelledby="assets-title">
    <div>
      <p class="app-eyebrow">单路相机视频</p>
      <h2 id="assets-title">视频资产</h2>
      <p>用于跨 Session 排查单路视频状态；日常数据管理仍以一次配药操作为主。</p>
    </div>
    <ElButton :loading="loadState === 'loading'" @click="loadPage(false)">刷新</ElButton>
  </section>

  <ElCard class="filter-card" shadow="never">
    <ElForm class="catalog-filters asset-filters" label-position="top" @submit.prevent="applyFilters">
      <ElFormItem label="Session ID"><ElInput v-model="captureSessionId" clearable /></ElFormItem>
      <ElFormItem label="批次 ID"><ElInput v-model="batchId" clearable /></ElFormItem>
      <ElFormItem label="设备 ID"><ElInput v-model="deviceId" clearable /></ElFormItem>
      <ElFormItem label="相机角色">
        <ElInput v-model="cameraRole" clearable placeholder="例如 HANDS" />
      </ElFormItem>
      <ElFormItem label="资产状态">
        <ElSelect v-model="assetStatus" clearable placeholder="全部状态">
          <ElOption
            v-for="(label, value) in assetStatusLabels"
            :key="value"
            :label="label"
            :value="value"
          />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="拍摄时间">
        <ElDatePicker
          v-model="recordedRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          unlink-panels
        />
      </ElFormItem>
      <div class="filter-actions">
        <ElButton native-type="submit" type="primary">应用筛选</ElButton>
        <ElButton @click="resetFilters">重置</ElButton>
      </div>
    </ElForm>
  </ElCard>

  <div v-if="loadState === 'loading'" class="catalog-notice" role="status">正在读取视频资产…</div>
  <div v-else-if="loadState === 'error'" class="catalog-error" role="alert">
    <strong>{{ errorCode }}</strong>
    <span>{{ errorMessage }}</span>
    <ElButton @click="loadPage(false)">重试</ElButton>
  </div>

  <ElCard v-if="items.length > 0" class="table-card" shadow="never" :aria-busy="isAppending">
    <ElTable :data="items" row-key="clip_asset_id">
      <ElTableColumn label="视频资产" min-width="170">
        <template #default="{ row }">
          <strong class="mono-wrap">{{ row.client_file_id }}</strong>
          <span class="cell-secondary">{{ row.content_type }} · {{ formatBytes(row.size_bytes) }}</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="Session / 视角" min-width="180">
        <template #default="{ row }">
          <template v-if="row.session_binding">
            <RouterLink class="text-link" :to="`/sessions/${row.session_binding.capture_session_id}`">
              {{ row.session_binding.camera_role }} · #{{ row.session_binding.camera_index }}
            </RouterLink>
            <span class="cell-secondary">{{ formatDateTime(row.session_binding.record_start_utc) }}</span>
          </template>
          <span v-else>尚未绑定 Session</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="资产状态" min-width="140">
        <template #default="{ row }">
          <ElTag :type="statusTone(row.status)">
            {{ statusLabel(assetStatusLabels, row.status) }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="流 / 质量" min-width="150">
        <template #default="{ row }">
          <template v-if="row.session_binding">
            <span>{{ row.session_binding.stream_status }}</span>
            <span class="cell-secondary">质量：{{ row.session_binding.quality_status }}</span>
          </template>
          <span v-else>等待 Session 组装</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="更新时间" min-width="170">
        <template #default="{ row }">{{ formatDateTime(row.updated_at) }}</template>
      </ElTableColumn>
      <ElTableColumn label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <RouterLink class="text-link" :to="`/assets/${row.clip_asset_id}`">查看详情</RouterLink>
        </template>
      </ElTableColumn>
    </ElTable>
    <div class="table-footer">
      <span>已显示 {{ items.length }} 个视频资产</span>
      <ElButton v-if="nextCursor" :loading="isAppending" @click="loadPage(true)">加载更多</ElButton>
    </div>
  </ElCard>

  <ElEmpty
    v-else-if="loadState === 'success'"
    description="当前项目没有符合条件的视频资产。请先检查边端上传和 Session 组装状态。"
  />
</template>
