import { Button, Card, Empty, Table } from 'antd'
import { useEffect, useRef, useState } from 'react'

import { CatalogApiError, listClipAssetActivity, type ClipAssetActivityItem } from '@/api/catalog'
import { StatusBadge } from '@/components/StatusBadge'
import { ErrorState } from '@/components/WorkspaceFeedback'
import { formatDateTime, shortId } from '@/features/catalog/catalog-presentation'

const actionLabels: Record<string, string> = {
  CLIP_ASSET_PLAYBACK_GRANTED: '获取视频播放授权',
  CLIP_ASSET_DELETED: '删除视频',
  CLIP_ASSET_QUALITY_REVIEWED: '记录人工质量结论',
  ANNOTATION_TASK_CREATED: '创建配药动作标注',
  ANNOTATION_TASK_AUTO_ASSIGNED: '自动创建并分配配药动作标注',
  ANNOTATION_REVISION_SUBMITTED: '提交配药动作标注修订',
  ANNOTATION_REVISION_REVIEWED: '复核配药动作标注修订',
  POST_ANNOTATION_CLEANING_COMPLETED: '完成后置清洗检查',
}

const reasonLabels: Record<string, string> = {
  DATA_REVIEW: '人工查看',
  USER_REQUESTED_DELETION: '用户请求删除',
  QUALIFIED_CLIP_ASSET: '视频已通过人工质量判断',
  AUTO_AFTER_QUALITY_QUALIFIED: '质量合格后自动分配',
  MANUAL_ANNOTATION_SUBMITTED: '人工配药动作标注已提交',
  INDEPENDENT_ANNOTATION_REVIEW: '独立复核',
  SELF_ADMIN_APPROVAL: '管理员自审（SELF_ADMIN_APPROVAL）',
  MEETS_REQUIREMENTS: '符合质量要求',
  POST_CLEANING_PASS: '后置清洗通过',
  POST_CLEANING_WARNING: '后置清洗需确认',
  POST_CLEANING_REJECT: '后置清洗不通过',
}

export function AssetActivityCard({ projectId, assetId }: { projectId: string; assetId: string }) {
  const [items, setItems] = useState<ClipAssetActivityItem[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [state, setState] = useState<'idle' | 'loading' | 'success' | 'error'>('idle')
  const [errorCode, setErrorCode] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const activeRequest = useRef<AbortController | null>(null)

  useEffect(
    () => () => {
      activeRequest.current?.abort()
      activeRequest.current = null
    },
    [],
  )

  async function load(reset: boolean) {
    if (!reset && !nextCursor) return
    activeRequest.current?.abort()
    const request = new AbortController()
    activeRequest.current = request
    const cursor = reset ? null : nextCursor
    if (reset) {
      setItems([])
      setNextCursor(null)
    }
    setState('loading')
    setErrorCode('')
    setErrorMessage('')
    try {
      const response = await listClipAssetActivity(projectId, assetId, cursor, request.signal)
      if (request.signal.aborted || activeRequest.current !== request) return
      setItems((current) => (reset ? response.items : [...current, ...response.items]))
      setNextCursor(response.next_cursor)
      setState('success')
    } catch (error) {
      if (request.signal.aborted || activeRequest.current !== request) return
      setState('error')
      setErrorCode(error instanceof CatalogApiError ? error.errorCode : 'ASSET_ACTIVITY_FAILED')
      setErrorMessage(
        error instanceof CatalogApiError ? error.message : '暂时无法读取操作记录，请稍后重试。',
      )
    } finally {
      if (activeRequest.current === request) activeRequest.current = null
    }
  }

  return (
    <Card
      className="detail-card"
      title={<strong>操作记录</strong>}
      extra={
        <Button loading={state === 'loading'} onClick={() => void load(true)}>
          {state === 'idle' ? '查看记录' : '刷新'}
        </Button>
      }
      variant="outlined"
    >
      {state === 'idle' ? <p className="form-hint">按需加载该视频的操作记录。</p> : null}
      {state === 'error' ? (
        <ErrorState code={errorCode} onRetry={() => void load(items.length === 0)}>
          {errorMessage}
        </ErrorState>
      ) : null}
      {state === 'success' && items.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该视频还没有可显示的操作记录。" />
      ) : null}
      {items.length > 0 ? (
        <>
          <Table<ClipAssetActivityItem>
            dataSource={items}
            rowKey="event_id"
            pagination={false}
            scroll={{ x: 960 }}
            columns={[
              {
                title: '时间',
                dataIndex: 'occurred_at_utc',
                width: 190,
                render: (value: string) => formatDateTime(value),
              },
              {
                title: '操作',
                dataIndex: 'action',
                width: 220,
                render: (value: string) => (
                  <span title={value}>{actionLabels[value] ?? value}</span>
                ),
              },
              {
                title: '操作者',
                width: 180,
                render: (_, item) => (
                  <span>
                    {item.actor_type === 'USER'
                      ? '用户'
                      : item.actor_type === 'SERVICE'
                        ? '服务'
                        : '系统'}
                    {' · '}
                    {item.actor_id}
                  </span>
                ),
              },
              {
                title: '结果',
                dataIndex: 'outcome',
                width: 100,
                render: (value: ClipAssetActivityItem['outcome']) => (
                  <StatusBadge
                    tone={
                      value === 'SUCCEEDED' ? 'success' : value === 'DENIED' ? 'warning' : 'error'
                    }
                  >
                    {value === 'SUCCEEDED' ? '成功' : value === 'DENIED' ? '已拒绝' : '失败'}
                  </StatusBadge>
                ),
              },
              {
                title: '说明',
                width: 270,
                render: (_, item) => (
                  <details>
                    <summary title={item.reason_code}>
                      {reasonLabels[item.reason_code] ?? item.reason_code}
                    </summary>
                    <small>
                      请求 {shortId(item.request_id)} · {item.object_type}
                      {item.object_version ? ` v${item.object_version}` : ''}
                    </small>
                  </details>
                ),
              },
            ]}
          />
          {nextCursor ? (
            <Button loading={state === 'loading'} onClick={() => void load(false)}>
              加载更多
            </Button>
          ) : null}
        </>
      ) : null}
    </Card>
  )
}
