import {
  App as AntdApp,
  Button,
  Card,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Table,
  Tag,
} from 'antd'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'

import {
  AnnotationTaskApiError,
  getAnnotationLabelSchema,
  listAnnotationTasks,
  recheckAnnotationPostCleaning,
  reviewAnnotationRevision,
  submitAnnotationRevision,
  type AnnotationLabelSchema,
  type AnnotationSegment,
  type AnnotationTask,
  type AnnotationTaskProgress,
} from '@/api/annotation'
import { paths } from '@/app/paths'
import { formatDateTime } from '@/catalog-presentation'

interface DraftAnnotationSegment extends AnnotationSegment {
  readonly draftId: number
}
const postCleaningReasonLabels: Readonly<Record<string, string>> = {
  REVIEW_NOT_APPROVED: '未绑定已通过的管理员复核',
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
let nextDraftSegmentId = 0
function createDraftSegment(): DraftAnnotationSegment {
  nextDraftSegmentId += 1
  return { draftId: nextDraftSegmentId, start_ms: 0, end_ms: 1000, label: '' }
}
function taskStatusLabel(task: AnnotationTask) {
  if (task.status === 'COMPLETED') return '已完成'
  if (task.status === 'SUBMITTED') return '已提交'
  return task.annotation_revision?.review?.decision === 'REJECTED' ? '待修改' : '已分配'
}
function taskStatusColor(task: AnnotationTask) {
  return task.status === 'COMPLETED'
    ? 'success'
    : task.status === 'SUBMITTED'
      ? 'default'
      : 'warning'
}
function postCleaningPresentation(task: AnnotationTask) {
  const revision = task.annotation_revision
  if (revision?.review?.decision !== 'APPROVED') return null
  const result = revision.post_cleaning_result
  const reasons = (result?.reason_codes ?? []).map(
    (code) =>
      postCleaningReasonLabels[code] ??
      `其他清洗原因（${/^[A-Z][A-Z0-9_]{0,63}$/.test(code) ? code : 'UNKNOWN_REASON'}）`,
  )
  if (result?.outcome === 'PASS')
    return {
      label: '当前规则检查通过',
      color: 'success',
      description: '尚未进入训练数据集。',
      reasons,
    }
  if (result?.outcome === 'WARNING')
    return {
      label: '后置清洗待确认',
      color: 'warning',
      description: '不会自动准入数据集，需补充事实或按后续准入流程确认。',
      reasons,
    }
  if (result?.outcome === 'REJECT')
    return {
      label: '后置清洗不通过',
      color: 'error',
      description: '当前版本默认不准入数据集；视频资产未被删除。',
      reasons,
    }
  return {
    label: '后置清洗未执行',
    color: 'default',
    description: '尚未形成可用于后续准入判断的结果。',
    reasons,
  }
}

export default function AnnotationTaskListView() {
  const { projectId = '' } = useParams()
  const { message, modal } = AntdApp.useApp()
  const [items, setItems] = useState<AnnotationTask[]>([])
  const [progress, setProgress] = useState<AnnotationTaskProgress | null>(null)
  const [labelSchema, setLabelSchema] = useState<AnnotationLabelSchema | null>(null)
  const sortedLabels = useMemo(
    () =>
      [...(labelSchema?.labels ?? [])].sort(
        (a, b) => a.sort_order - b.sort_order || a.label_id.localeCompare(b.label_id),
      ),
    [labelSchema],
  )
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [errorCode, setErrorCode] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const [selectedTask, setSelectedTask] = useState<AnnotationTask | null>(null)
  const [isSubmissionOpen, setIsSubmissionOpen] = useState(false)
  const [segments, setSegments] = useState<DraftAnnotationSegment[]>([])
  const [note, setNote] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [reviewingRevisionId, setReviewingRevisionId] = useState<string | null>(null)
  const [recheckingRevisionId, setRecheckingRevisionId] = useState<string | null>(null)
  const activeRequest = useRef<AbortController | null>(null)

  const loadTasks = useCallback(async () => {
    activeRequest.current?.abort()
    const request = new AbortController()
    activeRequest.current = request
    setLoadState('loading')
    setProgress(null)
    setLabelSchema(null)
    setErrorCode('')
    setErrorMessage('')
    try {
      const [taskList, schema] = await Promise.all([
        listAnnotationTasks(projectId, request.signal),
        getAnnotationLabelSchema(projectId, request.signal),
      ])
      if (activeRequest.current !== request) return
      setItems(taskList.items)
      setProgress(taskList.progress)
      setLabelSchema(schema)
      setLoadState('success')
    } catch (error) {
      if (request.signal.aborted || activeRequest.current !== request) return
      setLoadState('error')
      setErrorCode(
        error instanceof AnnotationTaskApiError
          ? error.errorCode
          : 'ANNOTATION_TASK_REQUEST_FAILED',
      )
      setErrorMessage(
        error instanceof AnnotationTaskApiError
          ? error.message
          : '暂时无法读取配药动作标注，请稍后重试。',
      )
    } finally {
      if (activeRequest.current === request) activeRequest.current = null
    }
  }, [projectId])
  useEffect(() => {
    const timer = window.setTimeout(() => void loadTasks(), 0)
    return () => {
      window.clearTimeout(timer)
      activeRequest.current?.abort()
    }
  }, [loadTasks])

  function openSubmission(task: AnnotationTask) {
    if (!task.can_submit || task.status !== 'ASSIGNED') return
    if (
      !labelSchema ||
      labelSchema.label_schema_id !== task.label_schema_id ||
      labelSchema.schema_digest !== task.label_schema_digest
    ) {
      void message.error('任务绑定的标签规范与当前项目不一致，请刷新后重试。')
      return
    }
    setSelectedTask(task)
    setIsSubmissionOpen(true)
    setSegments([createDraftSegment()])
    setNote('')
  }
  function updateSegment(index: number, patch: Partial<AnnotationSegment>) {
    setSegments((current) =>
      current.map((segment, currentIndex) =>
        currentIndex === index ? { ...segment, ...patch } : segment,
      ),
    )
  }
  async function submit() {
    const task = selectedTask
    const schema = labelSchema
    if (!task || !schema || isSubmitting) return
    if (
      schema.label_schema_id !== task.label_schema_id ||
      schema.schema_digest !== task.label_schema_digest
    ) {
      void message.error('任务绑定的标签规范已发生变化，请关闭窗口并刷新后重试。')
      return
    }
    const validSegments = segments.map(({ start_ms, end_ms, label }) => ({
      start_ms,
      end_ms,
      label: label.trim(),
    }))
    if (
      validSegments.some(
        (segment) => !segment.label || segment.start_ms < 0 || segment.end_ms <= segment.start_ms,
      )
    ) {
      void message.error('请填写标签，并确保每个片段的结束时间晚于开始时间。')
      return
    }
    setIsSubmitting(true)
    try {
      await submitAnnotationRevision(projectId, task.annotation_task_id, validSegments, note)
      void message.success('配药动作标注已提交，等待管理员复核。')
      setIsSubmissionOpen(false)
      await loadTasks()
    } catch (error) {
      void message.error(
        error instanceof AnnotationTaskApiError
          ? error.message
          : '配药动作标注暂未提交，请稍后重试。',
      )
    } finally {
      setIsSubmitting(false)
    }
  }
  function review(task: AnnotationTask, decision: 'APPROVED' | 'REJECTED') {
    const revision = task.annotation_revision
    if (!revision || !task.can_review || reviewingRevisionId) return
    const approved = decision === 'APPROVED'
    modal.confirm({
      title: approved ? '通过配药动作标注复核' : '配药动作标注复核不通过',
      content: approved
        ? '确认该时间段标注与视频画面一致，并将其作为通过的人工配药动作标注？'
        : '确认该配药动作标注不符合要求？本次将记录为不通过，并退回给标注人修改。',
      okText: approved ? '确认通过' : '确认不通过',
      cancelText: '取消',
      okButtonProps: { danger: !approved },
      async onOk() {
        setReviewingRevisionId(revision.annotation_revision_id)
        try {
          await reviewAnnotationRevision(projectId, revision.annotation_revision_id, decision)
          void message.success(
            approved ? '配药动作标注已通过管理员复核。' : '已记录配药动作标注不通过。',
          )
          await loadTasks()
        } catch (error) {
          void message.error(
            error instanceof AnnotationTaskApiError ? error.message : '复核暂未提交，请稍后重试。',
          )
          throw error
        } finally {
          setReviewingRevisionId(null)
        }
      },
    })
  }
  function recheckPostCleaning(task: AnnotationTask) {
    const revision = task.annotation_revision
    if (!revision || !task.can_recheck_post_cleaning || recheckingRevisionId) return
    modal.confirm({
      title: '重新核对清洗',
      content:
        '将按当前人工质量、视频和标注事实重新生成后置清洗结果；历史结果会保留，不会修改已通过的标注。',
      okText: '确认重新核对',
      cancelText: '取消',
      async onOk() {
        setRecheckingRevisionId(revision.annotation_revision_id)
        try {
          await recheckAnnotationPostCleaning(projectId, revision.annotation_revision_id)
          void message.success('已按当前事实核对清洗结果。')
          await loadTasks()
        } catch (error) {
          void message.error(
            error instanceof AnnotationTaskApiError
              ? error.message
              : '暂时无法重新核对清洗，请稍后重试。',
          )
          throw error
        } finally {
          setRecheckingRevisionId(null)
        }
      },
    })
  }

  return (
    <>
      <section className="page-heading" aria-labelledby="annotation-task-title">
        <div>
          <p className="app-eyebrow">固定机位视频 · 人工确认</p>
          <h1 id="annotation-task-title">配药动作标注</h1>
          <p>在配药舱固定机位视频上记录动作时间段；提交后由当前项目管理员复核。</p>
        </div>
        <Button loading={loadState === 'loading'} onClick={() => void loadTasks()}>
          刷新
        </Button>
      </section>
      {loadState === 'loading' ? (
        <div className="catalog-notice" role="status">
          正在读取配药动作标注…
        </div>
      ) : null}
      {loadState === 'error' ? (
        <div className="catalog-error" role="alert">
          <strong>{errorCode}</strong>
          <span>{errorMessage}</span>
          <Button onClick={() => void loadTasks()}>重试</Button>
        </div>
      ) : null}
      {loadState === 'success' && progress ? (
        <dl
          className="annotation-progress"
          aria-label={
            progress.scope === 'PROJECT' ? '项目配药动作标注进度' : '我的配药动作标注进度'
          }
        >
          <div>
            <dt>{progress.scope === 'PROJECT' ? '项目任务' : '我的任务'}</dt>
            <dd>{progress.total}</dd>
          </div>
          <div>
            <dt>待提交</dt>
            <dd>{progress.awaiting_submission}</dd>
          </div>
          <div>
            <dt>待修改</dt>
            <dd>{progress.rework_required}</dd>
          </div>
          <div>
            <dt>待复核</dt>
            <dd>{progress.awaiting_review}</dd>
          </div>
          <div>
            <dt>已完成</dt>
            <dd>{progress.completed}</dd>
          </div>
        </dl>
      ) : null}
      {loadState === 'success' && items.length ? (
        <Card className="table-card" variant="outlined">
          <Table<AnnotationTask>
            dataSource={items}
            rowKey="annotation_task_id"
            pagination={false}
            scroll={{ x: 1250 }}
            columns={[
              {
                title: '视频资产',
                width: 270,
                render: (_, row) => (
                  <Link
                    className="text-link mono-wrap"
                    to={paths.asset(projectId, row.clip_asset_id)}
                  >
                    {row.clip_asset_id}
                  </Link>
                ),
              },
              {
                title: '状态',
                width: 130,
                render: (_, row) => <Tag color={taskStatusColor(row)}>{taskStatusLabel(row)}</Tag>,
              },
              {
                title: '动作标注、复核与清洗',
                width: 310,
                render: (_, row) => {
                  const revision = row.annotation_revision
                  const presentation = postCleaningPresentation(row)
                  return revision ? (
                    <>
                      <div>已提交 · {revision.annotation_document.segments.length} 个时间段</div>
                      {revision.review ? (
                        <>
                          <Tag
                            color={revision.review.decision === 'APPROVED' ? 'success' : 'error'}
                          >
                            {revision.review.decision === 'APPROVED' ? '复核通过' : '复核不通过'}
                          </Tag>
                          {revision.review.reviewed_by === revision.submitted_by ? (
                            <Tag color="gold">管理员自审（SELF_ADMIN_APPROVAL）</Tag>
                          ) : null}
                        </>
                      ) : (
                        <span className="muted-text">等待管理员复核</span>
                      )}
                      {presentation ? (
                        <div>
                          <Tag color={presentation.color}>{presentation.label}</Tag>
                          <span className="cell-secondary">{presentation.description}</span>
                          {presentation.reasons.length ? (
                            <span className="cell-secondary">
                              原因：{presentation.reasons.join('；')}
                            </span>
                          ) : null}
                        </div>
                      ) : null}
                    </>
                  ) : (
                    <span className="muted-text">尚未提交</span>
                  )
                },
              },
              { title: '执行人', dataIndex: 'assignee_actor_id', width: 180 },
              { title: '创建人', dataIndex: 'created_by', width: 180 },
              { title: '创建时间', width: 190, render: (_, row) => formatDateTime(row.created_at) },
              {
                title: '操作',
                width: 170,
                fixed: 'right',
                render: (_, row) => (
                  <div className="table-actions">
                    {row.can_submit && row.status === 'ASSIGNED' ? (
                      <Button
                        type="link"
                        aria-label={`提交视频 ${row.clip_asset_id} 的配药动作标注`}
                        onClick={() => openSubmission(row)}
                      >
                        提交配药动作标注
                      </Button>
                    ) : row.can_review && row.annotation_revision ? (
                      <>
                        <Button
                          type="link"
                          aria-label={`通过视频 ${row.clip_asset_id} 的配药动作标注复核`}
                          loading={
                            reviewingRevisionId === row.annotation_revision.annotation_revision_id
                          }
                          onClick={() => review(row, 'APPROVED')}
                        >
                          通过
                        </Button>
                        <Button
                          type="link"
                          danger
                          aria-label={`不通过视频 ${row.clip_asset_id} 的配药动作标注复核`}
                          disabled={reviewingRevisionId !== null}
                          onClick={() => review(row, 'REJECTED')}
                        >
                          不通过
                        </Button>
                      </>
                    ) : row.can_recheck_post_cleaning && row.annotation_revision ? (
                      <Button
                        type="link"
                        aria-label={`重新核对视频 ${row.clip_asset_id} 的后置清洗`}
                        loading={
                          recheckingRevisionId === row.annotation_revision.annotation_revision_id
                        }
                        onClick={() => recheckPostCleaning(row)}
                      >
                        重新核对清洗
                      </Button>
                    ) : (
                      <span>—</span>
                    )}
                  </div>
                ),
              },
            ]}
          />
        </Card>
      ) : null}
      {loadState === 'success' && !items.length ? (
        <Empty description="当前没有可处理的配药动作标注。管理员可在已判定合格的视频详情中创建并分配任务。" />
      ) : null}
      <Modal
        open={isSubmissionOpen}
        title="提交配药动作时间段"
        width={720}
        footer={null}
        onCancel={() => setIsSubmissionOpen(false)}
        destroyOnHidden
      >
        <p className="form-hint">
          按项目标签规范记录一个或多个配药动作时间段。提交后内容不可覆盖，后续由管理员复核处理。
        </p>
        <Form layout="vertical" onFinish={() => void submit()}>
          {segments.map((segment, index) => (
            <div
              key={segment.draftId}
              className="annotation-segment-row"
              role="group"
              aria-label={`标注片段 ${index + 1}`}
            >
              <Form.Item label="开始（毫秒）">
                <InputNumber
                  min={0}
                  step={100}
                  value={segment.start_ms}
                  onChange={(value) => updateSegment(index, { start_ms: value ?? 0 })}
                />
              </Form.Item>
              <Form.Item label="结束（毫秒）">
                <InputNumber
                  min={1}
                  step={100}
                  value={segment.end_ms}
                  onChange={(value) => updateSegment(index, { end_ms: value ?? 1 })}
                />
              </Form.Item>
              <Form.Item label="标签">
                <Select
                  value={segment.label || undefined}
                  placeholder="选择步骤标签"
                  aria-label={`标注片段 ${index + 1} 的标签`}
                  onChange={(value) => updateSegment(index, { label: value })}
                  options={sortedLabels.map((label) => ({
                    value: label.label_id,
                    label: label.display_name,
                  }))}
                />
              </Form.Item>
              <Button
                aria-label={`移除标注片段 ${index + 1}`}
                disabled={segments.length === 1}
                onClick={() =>
                  setSegments((current) =>
                    current.filter((_, currentIndex) => currentIndex !== index),
                  )
                }
              >
                移除
              </Button>
            </div>
          ))}
          <Button onClick={() => setSegments((current) => [...current, createDraftSegment()])}>
            新增片段
          </Button>
          <Form.Item label="备注（可选）">
            <Input.TextArea
              value={note}
              maxLength={1000}
              rows={3}
              onChange={(event) => setNote(event.target.value)}
            />
          </Form.Item>
          <div className="detail-actions">
            <Button onClick={() => setIsSubmissionOpen(false)}>取消</Button>
            <Button htmlType="submit" type="primary" loading={isSubmitting}>
              提交配药动作标注
            </Button>
          </div>
        </Form>
      </Modal>
    </>
  )
}
