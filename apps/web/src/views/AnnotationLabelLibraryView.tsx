import { Alert, App as AntdApp, Button, Form, Input, Table, Tag } from 'antd'
import { Plus, RefreshCw } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'

import {
  AnnotationTaskApiError,
  createAnnotationLabel,
  getAnnotationLabelSchema,
  type AnnotationLabelSchema,
} from '@/api/annotation'
import { PageHeader } from '@/components/PageHeader'
import { ErrorState, LoadingState } from '@/components/WorkspaceFeedback'
import { Section } from '@/components/workspace/Section'

interface LabelFields {
  labelId: string
  displayName: string
  description: string
}

export default function AnnotationLabelLibraryView() {
  const { message } = AntdApp.useApp()
  const [form] = Form.useForm<LabelFields>()
  const [schema, setSchema] = useState<AnnotationLabelSchema | null>(null)
  const [state, setState] = useState<'loading' | 'success' | 'error'>('loading')
  const [saving, setSaving] = useState(false)
  const requestRef = useRef<AbortController | null>(null)

  const load = useCallback(async () => {
    requestRef.current?.abort()
    const request = new AbortController()
    requestRef.current = request
    setState('loading')
    try {
      const result = await getAnnotationLabelSchema(null, request.signal)
      if (requestRef.current === request) {
        setSchema(result)
        setState('success')
      }
    } catch {
      if (!request.signal.aborted && requestRef.current === request) setState('error')
    } finally {
      if (requestRef.current === request) requestRef.current = null
    }
  }, [])

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0)
    return () => {
      window.clearTimeout(timer)
      requestRef.current?.abort()
    }
  }, [load])

  async function submit(values: LabelFields) {
    if (saving) return
    setSaving(true)
    try {
      const result = await createAnnotationLabel(null, {
        label_id: values.labelId.trim(),
        display_name: values.displayName.trim(),
        description: values.description.trim(),
      })
      setSchema(result)
      form.resetFields()
      void message.success('标签已发布为新的不可变标注规范。')
    } catch (error) {
      void message.error(
        error instanceof AnnotationTaskApiError ? error.message : '标签发布未完成，请稍后重试。',
      )
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <PageHeader
        eyebrow="全局标注规范"
        title="标签库"
        description="新增标签会发布新的不可变规范；已有标注任务和导出数据继续保留原规范。"
        actions={
          <Button
            type="text"
            icon={<RefreshCw aria-hidden="true" size={14} strokeWidth={1.8} />}
            loading={state === 'loading'}
            onClick={() => void load()}
          >
            刷新
          </Button>
        }
      />
      {state === 'loading' && !schema ? <LoadingState>正在读取标签库…</LoadingState> : null}
      {state === 'error' ? (
        <ErrorState code="ANNOTATION_LABEL_LIBRARY_UNAVAILABLE" onRetry={() => void load()}>
          暂时无法读取标签库，请稍后重试。
        </ErrorState>
      ) : null}
      {schema ? (
        <div className="workspace-stack">
          <Section
            className="table-card working-surface"
            title="当前标签"
            actions={
              <Tag color="blue" bordered={false} className="label-version-tag">
                规范版本: {schema.schema_version}
              </Tag>
            }
          >
            <Table
              rowKey="label_id"
              pagination={false}
              dataSource={schema.labels}
              columns={[
                {
                  title: '稳定标签 ID',
                  dataIndex: 'label_id',
                  width: 220,
                  render: (value: string) => <code className="label-code-tag">{value}</code>,
                },
                {
                  title: '显示名称',
                  dataIndex: 'display_name',
                  width: 180,
                  render: (text: string) => <strong>{text}</strong>,
                },
                { title: '说明', dataIndex: 'description' },
              ]}
            />
          </Section>
          <Section
            className="account-create-panel working-surface"
            title="新增动作标签"
          >
            <p className="form-hint" style={{ marginBottom: 12 }}>发布后不能编辑、删除或重新排序。</p>
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 16 }}
              message="新建标注任务会使用新规范；历史任务继续绑定已有规范。"
            />
            <Form
              form={form}
              layout="vertical"
              className="label-create-form"
              onFinish={(values) => void submit(values)}
            >
              <div className="label-create-form-row">
                <Form.Item
                  label="稳定标签 ID"
                  name="labelId"
                  rules={[
                    { required: true, message: '请输入稳定标签 ID。' },
                    { pattern: /^[a-z][a-z0-9_]{0,63}$/, message: '使用小写字母、数字和下划线。' },
                  ]}
                  extra="如 verify_dose"
                >
                  <Input autoComplete="off" placeholder="例如 verify_dose" />
                </Form.Item>
                <Form.Item
                  label="中文显示名称"
                  name="displayName"
                  rules={[{ required: true, message: '请输入显示名称。' }]}
                  extra="在标注工作区中显示的中文操作名"
                >
                  <Input autoComplete="off" maxLength={128} placeholder="例如 确认剂量" />
                </Form.Item>
              </div>
              <Form.Item
                label="动作说明"
                name="description"
                rules={[{ required: true, message: '请输入说明。' }]}
                extra="详细描述动作开始、进行与结束的标准，供标注员参考"
              >
                <Input.TextArea autoSize={{ minRows: 2, maxRows: 4 }} maxLength={512} placeholder="详细说明动作的标准判定依据…" />
              </Form.Item>
              <Button type="primary" htmlType="submit" loading={saving} icon={<Plus size={14} />}>
                发布标签
              </Button>
            </Form>
          </Section>
        </div>
      ) : null}
    </>
  )
}
