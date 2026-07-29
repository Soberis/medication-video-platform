import { Alert, App as AntdApp, Button, Card, Empty, Form, Select, Table, Tag } from 'antd'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'

import { AdminApiError, listPlatformAccounts, type PlatformAccount } from '@/api/admin'
import { CatalogApiError } from '@/api/catalog'
import {
  getProject,
  listProjectMemberships,
  removeProjectMembership,
  type ProjectMembership,
  type ProjectRole,
  upsertProjectMembership,
} from '@/api/projects'
import { paths } from '@/app/paths'

interface MembershipFields {
  actorId: string
  role: ProjectRole
}

export default function ProjectMembershipView() {
  const { projectId = '' } = useParams()
  const { message, modal } = AntdApp.useApp()
  const [form] = Form.useForm<MembershipFields>()
  const [accounts, setAccounts] = useState<PlatformAccount[]>([])
  const [memberships, setMemberships] = useState<ProjectMembership[]>([])
  const [projectRole, setProjectRole] = useState<ProjectRole | null>(null)
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [errorMessage, setErrorMessage] = useState('')
  const [isMutating, setIsMutating] = useState(false)
  const activeRequest = useRef<AbortController | null>(null)
  const canManageMembers = projectRole === 'ADMIN'
  const availableAccounts = useMemo(() => accounts.filter((account) => account.enabled), [accounts])

  const loadMemberships = useCallback(async () => {
    activeRequest.current?.abort()
    const request = new AbortController()
    activeRequest.current = request
    setLoadState('loading')
    setErrorMessage('')
    try {
      const [project, listedMemberships, listedAccounts] = await Promise.all([
        getProject(projectId, request.signal),
        listProjectMemberships(projectId, request.signal),
        listPlatformAccounts(request.signal),
      ])
      if (activeRequest.current !== request) return
      setProjectRole(project.current_role)
      setMemberships(listedMemberships)
      setAccounts(listedAccounts)
      setLoadState('success')
    } catch (error) {
      if (request.signal.aborted || activeRequest.current !== request) return
      setMemberships([])
      setAccounts([])
      setProjectRole(null)
      setLoadState('error')
      setErrorMessage(
        error instanceof AdminApiError || error instanceof CatalogApiError
          ? error.message
          : '暂时无法读取项目成员。',
      )
    } finally {
      if (activeRequest.current === request) activeRequest.current = null
    }
  }, [projectId])

  useEffect(() => {
    const timer = window.setTimeout(() => void loadMemberships(), 0)
    return () => {
      window.clearTimeout(timer)
      activeRequest.current?.abort()
    }
  }, [loadMemberships])

  async function saveMembership(values: MembershipFields) {
    if (!canManageMembers || isMutating || !values.actorId) return
    setIsMutating(true)
    try {
      await upsertProjectMembership(projectId, values.actorId, values.role)
      void message.success('项目成员已保存。')
      form.resetFields()
      await loadMemberships()
    } catch (error) {
      void message.error(
        error instanceof CatalogApiError ? error.message : '项目成员保存未完成，请稍后重试。',
      )
    } finally {
      setIsMutating(false)
    }
  }

  function removeMembership(membership: ProjectMembership) {
    if (!canManageMembers || isMutating) return
    modal.confirm({
      title: '确认移除项目成员',
      content: `移除“${membership.actor_id}”后，该账号将看不到当前项目及其视频数据。`,
      okText: '移除成员',
      cancelText: '取消',
      okButtonProps: { danger: true },
      async onOk() {
        setIsMutating(true)
        try {
          await removeProjectMembership(projectId, membership.actor_id)
          void message.success('项目成员已移除。')
          await loadMemberships()
        } catch (error) {
          void message.error(
            error instanceof CatalogApiError ? error.message : '项目成员移除未完成，请稍后重试。',
          )
          throw error
        } finally {
          setIsMutating(false)
        }
      },
    })
  }

  return (
    <>
      <nav className="breadcrumb" aria-label="面包屑">
        <Link to={paths.projects}>项目</Link>
        <span aria-hidden="true">/</span>
        <span>项目成员</span>
      </nav>
      <section className="page-heading" aria-labelledby="members-title">
        <div>
          <p className="app-eyebrow">项目访问范围</p>
          <h1 id="members-title">项目成员</h1>
          <p>分配当前项目的访问范围；项目角色不改变账号的全局身份。</p>
        </div>
        <Button loading={loadState === 'loading'} onClick={() => void loadMemberships()}>
          刷新
        </Button>
      </section>
      {canManageMembers ? (
        <Card className="detail-card" title="添加或更新成员" variant="outlined">
          <Form<MembershipFields>
            form={form}
            className="membership-form"
            layout="vertical"
            initialValues={{ role: 'MEMBER' }}
            onFinish={(values) => void saveMembership(values)}
          >
            <Form.Item label="平台账号" name="actorId">
              <Select
                showSearch
                placeholder="选择已启用的内部账号"
                options={availableAccounts.map((account) => ({
                  value: account.username,
                  label: `${account.username}（${account.role === 'ADMIN' ? '管理员' : '普通用户'}）`,
                }))}
              />
            </Form.Item>
            <Form.Item label="项目角色" name="role">
              <Select
                options={[
                  { label: '普通用户', value: 'MEMBER' },
                  { label: '项目管理员', value: 'ADMIN' },
                ]}
              />
            </Form.Item>
            <Button htmlType="submit" type="primary" loading={isMutating}>
              保存成员
            </Button>
          </Form>
        </Card>
      ) : loadState === 'success' ? (
        <Alert
          className="catalog-alert"
          title="当前不是该项目管理员"
          description="只有当前项目的管理员可以调整成员。"
          type="warning"
          showIcon
        />
      ) : null}
      {loadState === 'loading' ? (
        <div className="catalog-notice" role="status">
          正在读取项目成员…
        </div>
      ) : null}
      {loadState === 'error' ? (
        <div className="catalog-error" role="alert">
          <span>{errorMessage}</span>
          <Button onClick={() => void loadMemberships()}>重试</Button>
        </div>
      ) : null}
      {loadState === 'success' && memberships.length > 0 ? (
        <Card className="table-card" variant="outlined">
          <Table<ProjectMembership>
            dataSource={memberships}
            rowKey="membership_id"
            pagination={false}
            columns={[
              { title: '账号', dataIndex: 'actor_id' },
              {
                title: '项目角色',
                width: 140,
                render: (_, row) => (
                  <Tag color={row.role === 'ADMIN' ? 'blue' : 'default'}>
                    {row.role === 'ADMIN' ? '项目管理员' : '普通用户'}
                  </Tag>
                ),
              },
              { title: '状态', width: 110, render: () => '有效' },
              ...(canManageMembers
                ? [
                    {
                      title: '操作',
                      width: 120,
                      render: (_: unknown, row: ProjectMembership) => (
                        <Button
                          type="link"
                          danger
                          disabled={isMutating}
                          aria-label={`移除项目成员：${row.actor_id}`}
                          onClick={() => removeMembership(row)}
                        >
                          移除
                        </Button>
                      ),
                    },
                  ]
                : []),
            ]}
          />
        </Card>
      ) : null}
      {loadState === 'success' && memberships.length === 0 ? (
        <Empty description="当前项目尚未分配成员。" />
      ) : null}
    </>
  )
}
