<script setup lang="ts">
import {
  ElButton,
  ElCard,
  ElDescriptions,
  ElDescriptionsItem,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useRoute } from 'vue-router'

import { CatalogApiError, getCaptureSession, type CaptureSessionDetail } from '@/api/catalog'
import {
  assemblyStatusLabels,
  declaredSyncStatusLabels,
  formatDateTime,
  qualityStatusLabels,
  statusLabel,
  statusTone,
  streamStatusLabels,
} from '@/catalog-presentation'

defineOptions({ name: 'SessionDetailView' })

const route = useRoute()
const projectId = computed(() => String(route.params.projectId ?? ''))
const sessionId = computed(() => String(route.params.id ?? ''))
const detail = shallowRef<CaptureSessionDetail | null>(null)
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
    detail.value = await getCaptureSession(projectId.value, sessionId.value, request.signal)
    if (activeRequest === request) loadState.value = 'success'
  } catch (error) {
    if (request.signal.aborted || activeRequest !== request) return
    detail.value = null
    loadState.value = 'error'
    errorCode.value = error instanceof CatalogApiError ? error.errorCode : 'CATALOG_REQUEST_FAILED'
    errorMessage.value =
      error instanceof CatalogApiError ? error.message : '暂时无法读取配药操作详情，请稍后重试。'
  } finally {
    if (activeRequest === request) activeRequest = null
  }
}

watch([projectId, sessionId], () => void loadDetail(), { immediate: true })
onUnmounted(() => activeRequest?.abort())
</script>

<template>
  <nav class="breadcrumb" aria-label="面包屑">
    <RouterLink :to="{ name: 'session-list', params: { projectId } }">配药操作记录</RouterLink>
    <span aria-hidden="true">/</span>
    <span>详情</span>
  </nav>

  <div v-if="loadState === 'loading'" class="catalog-notice" role="status">正在读取 Session 详情…</div>
  <div v-else-if="loadState === 'error'" class="catalog-error" role="alert">
    <strong>{{ errorCode }}</strong>
    <span>{{ errorMessage }}</span>
    <ElButton @click="loadDetail">重试</ElButton>
  </div>

  <template v-else-if="detail">
    <section class="page-heading detail-heading" aria-labelledby="session-detail-title">
      <div>
        <p class="app-eyebrow">一次护士配药操作</p>
        <h1 id="session-detail-title">{{ detail.device_id }}</h1>
        <p class="mono-wrap">Session ID：{{ detail.capture_session_id }}</p>
      </div>
      <div class="heading-tags">
        <ElTag :type="statusTone(detail.assembly_status)">
          {{ assemblyStatusLabels[detail.assembly_status] }}
        </ElTag>
        <ElTag :type="statusTone(detail.quality_status)" effect="plain">
          {{ qualityStatusLabels[detail.quality_status] }}
        </ElTag>
      </div>
    </section>

    <ElCard class="detail-card" shadow="never">
      <template #header><strong>Session 事实</strong></template>
      <ElDescriptions :column="2" border>
        <ElDescriptionsItem label="拍摄时间">{{ formatDateTime(detail.device_time_utc) }}</ElDescriptionsItem>
        <ElDescriptionsItem label="平台接收时间">{{ formatDateTime(detail.server_receive_time) }}</ElDescriptionsItem>
        <ElDescriptionsItem label="设备时区">{{ detail.device_timezone }}</ElDescriptionsItem>
        <ElDescriptionsItem label="同步组">{{ detail.sync_group_id }}</ElDescriptionsItem>
        <ElDescriptionsItem label="已入库 / 预期流">
          {{ detail.received_camera_count }} / {{ detail.expected_camera_count }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="批次 ID"><code>{{ detail.batch_id }}</code></ElDescriptionsItem>
      </ElDescriptions>
    </ElCard>

    <ElCard class="detail-card" shadow="never">
      <template #header><strong>冻结的 CameraSet 配置</strong></template>
      <ElDescriptions :column="2" border>
        <ElDescriptionsItem label="CameraSet">
          {{ detail.camera_set_snapshot.camera_set_key }} · v{{ detail.camera_set_snapshot.configuration_version }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="布局版本">{{ detail.camera_set_snapshot.layout_version }}</ElDescriptionsItem>
        <ElDescriptionsItem label="预期启用相机">
          {{ detail.camera_set_snapshot.expected_camera_count }} 路
        </ElDescriptionsItem>
        <ElDescriptionsItem label="配置快照 ID">
          <code>{{ detail.camera_set_snapshot.camera_set_snapshot_id }}</code>
        </ElDescriptionsItem>
      </ElDescriptions>
    </ElCard>

    <ElCard class="detail-card table-card" shadow="never">
      <template #header>
        <div class="card-header-row">
          <strong>相机视频流</strong>
          <span>{{ detail.streams.length }} 路已绑定</span>
        </div>
      </template>
      <ElTable :data="detail.streams" row-key="camera_stream_id">
        <ElTableColumn prop="camera_index" label="序号" width="72" />
        <ElTableColumn label="视角 / 安装位" min-width="160">
          <template #default="{ row }">
            <strong>{{ row.camera_role }}</strong>
            <span class="cell-secondary">{{ row.mount_position }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="拍摄开始" min-width="180">
          <template #default="{ row }">{{ formatDateTime(row.record_start_utc) }}</template>
        </ElTableColumn>
        <ElTableColumn label="流状态" min-width="150">
          <template #default="{ row }">
            <ElTag :type="statusTone(row.stream_status)">
              {{ statusLabel(streamStatusLabels, row.stream_status) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="边端同步声明" min-width="165">
          <template #default="{ row }">
            <span>{{ statusLabel(declaredSyncStatusLabels, row.sync_status) }}</span>
            <span class="cell-secondary">偏移 {{ row.time_offset_ms }} ms</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="平台质量" min-width="110">
          <template #default="{ row }">
            {{ statusLabel(qualityStatusLabels, row.quality_status) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="视频资产" width="100" fixed="right">
          <template #default="{ row }">
            <RouterLink
              class="text-link table-action-link"
              :to="{ name: 'asset-detail', params: { projectId, id: row.clip_asset_id } }"
            >
              查看资产
            </RouterLink>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>
  </template>
</template>
