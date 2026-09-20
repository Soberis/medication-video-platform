import { App as AntdApp, Button, Form, Input, Modal, Table } from 'antd'
import { Lock, Plus, User } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'

import {
  AdminApiError,
  changePlatformAccountEnabled,
  createPlatformAccount,
  listPlatformAccounts,
  type PlatformAccount,
} from '@/api/admin'
import { PageHeader } from '@/components/PageHeader'
import { RefreshButton } from '@/components/RefreshButton'
import { StatusBadge } from '@/components/StatusBadge'
import { EmptyState, ErrorState, LoadingState } from '@/components/WorkspaceFeedback'

interface AccountFields {
  username: string
  password: string
}

const usernamePattern = /^[A-Za-z0-9][A-Za-z0-9._:@-]{2,127}$/
const usernameConstraintMessage = '账号需为 3 至 128 位字母、数字或 . _ : @ -。'
const passwordConstraintMessage = '初始密码长度需为 12 至 72 个字符。'

export default function AccountManagementView() {
  const { message, modal } = AntdApp.useApp()
  const [form] = Form.useForm<AccountFields>()
  const [accounts, setAccounts] = useState<PlatformAccount[]>([])
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [errorMessage, setErrorMessage] = useState('')
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false)
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

  function handleOpenCreate() {
    form.resetFields()
    setIsCreateModalOpen(true)
  }

  function handleCloseCreate() {
    setIsCreateModalOpen(false)
    form.resetFields()
  }

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
      handleCloseCreate()
      void message.success('账号已创建，请在项目成员页分配项目角色。')
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
    <div className="account-management-page">
      <nav className="breadcrumb" aria-label="面包屑导航">
        <span>设置</span>
        <span className="breadcrumb-separator" aria-hidden="true">
          /
        </span>
        <span>平台账号</span>
      </nav>
      <PageHeader
        titleId="accounts-title"
        title="平台账号"
        description="平台账号加入项目后，才可访问项目数据。"
        actions={
          <>
            <Button type="primary" icon={<Plus size={14} />} onClick={handleOpenCreate}>
              创建账号
            </Button>
            <RefreshButton loading={loadState === 'loading'} onClick={() => void loadAccounts()} />
          </>
        }
      />

      {loadState === 'loading' ? <LoadingState>正在加载平台账号…</LoadingState> : null}

      {loadState === 'error' ? (
        <ErrorState onRetry={() => void loadAccounts()}>{errorMessage}</ErrorState>
      ) : null}

      {loadState === 'success' && accounts.length === 0 ? (
        <EmptyState description="当前没有平台账号。">
          <Button type="primary" icon={<Plus size={14} />} onClick={handleOpenCreate}>
            创建账号
          </Button>
        </EmptyState>
      ) : null}

      {loadState === 'success' && accounts.length > 0 ? (
        <div className="table-page-container">
          <h2 className="visually-hidden" id="account-list-title">
            平台账号列表
          </h2>
          <div className="table-surface account-table-card">
            <Table<PlatformAccount>
              dataSource={accounts}
              rowKey="username"
              pagination={false}
              tableLayout="fixed"
              columns={[
                {
                  title: '平台账号',
                  dataIndex: 'username',
                  width: 240,
                  render: (username: string) => (
                    <span className="account-user-cell">
                      <span className="account-user-avatar" aria-hidden="true">
                        {username.slice(0, 1).toUpperCase()}
                      </span>
                      <strong className="account-username" title={username}>
                        {username}
                      </strong>
                    </span>
                  ),
                },
                {
                  title: '全局角色',
                  width: 120,
                  render: (_, row) => (
                    <StatusBadge tone={row.role === 'ADMIN' ? 'information' : 'neutral'}>
                      {row.role === 'ADMIN' ? '管理员' : '成员'}
                    </StatusBadge>
                  ),
                },
                {
                  title: '登录状态',
                  width: 120,
                  render: (_, row) => (
                    <StatusBadge tone={row.enabled ? 'success' : 'destructive'}>
                      {row.enabled ? '启用' : '停用'}
                    </StatusBadge>
                  ),
                },
                {
                  title: '操作',
                  align: 'right',
                  render: (_, row) =>
                    row.role !== 'ADMIN' ? (
                      <div className="table-cell-actions">
                        <Button
                          size="small"
                          danger={row.enabled}
                          disabled={isChanging}
                          aria-label={`${row.enabled ? '停用' : '启用'}账号：${row.username}`}
                          onClick={() => toggleEnabled(row)}
                        >
                          {row.enabled ? '停用' : '启用'}
                        </Button>
                      </div>
                    ) : (
                      <span className="cell-secondary">管理员不可停用</span>
                    ),
                },
              ]}
            />
          </div>
        </div>
      ) : null}

      <Modal
        title="创建平台账号"
        open={isCreateModalOpen}
        onCancel={handleCloseCreate}
        footer={null}
        destroyOnHidden
        width={560}
      >
        <Form<AccountFields>
          form={form}
          layout="vertical"
          className="management-modal-form"
          onFinish={(values) => void createAccount(values)}
        >
          <Form.Item
            label="账号"
            name="username"
            validateFirst
            rules={[
              { required: true, whitespace: true, message: '请输入账号。' },
              {
                validator: (_, value: unknown) => {
                  if (typeof value !== 'string' || value.trim() === '') return Promise.resolve()
                  return usernamePattern.test(value.trim())
                    ? Promise.resolve()
                    : Promise.reject(new Error(usernameConstraintMessage))
                },
              },
            ]}
            extra="3 至 128 位；仅限字母、数字和 . _ : @ -"
          >
            <Input
              prefix={<User size={14} className="account-field-icon" />}
              autoComplete="username"
              maxLength={128}
              placeholder="输入平台账号"
            />
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
            extra="12 至 72 个字符"
          >
            <Input.Password
              prefix={<Lock size={14} className="account-field-icon" />}
              autoComplete="new-password"
              maxLength={72}
              placeholder="输入初始密码"
            />
          </Form.Item>
          <p className="form-hint">初始密码仅用于创建，不会写入账号列表、日志或审计记录。</p>
          <div className="management-modal-actions">
            <Button onClick={handleCloseCreate}>取消</Button>
            <Button type="primary" htmlType="submit" loading={isSubmitting}>
              确认创建
            </Button>
          </div>
        </Form>
      </Modal>
    </div>
  )
}
