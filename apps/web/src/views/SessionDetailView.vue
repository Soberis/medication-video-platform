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

import {
  CatalogApiError,
  getCaptureSession,
  getCaptureSessionDatasetAdmissionPreview,
  type CaptureSessionDetail,
  type DatasetAdmissionPreview,
} from '@/api/catalog'
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
const admissionPreview = shallowRef<DatasetAdmissionPreview | null>(null)
const loadState = ref<'loading' | 'success' | 'error'>('loading')
const admissionLoadState = ref<'loading' | 'success' | 'error'>('loading')
const errorCode = ref('')
const errorMessage = ref('')
const admissionErrorCode = ref('')
const admissionErrorMessage = ref('')
let detailRequest: AbortController | null = null
let admissionRequest: AbortController | null = null

const admissionReasonLabels: Record<string, string> = {
  SESSION_ASSEMBLY_INCOMPLETE: '本次配药操作的视频流尚未组装完整',
  SESSION_STREAM_COUNT_MISMATCH: '已接收的视频流数量与相机配置不一致',
  SESSION_QUALITY_NOT_PASS: '本次配药操作的技术检测尚未通过',
  STREAM_NOT_INGESTED: '有相机视频尚未完成媒体入库',
  STREAM_QUALITY_REJECTED: '有相机视频未通过技术检测',
  CLIP_ASSET_NOT_INGESTED: '有视频资产尚未完成媒体检测',
  CLIP_ASSET_DELETED: '有视频已经删除',
  QUALITY_REVIEW_NOT_QUALIFIED: '有视频尚未通过人工质量判断',
  QUALITY_REVIEW_CHANGED_AFTER_POST_CLEANING: '人工质量结论已变更，需要重新完成清洗检查',
  ANNOTATION_REVISION_MISSING: '有视频尚未提交人工标注',
  ANNOTATION_REVIEW_NOT_APPROVED: '有标注尚未通过独立复核',
  POST_CLEANING_MISSING: '有已复核标注尚未完成清洗检查',
  POST_CLEANING_WARNING: '有标注清洗结果需要人工确认',
  POST_CLEANING_REJECT: '有标注未通过清洗检查',
}

const admissionReasonSummary = computed(() => {
  const preview = admissionPreview.value
  if (!preview) return []

  const affectedStreams = new Map<string, Set<string>>()
  for (const reason of preview.blocking_reasons) {
    affectedStreams.set(reason, new Set())
  }
  for (const stream of preview.streams) {
    for (const reason of stream.blocking_reasons) {
      const streams = affectedStreams.get(reason) ?? new Set<string>()
      streams.add(stream.camera_stream_id)
      affectedStreams.set(reason, streams)
    }
  }

  return [...affectedStreams.entries()].map(([code, streams]) => ({
    code,
    affectedStreamCount: streams.size,
    label: admissionReasonLabels[code] ?? `存在尚未识别的准入条件（${code}）`,
  }))
})

const ingestedStreamCount = computed(
  () =>
    admissionPreview.value?.streams.filter(
      (stream) => stream.stream_status === 'INGESTED' && stream.asset_status === 'INGESTED',
    ).length ?? 0,
)
const qualifiedStreamCount = computed(
  () =>
    admissionPreview.value?.streams.filter(
      (stream) => stream.quality_review_decision === 'QUALIFIED',
    ).length ?? 0,
)
const approvedAnnotationCount = computed(
  () =>
    admissionPreview.value?.streams.filter(
      (stream) =>
        stream.annotation_review_decision === 'APPROVED' &&
        stream.post_cleaning_decision === 'PASS',
    ).length ?? 0,
)

async function loadDetail() {
  detailRequest?.abort()
  const request = new AbortController()
  detailRequest = request
  loadState.value = 'loading'
  try {
    detail.value = await getCaptureSession(projectId.value, sessionId.value, request.signal)
    if (detailRequest === request) loadState.value = 'success'
  } catch (error) {
    if (request.signal.aborted || detailRequest !== request) return
    detail.value = null
    loadState.value = 'error'
    errorCode.value = error instanceof CatalogApiError ? error.errorCode : 'CATALOG_REQUEST_FAILED'
    errorMessage.value =
      error instanceof CatalogApiError ? error.message : '暂时无法读取配药操作详情，请稍后重试。'
  } finally {
    if (detailRequest === request) detailRequest = null
  }
}

async function loadAdmissionPreview() {
  admissionRequest?.abort()
  const request = new AbortController()
  admissionRequest = request
  admissionLoadState.value = 'loading'
  admissionErrorCode.value = ''
  admissionErrorMessage.value = ''
  try {
    admissionPreview.value = await getCaptureSessionDatasetAdmissionPreview(
      projectId.value,
      sessionId.value,
      request.signal,
    )
    if (admissionRequest === request) admissionLoadState.value = 'success'
  } catch (error) {
    if (request.signal.aborted || admissionRequest !== request) return
    admissionPreview.value = null
    admissionLoadState.value = 'error'
    admissionErrorCode.value =
      error instanceof CatalogApiError ? error.errorCode : 'DATASET_ADMISSION_PREVIEW_FAILED'
    admissionErrorMessage.value =
      error instanceof CatalogApiError
        ? error.message
        : '暂时无法核对训练数据准入条件，请稍后重试。'
  } finally {
    if (admissionRequest === request) admissionRequest = null
  }
}

function loadPage() {
  void Promise.all([loadDetail(), loadAdmissionPreview()])
}

watch([projectId, sessionId], loadPage, { immediate: true })
onUnmounted(() => {
  detailRequest?.abort()
  admissionRequest?.abort()
})
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
        <h1 id="session-detail-title">配药操作详情</h1>
        <p class="session-heading-context">
          <strong>{{ detail.device_id }}</strong>
          <span>拍摄于 {{ formatDateTime(detail.device_time_utc) }}</span>
        </p>
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

    <ElCard class="detail-card readiness-card" shadow="never">
      <template #header>
        <div class="card-header-row">
          <div>
            <strong>训练数据准备度</strong>
            <span class="card-header-note">当前事实预览，不会自动创建或冻结数据集</span>
          </div>
          <ElTag
            v-if="admissionLoadState === 'success' && admissionPreview"
            :type="admissionPreview.eligible ? 'success' : 'warning'"
          >
            {{ admissionPreview.eligible ? '可以进入数据集' : '尚未准备完成' }}
          </ElTag>
        </div>
      </template>

      <div v-if="admissionLoadState === 'loading'" class="readiness-loading" role="status">
        正在核对采集、技术检测、人工质量和标注证据…
      </div>
      <div v-else-if="admissionLoadState === 'error'" class="catalog-error readiness-error" role="alert">
        <span>{{ admissionErrorMessage }}</span>
        <strong>{{ admissionErrorCode }}</strong>
        <ElButton @click="loadAdmissionPreview">重试</ElButton>
      </div>
      <template v-else-if="admissionPreview">
        <div class="readiness-evidence" :data-ready="admissionPreview.eligible">
          <div>
            <span>已入库视频</span>
            <strong>
              {{ admissionPreview.received_camera_count }} / {{ admissionPreview.expected_camera_count }} 路
            </strong>
            <small>{{ assemblyStatusLabels[admissionPreview.assembly_status] }}</small>
          </div>
          <div>
            <span>技术检测</span>
            <strong>{{ ingestedStreamCount }} / {{ admissionPreview.streams.length }} 路</strong>
            <small>{{ qualityStatusLabels[admissionPreview.quality_status] }}</small>
          </div>
          <div>
            <span>人工质量</span>
            <strong>{{ qualifiedStreamCount }} / {{ admissionPreview.streams.length }} 路</strong>
            <small>人工判断合格</small>
          </div>
          <div>
            <span>标注与清洗</span>
            <strong>{{ approvedAnnotationCount }} / {{ admissionPreview.streams.length }} 路</strong>
            <small>独立复核及清洗通过</small>
          </div>
        </div>

        <div v-if="admissionPreview.eligible" class="readiness-result readiness-result-ready">
          <strong>当前证据满足训练数据准入条件</strong>
          <span>后续创建数据集时仍会重新校验，避免使用已变化的数据。</span>
        </div>
        <div v-else class="readiness-result">
          <strong>还需完成 {{ admissionReasonSummary.length }} 项条件</strong>
          <ul>
            <li
              v-for="reason in admissionReasonSummary"
              :key="reason.code"
              :title="reason.code"
            >
              <span>{{ reason.label }}</span>
              <small v-if="reason.affectedStreamCount > 0">
                影响 {{ reason.affectedStreamCount }} 路视频
              </small>
            </li>
          </ul>
        </div>
      </template>
    </ElCard>

    <ElCard class="detail-card" shadow="never">
      <template #header><strong>采集概况</strong></template>
      <ElDescriptions :column="3" border>
        <ElDescriptionsItem label="拍摄设备">{{ detail.device_id }}</ElDescriptionsItem>
        <ElDescriptionsItem label="平台接收">{{ formatDateTime(detail.server_receive_time) }}</ElDescriptionsItem>
        <ElDescriptionsItem label="视频完整性">
          {{ detail.received_camera_count }} / {{ detail.expected_camera_count }} 路
        </ElDescriptionsItem>
        <ElDescriptionsItem label="相机配置">
          {{ detail.camera_set_snapshot.camera_set_key }} · v{{ detail.camera_set_snapshot.configuration_version }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="布局版本">{{ detail.camera_set_snapshot.layout_version }}</ElDescriptionsItem>
        <ElDescriptionsItem label="同步组">{{ detail.sync_group_id ?? '未提供' }}</ElDescriptionsItem>
      </ElDescriptions>
      <details class="technical-facts">
        <summary>查看技术标识</summary>
        <dl>
          <div>
            <dt>Session ID</dt>
            <dd><code>{{ detail.capture_session_id }}</code></dd>
          </div>
          <div>
            <dt>批次 ID</dt>
            <dd><code>{{ detail.batch_id }}</code></dd>
          </div>
          <div>
            <dt>配置快照 ID</dt>
            <dd><code>{{ detail.camera_set_snapshot.camera_set_snapshot_id }}</code></dd>
          </div>
        </dl>
      </details>
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
        <ElTableColumn label="技术检测" min-width="110">
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
