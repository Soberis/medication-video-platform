import { Button, Card, Empty, Table, Tag } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'

import {
  CatalogApiError,
  listDatasetVersions,
  type DatasetVersionDraft,
} from '@/api/catalog'
import { paths } from '@/app/paths'
import { formatDateTime, shortId } from '@/catalog-presentation'

export default function DatasetVersionListView() {
  const { projectId = '' } = useParams()
  return <DatasetVersionListContent key={projectId} />
}

function DatasetVersionListContent() {
  const { projectId = '' } = useParams()
  const [items, setItems] = useState<DatasetVersionDraft[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [loadState, setLoadState] = useState<'loading' | 'success' | 'error'>('loading')
  const [isAppending, setIsAppending] = useState(false)
  const [errorCode, setErrorCode] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const [requestId, setRequestId] = useState<string | null>(null)
  const activeRequest = useRef<AbortController | null>(null)

  const loadPage = useCallback(
    async (cursor: string | null, append = false) => {
      activeRequest.current?.abort()
      const request = new AbortController()
      activeRequest.current = request
      setIsAppending(append)
      if (!append) setLoadState('loading')
      setErrorCode('')
      setErrorMessage('')
      setRequestId(null)
      try {
        const page = await listDatasetVersions(projectId, cursor, request.signal)
        if (activeRequest.current !== request) return
        setItems((current) => (append ? [...current, ...page.items] : page.items))
        setNextCursor(page.next_cursor)
        setLoadState('success')
      } catch (error) {
        if (request.signal.aborted || activeRequest.current !== request) return
        setLoadState('error')
        if (error instanceof CatalogApiError) {
          setErrorCode(error.errorCode)
          setErrorMessage(error.message)
          setRequestId(error.requestId)
        } else {
          setErrorCode('DATASET_VERSION_LIST_FAILED')
          setErrorMessage('暂时无法读取数据集草稿，请稍后重试。')
        }
      } finally {
        if (activeRequest.current === request) {
          activeRequest.current = null
          setIsAppending(false)
        }
      }
    },
    [projectId],
  )

  useEffect(() => {
    const timer = window.setTimeout(() => void loadPage(null), 0)
    return () => {
      window.clearTimeout(timer)
      activeRequest.current?.abort()
    }
  }, [loadPage])

  return (
    <>
      <section className="page-heading" aria-labelledby="dataset-versions-title">
        <div>
          <p className="app-eyebrow">训练数据准备</p>
          <h1 id="dataset-versions-title">数据集草稿</h1>
          <p>查看已保存的候选证据快照。草稿尚未拆分、冻结或导出，不能视为训练集。</p>
        </div>
        <Button loading={loadState === 'loading'} onClick={() => void loadPage(null)}>
          刷新
        </Button>
      </section>
      {loadState === 'loading' ? (
        <div className="catalog-notice" role="status" aria-live="polite">
          正在读取数据集草稿…
        </div>
      ) : null}
      {loadState === 'error' ? (
        <div className="catalog-error" role="alert">
          <strong>{errorCode}</strong>
          <span>{errorMessage}</span>
          {requestId ? <small>请求标识：{requestId}</small> : null}
          <Button onClick={() => void loadPage(null)}>重试</Button>
        </div>
      ) : null}
      {items.length > 0 ? (
        <Card className="table-card" variant="outlined" aria-busy={isAppending}>
          <Table<DatasetVersionDraft>
            dataSource={items}
            rowKey="dataset_version_id"
            pagination={false}
            scroll={{ x: 820 }}
            columns={[
              {
                title: '草稿名称',
                width: 280,
                render: (_, row) => (
                  <>
                    <strong>{row.display_name}</strong>
                    <span className="cell-secondary">{shortId(row.dataset_version_id)}</span>
                  </>
                ),
              },
              {
                title: '状态',
                width: 120,
                render: () => <Tag color="blue">草稿</Tag>,
              },
              { title: '创建人', dataIndex: 'created_by', width: 180 },
              {
                title: '创建时间',
                width: 210,
                render: (_, row) => formatDateTime(row.created_at),
              },
              {
                title: '操作',
                width: 110,
                fixed: 'right',
                render: (_, row) => (
                  <Link
                    className="text-link table-action-link"
                    aria-label={`查看数据集草稿：${row.display_name}`}
                    to={paths.dataset(projectId, row.dataset_version_id)}
                  >
                    查看证据
                  </Link>
                ),
              },
            ]}
          />
          <div className="table-footer">
            <span>已显示 {items.length} 个草稿</span>
            {nextCursor ? (
              <Button loading={isAppending} onClick={() => void loadPage(nextCursor, true)}>
                加载更多
              </Button>
            ) : null}
          </div>
        </Card>
      ) : null}
      {loadState === 'success' && items.length === 0 ? (
        <Empty description="当前项目还没有数据集草稿。满足准入条件的配药操作可在详情页创建候选快照。" />
      ) : null}
    </>
  )
}
