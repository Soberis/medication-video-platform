<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef } from 'vue'

import {
  getUploadBatchProgress,
  listUploadFileProgress,
  UploadApiError,
  type UploadBatchProgress,
  type UploadBatchStatus,
  type UploadFileKind,
  type UploadFileProgress,
  type UploadFileStatus,
} from '@/api/uploads'

defineOptions({ name: 'UploadProgressView' })

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

const batchStatusLabels: Record<UploadBatchStatus, string> = {
  INITIATED: '已初始化',
  UPLOADING: '上传中',
  COMPLETING: '完成处理中',
  COMPLETED: '已完成',
  FAILED: '失败',
  ABORTED: '已中止',
  EXPIRED: '已过期',
}

const fileStatusLabels: Record<UploadFileStatus, string> = {
  REGISTERED: '已登记',
  SESSION_CREATED: '会话已创建',
  UPLOADING: '上传中',
  COMPLETING: '校验中',
  UPLOADED: '已上传并校验',
  FAILED: '校验失败',
}

const fileKindLabels: Record<UploadFileKind, string> = {
  VIDEO: '视频',
  METADATA: '元数据',
  EVENT_LOG: '事件日志',
}

const batchId = ref('')
const batch = shallowRef<UploadBatchProgress | null>(null)
const files = shallowRef<UploadFileProgress[]>([])
const loadState = ref<'idle' | 'loading' | 'success' | 'error'>('idle')
const errorMessage = ref('')
const errorCode = ref('')
let activeRequest: AbortController | null = null

const isLoading = computed(() => loadState.value === 'loading')
const hasResult = computed(() => batch.value !== null && loadState.value === 'success')
const batchBytePercent = computed(() => percent(batch.value?.uploaded_size_bytes ?? 0, batch.value?.total_size_bytes ?? 0))

async function loadProgress() {
  const normalizedBatchId = batchId.value.trim()
  if (!uuidPattern.test(normalizedBatchId)) {
    batch.value = null
    files.value = []
    loadState.value = 'error'
    errorCode.value = 'INVALID_BATCH_ID'
    errorMessage.value = '请输入有效的上传批次 UUID。'
    return
  }

  activeRequest?.abort()
  const request = new AbortController()
  activeRequest = request
  loadState.value = 'loading'
  errorMessage.value = ''
  errorCode.value = ''

  try {
    const [batchResponse, fileResponse] = await Promise.all([
      getUploadBatchProgress(normalizedBatchId, request.signal),
      listUploadFileProgress(normalizedBatchId, request.signal),
    ])
    if (activeRequest !== request) {
      return
    }
    batch.value = batchResponse
    files.value = fileResponse.files
    loadState.value = 'success'
  } catch (error) {
    if (request.signal.aborted || activeRequest !== request) {
      return
    }
    batch.value = null
    files.value = []
    loadState.value = 'error'
    if (error instanceof UploadApiError) {
      errorCode.value = error.errorCode
      errorMessage.value = error.message
    } else {
      errorCode.value = 'UPLOAD_PROGRESS_REQUEST_FAILED'
      errorMessage.value = '无法连接上传进度服务，请确认本地 API 已启动。'
    }
  } finally {
    if (activeRequest === request) {
      activeRequest = null
    }
  }
}

function percent(value: number, total: number) {
  if (total <= 0) {
    return 0
  }
  return Math.min(100, Math.max(0, Math.round((value / total) * 100)))
}

function filePartPercent(file: UploadFileProgress) {
  return percent(file.uploaded_parts, file.total_parts)
}

function formatBytes(value: number) {
  if (value < 1024) {
    return `${value} B`
  }
  const units = ['KiB', 'MiB', 'GiB', 'TiB']
  let amount = value / 1024
  let unit = units[0]
  for (let index = 1; index < units.length && amount >= 1024; index += 1) {
    amount /= 1024
    unit = units[index]
  }
  return `${new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(amount)} ${unit}`
}

function formatTime(value: string | null) {
  if (value === null) {
    return '—'
  }
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'medium',
  }).format(new Date(value))
}

onUnmounted(() => activeRequest?.abort())
</script>

<template>
  <section class="query-card" aria-labelledby="progress-title">
    <div>
      <p class="status-label">上传状态查询</p>
      <h2 id="progress-title">查看批次上传进度</h2>
      <p class="section-description">只读取平台已确认的状态，不会上传文件或改变批次。</p>
    </div>

    <form class="batch-form" @submit.prevent="loadProgress">
      <label for="batch-id">上传批次 ID</label>
      <div class="batch-form-row">
        <input
          id="batch-id"
          v-model="batchId"
          name="batch-id"
          type="text"
          inputmode="text"
          autocomplete="off"
          spellcheck="false"
          placeholder="例如：00000000-0000-4000-8000-000000000001"
          :aria-invalid="loadState === 'error'"
        />
        <button type="submit" :disabled="isLoading">
          {{ isLoading ? '查询中…' : hasResult ? '刷新进度' : '查询进度' }}
        </button>
      </div>
    </form>
  </section>

  <div v-if="isLoading" class="notice" role="status" aria-live="polite">正在读取批次和文件进度…</div>

  <div v-else-if="loadState === 'error'" class="notice notice-error" role="alert">
    <strong>{{ errorCode }}</strong>
    <span>{{ errorMessage }}</span>
  </div>

  <template v-if="batch && hasResult">
    <section class="summary-card" aria-labelledby="batch-summary-title">
      <div class="summary-heading">
        <div>
          <p class="status-label">批次汇总</p>
          <h2 id="batch-summary-title">{{ batch.device_id }}</h2>
        </div>
        <span class="status-badge" :data-status="batch.status">
          {{ batchStatusLabels[batch.status] }}
        </span>
      </div>

      <div class="progress-block">
        <div class="progress-copy">
          <span>已确认字节</span>
          <strong>{{ batchBytePercent }}%</strong>
        </div>
        <progress :value="batch.uploaded_size_bytes" :max="batch.total_size_bytes">
          {{ batchBytePercent }}%
        </progress>
        <p>{{ formatBytes(batch.uploaded_size_bytes) }} / {{ formatBytes(batch.total_size_bytes) }}</p>
      </div>

      <dl class="metric-grid">
        <div>
          <dt>文件登记</dt>
          <dd>{{ batch.registered_file_count }} / {{ batch.file_count }}</dd>
        </div>
        <div>
          <dt>已完成文件</dt>
          <dd>{{ batch.uploaded_file_count }}</dd>
        </div>
        <div>
          <dt>失败文件</dt>
          <dd>{{ batch.failed_file_count }}</dd>
        </div>
        <div>
          <dt>已确认 Part</dt>
          <dd>{{ batch.uploaded_part_count }} / {{ batch.tracked_part_count }}</dd>
        </div>
      </dl>

      <p class="batch-meta">批次 ID：<code>{{ batch.batch_id }}</code></p>
      <p class="batch-meta">有效期至：{{ formatTime(batch.expires_at) }}</p>
    </section>

    <section class="files-section" aria-labelledby="files-title">
      <div class="files-heading">
        <div>
          <p class="status-label">文件进度</p>
          <h2 id="files-title">已登记文件</h2>
        </div>
        <span>{{ files.length }} 个</span>
      </div>

      <p v-if="files.length === 0" class="empty-state">该批次尚未登记文件。</p>

      <article v-for="file in files" :key="file.upload_file_id" class="file-card">
        <div class="file-heading">
          <div>
            <p class="file-kind">{{ fileKindLabels[file.file_kind] }} · {{ file.content_type }}</p>
            <h3>{{ file.client_file_id }}</h3>
          </div>
          <span class="status-badge" :data-status="file.status">
            {{ fileStatusLabels[file.status] }}
          </span>
        </div>

        <div class="progress-block progress-compact">
          <div class="progress-copy">
            <span>Part 进度</span>
            <strong>{{ filePartPercent(file) }}%</strong>
          </div>
          <progress :value="file.uploaded_parts" :max="file.total_parts">
            {{ filePartPercent(file) }}%
          </progress>
          <p>
            已确认 {{ file.uploaded_parts }} / {{ file.total_parts }} Part，
            {{ formatBytes(file.uploaded_size_bytes) }} / {{ formatBytes(file.size_bytes) }}
          </p>
        </div>

        <div v-if="file.completion_error_code" class="file-error" role="status">
          <span>稳定错误码</span>
          <code>{{ file.completion_error_code }}</code>
        </div>

        <dl class="file-meta-grid">
          <div>
            <dt>已跟踪 Part</dt>
            <dd>{{ file.tracked_parts }}</dd>
          </div>
          <div>
            <dt>最后更新</dt>
            <dd>{{ formatTime(file.updated_at) }}</dd>
          </div>
          <div>
            <dt>完成时间</dt>
            <dd>{{ formatTime(file.completed_at) }}</dd>
          </div>
        </dl>
      </article>
    </section>
  </template>
</template>
