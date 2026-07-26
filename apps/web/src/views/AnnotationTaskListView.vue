<script setup lang="ts">
import {
  ElButton,
  ElCard,
  ElDialog,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElMessage,
  ElMessageBox,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import { onUnmounted, ref, shallowRef, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import {
  AnnotationTaskApiError,
  listAnnotationTasks,
  reviewAnnotationRevision,
  submitAnnotationRevision,
  type AnnotationSegment,
  type AnnotationTask,
} from '@/api/annotation'
import { formatDateTime } from '@/catalog-presentation'

defineOptions({ name: 'AnnotationTaskListView' })

interface DraftAnnotationSegment extends AnnotationSegment {
  readonly draftId: number
}

const route = useRoute()
const projectId = ref(String(route.params.projectId ?? ''))
const items = shallowRef<AnnotationTask[]>([])
const loadState = ref<'loading' | 'success' | 'error'>('loading')
const errorCode = ref('')
const errorMessage = ref('')
const selectedTask = shallowRef<AnnotationTask | null>(null)
const isSubmissionOpen = ref(false)
const segments = ref<DraftAnnotationSegment[]>([])
const note = ref('')
const isSubmitting = ref(false)
const reviewingRevisionId = ref<string | null>(null)
let activeRequest: AbortController | null = null
let nextDraftSegmentId = 0

function createDraftSegment(): DraftAnnotationSegment {
  nextDraftSegmentId += 1
  return { draftId: nextDraftSegmentId, start_ms: 0, end_ms: 1000, label: '' }
}

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

function openSubmission(task: AnnotationTask) {
  if (!task.can_submit || task.status !== 'ASSIGNED') return
  selectedTask.value = task
  isSubmissionOpen.value = true
  segments.value = [createDraftSegment()]
  note.value = ''
}

function taskStatusLabel(task: AnnotationTask) {
  if (task.status === 'SUBMITTED') return '已提交'
  return task.annotation_revision?.review?.decision === 'REJECTED' ? '待修改' : '已分配'
}

function addSegment() {
  segments.value = [...segments.value, createDraftSegment()]
}

function removeSegment(index: number) {
  if (segments.value.length === 1) return
  segments.value = segments.value.filter((_, currentIndex) => currentIndex !== index)
}

async function submit() {
  const task = selectedTask.value
  if (!task || isSubmitting.value) return
  const validSegments: AnnotationSegment[] = segments.value.map((segment) => ({
    start_ms: segment.start_ms,
    end_ms: segment.end_ms,
    label: segment.label.trim(),
  }))
  if (validSegments.some((segment) => !segment.label || segment.start_ms < 0 || segment.end_ms <= segment.start_ms)) {
    ElMessage.error('请填写标签，并确保每个片段的结束时间晚于开始时间。')
    return
  }
  isSubmitting.value = true
  try {
    await submitAnnotationRevision(projectId.value, task.annotation_task_id, validSegments, note.value)
    ElMessage.success('标注已提交，等待后续独立复核。')
    isSubmissionOpen.value = false
    await loadTasks()
  } catch (error) {
    ElMessage.error(error instanceof AnnotationTaskApiError ? error.message : '标注暂未提交，请稍后重试。')
  } finally {
    isSubmitting.value = false
  }
}

async function review(task: AnnotationTask, decision: 'APPROVED' | 'REJECTED') {
  const revision = task.annotation_revision
  if (!revision || !task.can_review || reviewingRevisionId.value) return
  const approved = decision === 'APPROVED'
  try {
    await ElMessageBox.confirm(
      approved
        ? '确认该时间段标注与视频画面一致，并将其作为通过的人工标注？'
        : '确认该标注不符合要求？本次将记录为不通过，并退回给标注人修改。',
      approved ? '通过标注复核' : '标注复核不通过',
      { type: approved ? 'success' : 'warning', confirmButtonText: approved ? '确认通过' : '确认不通过', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  reviewingRevisionId.value = revision.annotation_revision_id
  try {
    await reviewAnnotationRevision(projectId.value, revision.annotation_revision_id, decision)
    ElMessage.success(approved ? '标注已通过独立复核。' : '已记录标注不通过。')
    await loadTasks()
  } catch (error) {
    ElMessage.error(error instanceof AnnotationTaskApiError ? error.message : '复核暂未提交，请稍后重试。')
  } finally {
    reviewingRevisionId.value = null
  }
}
</script>

<template>
  <section class="page-heading" aria-labelledby="annotation-task-title">
    <div>
      <p class="app-eyebrow">人工训练数据标注</p>
      <h1 id="annotation-task-title">标注任务</h1>
      <p>只显示当前项目中可处理的合格视频任务。受理人提交时间片段标签后，由另一位项目管理员独立复核。</p>
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
        <template #default="{ row }">
          <ElTag :type="row.status === 'ASSIGNED' ? 'warning' : 'success'">
            {{ taskStatusLabel(row as AnnotationTask) }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="标注与复核" min-width="250">
        <template #default="{ row }">
          <template v-if="row.annotation_revision">
            <div>已提交 · {{ row.annotation_revision.annotation_document.segments.length }} 个时间段</div>
            <ElTag v-if="row.annotation_revision.review" :type="row.annotation_revision.review.decision === 'APPROVED' ? 'success' : 'danger'">
              {{ row.annotation_revision.review.decision === 'APPROVED' ? '复核通过' : '复核不通过' }}
            </ElTag>
            <span v-else class="muted-text">等待独立复核</span>
          </template>
          <span v-else class="muted-text">尚未提交</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="执行人" min-width="180" prop="assignee_actor_id" />
      <ElTableColumn label="创建人" min-width="180" prop="created_by" />
      <ElTableColumn label="创建时间" min-width="185">
        <template #default="{ row }">{{ formatDateTime(row.created_at) }}</template>
      </ElTableColumn>
      <ElTableColumn label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <div class="table-actions">
            <ElButton
              v-if="row.can_submit && row.status === 'ASSIGNED'"
              link
              type="primary"
              :aria-label="`提交视频 ${row.clip_asset_id} 的标注`"
              @click="openSubmission(row as AnnotationTask)"
            >
              提交标注
            </ElButton>
            <template v-else-if="row.can_review && row.annotation_revision">
              <ElButton
                link
                type="success"
                :aria-label="`通过视频 ${row.clip_asset_id} 的标注复核`"
                :loading="reviewingRevisionId === row.annotation_revision.annotation_revision_id"
                @click="review(row as AnnotationTask, 'APPROVED')"
              >
                通过
              </ElButton>
              <ElButton
                link
                type="danger"
                :aria-label="`不通过视频 ${row.clip_asset_id} 的标注复核`"
                :disabled="reviewingRevisionId !== null"
                @click="review(row as AnnotationTask, 'REJECTED')"
              >
                不通过
              </ElButton>
            </template>
            <span v-else>—</span>
          </div>
        </template>
      </ElTableColumn>
    </ElTable>
  </ElCard>

  <ElEmpty
    v-else
    description="当前没有可处理的标注任务。管理员可在已判定合格的视频详情中创建并分配任务。"
  />

  <ElDialog v-model="isSubmissionOpen" title="提交时间片段标注" width="min(720px, calc(100vw - 32px))">
    <p class="form-hint">为该视频记录一个或多个动作时间范围。提交后内容不可覆盖，后续由独立复核处理。</p>
    <ElForm label-position="top" @submit.prevent="submit">
      <div
        v-for="(segment, index) in segments"
        :key="segment.draftId"
        class="annotation-segment-row"
        role="group"
        :aria-label="`标注片段 ${index + 1}`"
      >
        <ElFormItem label="开始（毫秒）">
          <ElInputNumber v-model="segment.start_ms" :min="0" :step="100" controls-position="right" />
        </ElFormItem>
        <ElFormItem label="结束（毫秒）">
          <ElInputNumber v-model="segment.end_ms" :min="1" :step="100" controls-position="right" />
        </ElFormItem>
        <ElFormItem label="标签">
          <ElInput v-model="segment.label" maxlength="64" placeholder="例如：拿取药品" />
        </ElFormItem>
        <ElButton
          :aria-label="`移除标注片段 ${index + 1}`"
          :disabled="segments.length === 1"
          @click="removeSegment(index)"
        >
          移除
        </ElButton>
      </div>
      <ElButton @click="addSegment">新增片段</ElButton>
      <ElFormItem label="备注（可选）">
        <ElInput v-model="note" maxlength="1000" :rows="3" type="textarea" />
      </ElFormItem>
      <div class="detail-actions">
        <ElButton @click="isSubmissionOpen = false">取消</ElButton>
        <ElButton native-type="submit" type="primary" :loading="isSubmitting">提交标注</ElButton>
      </div>
    </ElForm>
  </ElDialog>
</template>
