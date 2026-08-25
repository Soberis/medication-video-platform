import { App as AntdApp, Button, Form, Input, Table } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'

import {
  AdminApiError,
  changePlatformAccountEnabled,
  createPlatformAccount,
  listPlatformAccounts,
  type PlatformAccount,
} from '@/api/admin'
import { PageHeader } from '@/components/PageHeader'
import { StatusBadge } from '@/components/StatusBadge'
import { ErrorState, LoadingState } from '@/components/WorkspaceFeedback'
import { Section } from '@/components/workspace/Section'

interface AccountFields {
  username: string
  password: string
}

const usernamePattern = /^[A-Za-z0-9][A-Za-z0-9._:@-]{2,127}$/
const usernameConstraintMessage = '账号名需为 3 至 128 位字母、数字或 . _ : @ -。'
const passwordConstraintMessage = '初始密码长度需为 12 至 72 个字符。'

export default function AccountManagementView() {
  const { message, modal } = AntdApp.useApp()
  const [form] = Form.useForm<AccountFields>()
  const [accounts, setAccounts] = useState<PlatformAccount[]>([])
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [errorMessage, setErrorMessage] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [isChanging, setIsChanging] = useState(false)
  const activeRequest = useRef<AbortController | null>(null)

  const loadAccounts = useCallback(async () => {
    activeRequest.current?.abort()
    const request = new AbortController()
    activeRequest.current = request
    setLoadState('loading')
    setErrorMessage('')
    try {
      const listedAccounts = await listPlatformAccounts(request.signal)
      if (activeRequest.current !== request) return
      setAccounts(listedAccounts)
      setLoadState('success')
    } catch (error) {
      if (request.signal.aborted || activeRequest.current !== request) return
      setAccounts([])
      setLoadState('error')
      setErrorMessage(
        error instanceof AdminApiError ? error.message : '暂时无法读取平台账号，请稍后重试。',
      )
    } finally {
      if (activeRequest.current === request) activeRequest.current = null
    }
  }, [])

  useEffect(() => {
    const timer = window.setTimeout(() => void loadAccounts(), 0)
    return () => {
      window.clearTimeout(timer)
      activeRequest.current?.abort()
    }
  }, [loadAccounts])

  async function createAccount(values: AccountFields) {
    if (isSubmitting) return
    const username = values.username.trim()
    if (!usernamePattern.test(username)) {
      void message.error(usernameConstraintMessage)
      return
    }
    if (values.password.length < 12 || values.password.length > 72) {
      void message.error(passwordConstraintMessage)
      return
    }
    setIsSubmitting(true)
    try {
      await createPlatformAccount(username, values.password)
      form.resetFields()
      void message.success('成员账号已创建，请在项目成员页分配项目角色。')
      await loadAccounts()
    } catch (error) {
      void message.error(
        error instanceof AdminApiError ? error.message : '账号创建未完成，请稍后重试。',
      )
    } finally {
      setIsSubmitting(false)
    }
  }

  function toggleEnabled(account: PlatformAccount) {
    if (isChanging || account.role !== 'MEMBER') return
    const nextEnabled = !account.enabled
    modal.confirm({
      title: nextEnabled ? '确认启用账号' : '确认停用账号',
      content: nextEnabled
        ? `启用“${account.username}”后，该账号可以再次登录。`
        : `停用“${account.username}”会阻止其创建新的登录会话；已有会话按当前会话策略自然失效。`,
      okText: nextEnabled ? '启用账号' : '停用账号',
      cancelText: '取消',
      okButtonProps: { danger: !nextEnabled },
      async onOk() {
        setIsChanging(true)
        try {
          await changePlatformAccountEnabled(account.username, nextEnabled)
          void message.success(nextEnabled ? '账号已启用。' : '账号已停用。')
          await loadAccounts()
        } catch (error) {
          void message.error(
            error instanceof AdminApiError ? error.message : '账号状态更新未完成，请稍后重试。',
          )
          throw error
        } finally {
          setIsChanging(false)
        }
      },
    })
  }

  return (
    <>
      <PageHeader
        eyebrow="内部访问控制"
        titleId="accounts-title"
        title="平台账号"
        description="管理平台账号；账号加入项目后才能访问项目数据。"
        actions={
          <Button loading={loadState === 'loading'} onClick={() => void loadAccounts()}>
            刷新
          </Button>
        }
      />
      <Section
        className="account-create-panel working-surface"
        title="创建成员账号"
        titleId="account-create-title"
      >
        <Form<AccountFields>
          form={form}
          layout="vertical"
          className="account-form"
          onFinish={(values) => void createAccount(values)}
        >
          <Form.Item
            label="账号名"
            name="username"
            validateFirst
            rules={[
              { required: true, whitespace: true, message: '请输入账号名。' },
              {
                validator: (_, value: unknown) => {
                  if (typeof value !== 'string' || value.trim() === '') return Promise.resolve()
                  return usernamePattern.test(value.trim())
                    ? Promise.resolve()
                    : Promise.reject(new Error(usernameConstraintMessage))
                },
              },
            ]}
          >
            <Input autoComplete="username" maxLength={128} />
          </Form.Item>
          <Form.Item
            label="初始密码"
            name="password"
            validateFirst
            rules={[
              { required: true, message: '请输入初始密码。' },
              {
                validator: (_, value: unknown) => {
                  if (typeof value !== 'string' || value.length === 0) return Promise.resolve()
                  return value.length >= 12 && value.length <= 72
                    ? Promise.resolve()
                    : Promise.reject(new Error(passwordConstraintMessage))
                },
              },
            ]}
          >
            <Input.Password autoComplete="new-password" maxLength={72} />
          </Form.Item>
          <p className="form-hint">初始密码不会写入账号列表、日志或审计记录。</p>
          <Button htmlType="submit" type="primary" loading={isSubmitting}>
            创建账号
          </Button>
        </Form>
      </Section>
      {loadState === 'loading' ? <LoadingState>正在读取平台账号…</LoadingState> : null}
      {loadState === 'error' ? (
        <ErrorState onRetry={() => void loadAccounts()}>{errorMessage}</ErrorState>
      ) : null}
      {loadState === 'success' ? (
        <Section
          className="table-card account-table-card working-surface"
          title="平台账号列表"
          titleId="account-list-title"
        >
          <Table<PlatformAccount>
            dataSource={accounts}
            rowKey="username"
            pagination={false}
            columns={[
              { title: '账号名', dataIndex: 'username' },
              {
                title: '全局角色',
                width: 130,
                render: (_, row) => (
                  <StatusBadge tone={row.role === 'ADMIN' ? 'info' : 'default'}>
                    {row.role === 'ADMIN' ? '管理员' : '成员'}
                  </StatusBadge>
                ),
              },
              {
                title: '登录状态',
                width: 120,
                render: (_, row) => (
                  <StatusBadge tone={row.enabled ? 'success' : 'error'}>
                    {row.enabled ? '启用' : '停用'}
                  </StatusBadge>
                ),
              },
              {
                title: '操作',
                width: 130,
                render: (_, row) =>
                  row.role === 'MEMBER' ? (
                    <Button
                      type="link"
                      danger={row.enabled}
                      disabled={isChanging}
                      onClick={() => toggleEnabled(row)}
                    >
                      {row.enabled ? '停用' : '启用'}
                    </Button>
                  ) : (
                    <span className="cell-secondary">不可停用</span>
                  ),
              },
            ]}
          />
        </Section>
      ) : null}
    </>
  )
}
