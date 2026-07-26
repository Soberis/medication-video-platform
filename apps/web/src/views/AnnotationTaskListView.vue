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
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import {
  AnnotationTaskApiError,
  getAnnotationLabelSchema,
  listAnnotationTasks,
  reviewAnnotationRevision,
  submitAnnotationRevision,
  type AnnotationLabelSchema,
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
const labelSchema = shallowRef<AnnotationLabelSchema | null>(null)
const sortedLabels = computed(() =>
  [...(labelSchema.value?.labels ?? [])].sort(
    (left, right) => left.sort_order - right.sort_order || left.label_id.localeCompare(right.label_id),
  ),
)
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

const postCleaningReasonLabels: Readonly<Record<string, string>> = {
  REVIEW_NOT_APPROVED: '未绑定已通过的独立复核',
  QUALITY_REVIEW_NOT_QUALIFIED: '当前人工质量结论不是合格',
  ASSET_INPUT_DIGEST_INVALID: '视频输入摘要无效',
  ANNOTATION_DOCUMENT_DIGEST_INVALID: '标注文档摘要无效',
  ANNOTATION_DOCUMENT_DIGEST_MISMATCH: '标注文档内容与摘要不一致',
  LABEL_SCHEMA_MISMATCH: '任务与修订绑定的标签规范不一致',
  ANNOTATION_SEGMENTS_MISSING: '缺少主流程时间片段',
  SEGMENT_TIME_RANGE_INVALID: '存在无效的时间范围',
  PRIMARY_STEP_LABEL_NOT_ALLOWED: '存在标签规范未允许的主流程标签',
  PRIMARY_STEP_OVERLAP: '主流程时间片段存在重叠',
  VERIFIED_MEDIA_DURATION_UNAVAILABLE: '缺少已验证的视频时长',
  VERIFIED_MEDIA_DURATION_INVALID: '已验证的视频时长无效',
  VERIFIED_MEDIA_FACT_INCOMPLETE: '媒体探测事实不完整',
  VERIFIED_MEDIA_INPUT_DIGEST_MISMATCH: '媒体探测输入摘要与视频资产不一致',
  SEGMENT_EXCEEDS_VERIFIED_MEDIA_DURATION: '时间片段超出已验证的视频时长',
  LEGACY_APPROVED_REVISION_REQUIRES_REVALIDATION: '历史通过记录需要按当前规则重新验证',
}

function createDraftSegment(): DraftAnnotationSegment {
  nextDraftSegmentId += 1
  return { draftId: nextDraftSegmentId, start_ms: 0, end_ms: 1000, label: '' }
}

async function loadTasks() {
  activeRequest?.abort()
  const request = new AbortController()
  activeRequest = request
  loadState.value = 'loading'
  labelSchema.value = null
  errorCode.value = ''
  errorMessage.value = ''
  try {
    const [tasks, schema] = await Promise.all([
      listAnnotationTasks(projectId.value, request.signal),
      getAnnotationLabelSchema(projectId.value, request.signal),
    ])
    if (activeRequest === request) {
      items.value = tasks
      labelSchema.value = schema
      loadState.value = 'success'
    }
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
  const schema = labelSchema.value
  if (
    !schema ||
    schema.label_schema_id !== task.label_schema_id ||
    schema.schema_digest !== task.label_schema_digest
  ) {
    ElMessage.error('任务绑定的标签规范与当前项目不一致，请刷新后重试。')
    return
  }
  selectedTask.value = task
  isSubmissionOpen.value = true
  segments.value = [createDraftSegment()]
  note.value = ''
}

function taskStatusLabel(task: AnnotationTask) {
  if (task.status === 'COMPLETED') return '已完成'
  if (task.status === 'SUBMITTED') return '已提交'
  return task.annotation_revision?.review?.decision === 'REJECTED' ? '待修改' : '已分配'
}

function taskStatusTagType(task: AnnotationTask) {
  if (task.status === 'COMPLETED') return 'success'
  if (task.status === 'SUBMITTED') return 'info'
  return 'warning'
}

function postCleaningPresentation(task: AnnotationTask) {
  const revision = task.annotation_revision
  if (revision?.review?.decision !== 'APPROVED') return null
  const result = revision.post_cleaning_result
  const reasons = (result?.reason_codes ?? []).map((reasonCode) => {
    const knownLabel = postCleaningReasonLabels[reasonCode]
    if (knownLabel) return knownLabel
    const safeCode = /^[A-Z][A-Z0-9_]{0,63}$/.test(reasonCode) ? reasonCode : 'UNKNOWN_REASON'
    return `其他清洗原因（${safeCode}）`
  })
  if (result?.outcome === 'PASS') {
    return {
      label: '当前规则检查通过',
      type: 'success' as const,
      description: '尚未进入训练数据集。',
      reasons,
    }
  }
  if (result?.outcome === 'WARNING') {
    return {
      label: '后置清洗待确认',
      type: 'warning' as const,
      description: '不会自动准入数据集，需补充事实或按后续准入流程确认。',
      reasons,
    }
  }
  if (result?.outcome === 'REJECT') {
    return {
      label: '后置清洗不通过',
      type: 'danger' as const,
      description: '当前版本默认不准入数据集；视频资产未被删除。',
      reasons,
    }
  }
  return {
    label: '后置清洗未执行',
    type: 'info' as const,
    description: '尚未形成可用于后续准入判断的结果。',
    reasons,
  }
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
  const schema = labelSchema.value
  if (!task || !schema || isSubmitting.value) return
  if (schema.label_schema_id !== task.label_schema_id || schema.schema_digest !== task.label_schema_digest) {
    ElMessage.error('任务绑定的标签规范已发生变化，请关闭窗口并刷新后重试。')
    return
  }
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
      <p>只显示当前项目中可处理的合格视频任务。受理人提交时间片段标签后，由另一位项目管理员独立复核并生成后置清洗结果。</p>
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
          <ElTag :type="taskStatusTagType(row as AnnotationTask)">
            {{ taskStatusLabel(row as AnnotationTask) }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="标注、复核与清洗" min-width="280">
        <template #default="{ row }">
          <template v-if="row.annotation_revision">
            <div>已提交 · {{ row.annotation_revision.annotation_document.segments.length }} 个时间段</div>
            <ElTag v-if="row.annotation_revision.review" :type="row.annotation_revision.review.decision === 'APPROVED' ? 'success' : 'danger'">
              {{ row.annotation_revision.review.decision === 'APPROVED' ? '复核通过' : '复核不通过' }}
            </ElTag>
            <span v-else class="muted-text">等待独立复核</span>
            <div v-if="postCleaningPresentation(row as AnnotationTask)">
              <ElTag :type="postCleaningPresentation(row as AnnotationTask)?.type">
                {{ postCleaningPresentation(row as AnnotationTask)?.label }}
              </ElTag>
              <span class="cell-secondary">
                {{ postCleaningPresentation(row as AnnotationTask)?.description }}
              </span>
              <span
                v-if="postCleaningPresentation(row as AnnotationTask)?.reasons.length"
                class="cell-secondary"
              >
                原因：{{ postCleaningPresentation(row as AnnotationTask)?.reasons.join('；') }}
              </span>
            </div>
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
    <p class="form-hint">
      按项目标签规范记录一个或多个主流程步骤。提交后内容不可覆盖，后续由独立复核处理。
    </p>
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
          <ElSelect v-model="segment.label" placeholder="选择步骤标签" :aria-label="`标注片段 ${index + 1} 的标签`">
            <ElOption
              v-for="label in sortedLabels"
              :key="label.label_id"
              :label="label.display_name"
              :value="label.label_id"
            />
          </ElSelect>
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
