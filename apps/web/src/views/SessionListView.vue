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
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useRoute } from 'vue-router'

import {
  CatalogApiError,
  listCaptureSessions,
  type CaptureQualityStatus,
  type CaptureSessionAssemblyStatus,
  type CaptureSessionFilters,
  type CaptureSessionListItem,
} from '@/api/catalog'
import {
  assemblyStatusLabels,
  formatDateTime,
  qualityStatusLabels,
  shortId,
  statusLabel,
  statusTone,
} from '@/catalog-presentation'

defineOptions({ name: 'SessionListView' })

const route = useRoute()
const projectId = computed(() => String(route.params.projectId ?? ''))
const deviceId = ref('')
const assemblyStatus = ref<CaptureSessionAssemblyStatus | ''>('')
const qualityStatus = ref<CaptureQualityStatus | ''>('')
const recordedRange = shallowRef<[Date, Date] | null>(null)
const activeFilters = shallowRef<CaptureSessionFilters>({})
const items = shallowRef<CaptureSessionListItem[]>([])
const nextCursor = ref<string | null>(null)
const loadState = ref<'loading' | 'success' | 'error'>('loading')
const isAppending = ref(false)
const errorCode = ref('')
const errorMessage = ref('')
const requestId = ref<string | null>(null)
let activeRequest: AbortController | null = null

async function loadPage(append = false) {
  activeRequest?.abort()
  const request = new AbortController()
  activeRequest = request
  isAppending.value = append
  if (!append && items.value.length === 0) loadState.value = 'loading'
  errorCode.value = ''
  errorMessage.value = ''
  requestId.value = null
  try {
    const page = await listCaptureSessions(
      projectId.value,
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
      requestId.value = error.requestId
    } else {
      errorCode.value = 'CATALOG_REQUEST_FAILED'
      errorMessage.value = '暂时无法读取配药操作记录，请稍后重试。'
    }
  } finally {
    if (activeRequest === request) activeRequest = null
    isAppending.value = false
  }
}

function applyFilters() {
  activeFilters.value = {
    deviceId: deviceId.value.trim() || undefined,
    assemblyStatus: assemblyStatus.value || undefined,
    qualityStatus: qualityStatus.value || undefined,
    recordedFrom: recordedRange.value?.[0].toISOString(),
    recordedBefore: recordedRange.value?.[1].toISOString(),
  }
  items.value = []
  nextCursor.value = null
  void loadPage()
}

function resetFilters() {
  deviceId.value = ''
  assemblyStatus.value = ''
  qualityStatus.value = ''
  recordedRange.value = null
  applyFilters()
}

watch(
  projectId,
  () => {
    items.value = []
    nextCursor.value = null
    void loadPage()
  },
  { immediate: true },
)
onUnmounted(() => activeRequest?.abort())
</script>

<template>
  <section class="page-heading" aria-labelledby="sessions-title">
    <div>
      <p class="app-eyebrow">护士配药 · 多相机数据</p>
      <h1 id="sessions-title">配药操作记录</h1>
      <p>按一次配药操作查看多路视频是否绑定、是否完成媒体入库，以及后续质量状态。</p>
    </div>
    <ElButton :loading="loadState === 'loading'" @click="loadPage(false)">刷新</ElButton>
  </section>

  <ElCard class="filter-card" shadow="never">
    <ElForm class="catalog-filters" label-position="top" @submit.prevent="applyFilters">
      <ElFormItem label="边端设备 ID">
        <ElInput v-model="deviceId" clearable placeholder="例如 synthetic-edge-01" />
      </ElFormItem>
      <ElFormItem label="组装状态">
        <ElSelect v-model="assemblyStatus" clearable placeholder="全部状态">
          <ElOption
            v-for="(label, value) in assemblyStatusLabels"
            :key="value"
            :label="label"
            :value="value"
          />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="平台质量状态">
        <ElSelect v-model="qualityStatus" clearable placeholder="全部状态">
          <ElOption
            v-for="(label, value) in qualityStatusLabels"
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

  <div v-if="loadState === 'loading'" class="catalog-notice" role="status" aria-live="polite">
    正在读取配药操作记录…
  </div>
  <div v-else-if="loadState === 'error'" class="catalog-error" role="alert">
    <strong>{{ errorCode }}</strong>
    <span>{{ errorMessage }}</span>
    <small v-if="requestId">请求标识：{{ requestId }}</small>
    <ElButton @click="loadPage(false)">重试</ElButton>
  </div>

  <ElCard v-if="items.length > 0" class="table-card" shadow="never" :aria-busy="isAppending">
    <ElTable :data="items" row-key="capture_session_id">
      <ElTableColumn label="拍摄时间" min-width="180">
        <template #default="{ row }">
          <strong>{{ formatDateTime(row.device_time_utc) }}</strong>
          <span class="cell-secondary">接收：{{ formatDateTime(row.server_receive_time) }}</span>
        </template>
      </ElTableColumn>
      <ElTableColumn prop="device_id" label="设备" min-width="150" />
      <ElTableColumn label="相机完整性" min-width="170">
        <template #default="{ row }">
          <strong>已绑定 {{ row.bound_stream_count }} / {{ row.expected_camera_count }}</strong>
          <span class="cell-secondary">已入库 {{ row.received_camera_count }}</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="CameraSet / 布局" min-width="170">
        <template #default="{ row }">
          <span>{{ row.camera_set_key }} · v{{ row.camera_set_configuration_version }}</span>
          <span class="cell-secondary">{{ row.camera_layout_version }}</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="组装状态" min-width="120">
        <template #default="{ row }">
          <ElTag :type="statusTone(row.assembly_status)" effect="light">
            {{ statusLabel(assemblyStatusLabels, row.assembly_status) }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="质量状态" min-width="110">
        <template #default="{ row }">
          <ElTag :type="statusTone(row.quality_status)" effect="plain">
            {{ statusLabel(qualityStatusLabels, row.quality_status) }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <RouterLink
            class="text-link"
            :to="{
              name: 'session-detail',
              params: { projectId, id: row.capture_session_id },
            }"
          >
            查看详情
          </RouterLink>
        </template>
      </ElTableColumn>
    </ElTable>
    <div class="table-footer">
      <span>已显示 {{ items.length }} 组 · Session ID 示例 {{ shortId(items[0]!.capture_session_id) }}</span>
      <ElButton v-if="nextCursor" :loading="isAppending" @click="loadPage(true)">加载更多</ElButton>
    </div>
  </ElCard>

  <ElEmpty
    v-else-if="loadState === 'success'"
    description="当前项目没有符合条件的配药操作记录。可调整筛选，或通过边端自动上传和平台本地上传新增视频。"
  />
</template>
