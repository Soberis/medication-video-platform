<script setup lang="ts">
import { ElButton, ElCard, ElEmpty, ElTable, ElTableColumn, ElTag } from 'element-plus'
import { onUnmounted, ref, shallowRef, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import {
  AnnotationTaskApiError,
  listAnnotationTasks,
  type AnnotationTask,
} from '@/api/annotation'
import { formatDateTime } from '@/catalog-presentation'

defineOptions({ name: 'AnnotationTaskListView' })

const route = useRoute()
const projectId = ref(String(route.params.projectId ?? ''))
const items = shallowRef<AnnotationTask[]>([])
const loadState = ref<'loading' | 'success' | 'error'>('loading')
const errorCode = ref('')
const errorMessage = ref('')
let activeRequest: AbortController | null = null

async function loadTasks() {
  activeRequest?.abort()
  const request = new AbortController()
  activeRequest = request
  loadState.value = 'loading'
  errorCode.value = ''
  errorMessage.value = ''
  try {
    items.value = await listAnnotationTasks(projectId.value, request.signal)
    if (activeRequest === request) loadState.value = 'success'
  } catch (error) {
    if (request.signal.aborted || activeRequest !== request) return
    loadState.value = 'error'
    errorCode.value = error instanceof AnnotationTaskApiError ? error.errorCode : 'ANNOTATION_TASK_REQUEST_FAILED'
    errorMessage.value =
      error instanceof AnnotationTaskApiError ? error.message : '暂时无法读取标注任务，请稍后重试。'
  } finally {
    if (activeRequest === request) activeRequest = null
  }
}

watch(
  () => String(route.params.projectId ?? ''),
  (value) => {
    projectId.value = value
    void loadTasks()
  },
  { immediate: true },
)
onUnmounted(() => activeRequest?.abort())
</script>

<template>
  <section class="page-heading" aria-labelledby="annotation-task-title">
    <div>
      <p class="app-eyebrow">人工训练数据标注</p>
      <h1 id="annotation-task-title">标注任务</h1>
      <p>只显示当前项目中你可处理的合格视频任务。标注内容与独立复核将在后续切片启用。</p>
    </div>
    <ElButton :loading="loadState === 'loading'" @click="loadTasks">刷新</ElButton>
  </section>

  <div v-if="loadState === 'loading'" class="catalog-notice" role="status">正在读取标注任务…</div>
  <div v-else-if="loadState === 'error'" class="catalog-error" role="alert">
    <strong>{{ errorCode }}</strong>
    <span>{{ errorMessage }}</span>
    <ElButton @click="loadTasks">重试</ElButton>
  </div>

  <ElCard v-else-if="items.length" class="table-card" shadow="never">
    <ElTable :data="items" row-key="annotation_task_id">
      <ElTableColumn label="视频资产" min-width="260">
        <template #default="{ row }">
          <RouterLink
            class="text-link mono-wrap"
            :to="{ name: 'asset-detail', params: { projectId, id: row.clip_asset_id } }"
          >
            {{ row.clip_asset_id }}
          </RouterLink>
        </template>
      </ElTableColumn>
      <ElTableColumn label="状态" width="130">
        <template #default="{ row }"><ElTag type="warning">{{ row.status === 'ASSIGNED' ? '已分配' : row.status }}</ElTag></template>
      </ElTableColumn>
      <ElTableColumn label="执行人" min-width="180" prop="assignee_actor_id" />
      <ElTableColumn label="创建人" min-width="180" prop="created_by" />
      <ElTableColumn label="创建时间" min-width="185">
        <template #default="{ row }">{{ formatDateTime(row.created_at) }}</template>
      </ElTableColumn>
    </ElTable>
  </ElCard>

  <ElEmpty
    v-else
    description="当前没有可处理的标注任务。管理员可在已判定合格的视频详情中创建并分配任务。"
  />
</template>
