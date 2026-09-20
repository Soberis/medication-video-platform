import { Alert, App as AntdApp, Button, Form, Modal, Select, Table } from 'antd'
import { Plus } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useParams } from 'react-router'

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
import { PageHeader } from '@/components/PageHeader'
import { ProjectBreadcrumb } from '@/components/ProjectBreadcrumb'
import { RefreshButton } from '@/components/RefreshButton'
import { StatusBadge } from '@/components/StatusBadge'
import { EmptyState, ErrorState, LoadingState } from '@/components/WorkspaceFeedback'

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
  const [projectName, setProjectName] = useState<string | null>(null)
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [errorMessage, setErrorMessage] = useState('')
  const [isMutating, setIsMutating] = useState(false)
  const [isModalOpen, setIsModalOpen] = useState(false)
  const [editingActorId, setEditingActorId] = useState<string | null>(null)
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
      setProjectName(project.display_name)
      setMemberships(listedMemberships)
      setAccounts(listedAccounts)
      setLoadState('success')
    } catch (error) {
      if (request.signal.aborted || activeRequest.current !== request) return
      setMemberships([])
      setAccounts([])
      setProjectRole(null)
      setProjectName(null)
      setLoadState('error')
      setErrorMessage(
        error instanceof AdminApiError || error instanceof CatalogApiError
          ? error.message
          : '暂时无法读取项目成员，请稍后重试。',
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

  function handleOpenAdd() {
    setEditingActorId(null)
    form.resetFields()
    form.setFieldsValue({ role: 'MEMBER' })
    setIsModalOpen(true)
  }

  function handleOpenEdit(membership: ProjectMembership) {
    setEditingActorId(membership.actor_id)
    form.setFieldsValue({
      actorId: membership.actor_id,
      role: membership.role,
    })
    setIsModalOpen(true)
  }

  function handleCloseModal() {
    setIsModalOpen(false)
    setEditingActorId(null)
    form.resetFields()
  }

  async function saveMembership(values: MembershipFields) {
    if (!canManageMembers || isMutating || !values.actorId) return
    setIsMutating(true)
    try {
      await upsertProjectMembership(projectId, values.actorId, values.role)
      void message.success('项目成员已保存。')
      handleCloseModal()
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
      content: `移除“${membership.actor_id}”后，该账号将无法查看当前项目及其视频数据。`,
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
    <div className="project-membership-page">
      <ProjectBreadcrumb projectId={projectId} projectName={projectName} switchTarget="members">
        <span>项目成员</span>
      </ProjectBreadcrumb>
      <PageHeader
        titleId="members-title"
        title="项目成员"
        description="维护当前项目的成员和角色。"
        actions={
          <>
            {canManageMembers ? (
              <Button type="primary" icon={<Plus size={14} />} onClick={handleOpenAdd}>
                添加成员
              </Button>
            ) : null}
            <RefreshButton
              loading={loadState === 'loading'}
              onClick={() => void loadMemberships()}
            />
          </>
        }
      />

      {!canManageMembers && loadState === 'success' ? (
        <Alert
          className="catalog-alert"
          title="当前账号无权管理成员"
          description="只有项目管理员可以添加、修改或移除成员。"
          type="warning"
          showIcon
        />
      ) : null}

      {loadState === 'loading' ? <LoadingState>正在加载项目成员…</LoadingState> : null}

      {loadState === 'error' ? (
        <ErrorState onRetry={() => void loadMemberships()}>{errorMessage}</ErrorState>
      ) : null}

      {loadState === 'success' && memberships.length === 0 ? (
        <EmptyState description="当前项目还没有成员。">
          {canManageMembers ? (
            <Button type="primary" icon={<Plus size={14} />} onClick={handleOpenAdd}>
              添加成员
            </Button>
          ) : null}
        </EmptyState>
      ) : null}

      {loadState === 'success' && memberships.length > 0 ? (
        <div className="table-page-container">
          <h2 className="visually-hidden" id="membership-list-title">
            项目成员列表
          </h2>
          <div className="table-surface membership-list-panel">
            <Table<ProjectMembership>
              dataSource={memberships}
              rowKey="membership_id"
              pagination={false}
              tableLayout="fixed"
              columns={[
                {
                  title: '平台账号',
                  dataIndex: 'actor_id',
                  width: 240,
                  render: (actorId: string) => (
                    <span className="account-user-cell">
                      <span className="account-user-avatar" aria-hidden="true">
                        {actorId.slice(0, 1).toUpperCase()}
                      </span>
                      <strong className="account-username" title={actorId}>
                        {actorId}
                      </strong>
                    </span>
                  ),
                },
                {
                  title: '项目角色',
                  width: 140,
                  render: (_, row) => (
                    <StatusBadge tone={row.role === 'ADMIN' ? 'information' : 'neutral'}>
                      {row.role === 'ADMIN' ? '项目管理员' : '成员'}
                    </StatusBadge>
                  ),
                },
                ...(canManageMembers
                  ? [
                      {
                        // 末列不给宽度：宽屏下的多余宽度由它吸收，保证账号与角色相邻。
                        title: '操作',
                        align: 'right' as const,
                        render: (_: unknown, row: ProjectMembership) => (
                          <div className="table-cell-actions">
                            <Button
                              size="small"
                              disabled={isMutating}
                              aria-label={`修改成员角色：${row.actor_id}`}
                              onClick={() => handleOpenEdit(row)}
                            >
                              修改角色
                            </Button>
                            <Button
                              size="small"
                              danger
                              disabled={isMutating}
                              aria-label={`移除项目成员：${row.actor_id}`}
                              onClick={() => removeMembership(row)}
                            >
                              移除成员
                            </Button>
                          </div>
                        ),
                      },
                    ]
                  : []),
              ]}
            />
          </div>
        </div>
      ) : null}

      <Modal
        title={editingActorId ? '修改成员项目角色' : '添加项目成员'}
        open={isModalOpen}
        onCancel={handleCloseModal}
        footer={null}
        destroyOnHidden
        width={560}
      >
        <Form<MembershipFields>
          form={form}
          className="management-modal-form"
          layout="vertical"
          initialValues={{ role: 'MEMBER' }}
          onFinish={(values) => void saveMembership(values)}
        >
          <Form.Item
            label="平台账号"
            name="actorId"
            rules={[{ required: true, message: '请选择平台账号。' }]}
          >
            <Select
              showSearch
              disabled={Boolean(editingActorId)}
              placeholder="选择已启用账号"
              options={availableAccounts.map((account) => ({
                value: account.username,
                label: `${account.username}（${account.role === 'ADMIN' ? '平台管理员' : '平台成员'}）`,
              }))}
            />
          </Form.Item>
          <Form.Item
            label="项目角色"
            name="role"
            rules={[{ required: true, message: '请选择项目角色。' }]}
          >
            <Select
              options={[
                { label: '成员', value: 'MEMBER' },
                { label: '项目管理员', value: 'ADMIN' },
              ]}
            />
          </Form.Item>
          <p className="form-hint membership-form-hint">
            平台账号是全局身份；项目角色仅对当前项目生效。
          </p>
          <div className="management-modal-actions">
            <Button onClick={handleCloseModal}>取消</Button>
            <Button type="primary" htmlType="submit" loading={isMutating}>
              保存成员
            </Button>
          </div>
        </Form>
      </Modal>
    </div>
  )
}
