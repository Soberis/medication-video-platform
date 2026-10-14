import { App as AntdApp } from 'antd'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import * as annotationApi from '@/api/annotation'
import { listAllCaptureGroupSummaries } from '@/api/capture-groups'
import {
  approvedGroupTask,
  captureGroupFixture,
  jsonResponse,
  projectId,
  summaryFromGroup,
  taskList,
} from '@/__tests__/capture-group-fixtures'
import { ExportDataMenu } from '@/features/capture-group/ExportDataMenu'

const groups = Array.from({ length: 4 }, (_, index) =>
  captureGroupFixture(`60000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`, {
    expected: [1],
    received: [1],
    receipt: 'COMPLETE',
    key: `视频 ${index + 1}`,
  }),
)

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

function stubCandidates(scope: 'PROJECT' | 'ASSIGNEE' = 'PROJECT') {
  const fetchMock = vi.fn((input: RequestInfo | URL) => {
    const url = new URL(String(input), 'http://localhost')
    if (url.pathname.endsWith('/annotation-tasks')) {
      return Promise.resolve(
        jsonResponse(
          taskList(
            groups.slice(0, 3).map((group) => approvedGroupTask(group)),
            scope,
          ),
        ),
      )
    }
    const secondPage = url.searchParams.has('cursor')
    return Promise.resolve(
      jsonResponse({
        items: (secondPage ? groups.slice(1) : groups.slice(0, 2)).map(summaryFromGroup),
        next_cursor: secondPage ? null : 'page-2',
      }),
    )
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function renderExport() {
  return render(
    <AntdApp>
      <ExportDataMenu projectId={projectId} />
    </AntdApp>,
  )
}

describe('project HDF5 selection', () => {
  it('loads all pages, deduplicates, hides unapproved records and sends exact single/multiple/all IDs', async () => {
    const user = userEvent.setup()
    const fetchMock = stubCandidates()
    const createJob = vi
      .spyOn(annotationApi, 'createHdf5ExportJob')
      .mockResolvedValue({
        task_id: 'job-1',
        project_id: projectId,
        status: 'PROCESSING',
        current: 0,
        total: 1,
        percent: 0,
        created_at: new Date().toISOString(),
      })
    renderExport()
    expect(fetchMock).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '导出数据' }))
    const dialog = screen.getByRole('dialog', { name: '导出数据' })
    await within(dialog).findByRole('checkbox', { name: '选择导出 视频 3' })
    expect(within(dialog).getAllByRole('checkbox', { name: /选择导出/ })).toHaveLength(3)
    expect(within(dialog).queryByText('视频 4')).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.map(([url]) => String(url))).toContain(
      `/api/v1/projects/${projectId}/capture-groups?limit=100&cursor=page-2`,
    )
    const button = within(dialog).getByRole('button', { name: '下载 HDF5' })
    expect(button).toBeDisabled()
    await user.click(within(dialog).getByRole('checkbox', { name: '选择导出 视频 1' }))
    expect(button).not.toBeDisabled()
    await user.click(within(dialog).getByRole('checkbox', { name: '选择导出 视频 3' }))
    expect(within(dialog).getByText('已选择 2 条')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('checkbox', { name: '全选' }))
    expect(within(dialog).getByText('已选择 3 条')).toBeInTheDocument()
    await user.click(button)
    expect(createJob).toHaveBeenLastCalledWith(
      projectId,
      groups.slice(0, 3).map((group) => group.capture_group_id),
    )
    expect(await screen.findByRole('region', { name: '后台导出任务卡片' })).toBeInTheDocument()
  })

  it('keeps partial pages unavailable after failure and supports a fresh retry', async () => {
    const user = userEvent.setup()
    const fetchMock = stubCandidates()
    fetchMock
      .mockImplementationOnce(() =>
        Promise.resolve(
          jsonResponse({ items: [summaryFromGroup(groups[0]!)], next_cursor: 'page-2' }),
        ),
      )
      .mockImplementationOnce(() =>
        Promise.resolve(
          jsonResponse(taskList(groups.slice(0, 3).map((group) => approvedGroupTask(group)))),
        ),
      )
      .mockRejectedValueOnce(new Error('page failed'))
    renderExport()
    await user.click(screen.getByRole('button', { name: '导出数据' }))
    await screen.findByRole('alert')
    expect(screen.queryByRole('checkbox', { name: /选择导出/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '下载 HDF5' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: /重\s*试/ }))
    await screen.findByRole('checkbox', { name: '选择导出 视频 3' })
  })

  it('rejects an assignee-only response rather than presenting it as the project', async () => {
    stubCandidates('ASSIGNEE')
    renderExport()
    await userEvent.setup().click(screen.getByRole('button', { name: '导出数据' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('没有读取项目全部导出候选的权限')
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  })

  it('ignores a cancelled read and reloads on reopening', async () => {
    const user = userEvent.setup()
    const fetchMock = stubCandidates()
    let resolvePage!: (value: Response) => void
    fetchMock.mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          resolvePage = resolve
        }),
    )
    const read = vi.spyOn(annotationApi, 'listAnnotationTasks')
    renderExport()
    await user.click(screen.getByRole('button', { name: '导出数据' }))
    await screen.findByText('正在读取当前项目的导出候选…')
    const signal = read.mock.calls[0]![1]!
    await user.click(screen.getByRole('button', { name: '取消' }))
    expect(signal.aborted).toBe(true)
    resolvePage(jsonResponse({ items: [summaryFromGroup(groups[3]!)], next_cursor: 'stale-page' }))
    await user.click(screen.getByRole('button', { name: '导出数据' }))
    await screen.findByRole('checkbox', { name: '选择导出 视频 3' })
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('stale-page'))).toBe(false)
  })

  it('shows empty candidates and initiates export upon valid selection', async () => {
    const user = userEvent.setup()
    stubCandidates()
    vi.spyOn(annotationApi, 'listAnnotationTasks').mockResolvedValueOnce(taskList([]))
    const createJob = vi
      .spyOn(annotationApi, 'createHdf5ExportJob')
      .mockResolvedValueOnce({
        task_id: 'job-synth',
        project_id: projectId,
        status: 'PROCESSING',
        current: 0,
        total: 1,
        percent: 0,
        created_at: new Date().toISOString(),
      })
    renderExport()
    await user.click(screen.getByRole('button', { name: '导出数据' }))
    await screen.findByText(/当前项目暂无/)
    expect(screen.getByRole('button', { name: '下载 HDF5' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '取消' }))
    await user.click(screen.getByRole('button', { name: '导出数据' }))
    await user.click(await screen.findByRole('checkbox', { name: '选择导出 视频 1' }))
    await user.click(screen.getByRole('button', { name: '下载 HDF5' }))
    expect(createJob).toHaveBeenCalledWith(projectId, [groups[0]!.capture_group_id])
    expect(await screen.findByRole('region', { name: '后台导出任务卡片' })).toBeInTheDocument()
  })

  it('selects and unselects a row by clicking its body, without double toggling on the checkbox', async () => {
    const user = userEvent.setup()
    stubCandidates()
    renderExport()
    await user.click(screen.getByRole('button', { name: '导出数据' }))
    const dialog = screen.getByRole('dialog', { name: '导出数据' })
    await within(dialog).findByRole('checkbox', { name: '选择导出 视频 1' })

    // Click the row body (video identity text), not the checkbox
    await user.click(within(dialog).getByText('视频 1'))
    expect(within(dialog).getByRole('checkbox', { name: '选择导出 视频 1' })).toBeChecked()
    expect(within(dialog).getByText('已选择 1 条')).toBeInTheDocument()

    // Clicking another row body adds it
    await user.click(within(dialog).getByText('视频 2'))
    expect(within(dialog).getByText('已选择 2 条')).toBeInTheDocument()

    // Clicking the checkbox itself toggles exactly once (no double toggle)
    await user.click(within(dialog).getByRole('checkbox', { name: '选择导出 视频 1' }))
    expect(within(dialog).getByRole('checkbox', { name: '选择导出 视频 1' })).not.toBeChecked()
    expect(within(dialog).getByText('已选择 1 条')).toBeInTheDocument()
  })

  it('stops on a repeated cursor rather than returning a partial project', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockImplementation(() =>
          Promise.resolve(jsonResponse({ items: [], next_cursor: 'repeat' })),
        ),
    )
    await expect(
      listAllCaptureGroupSummaries(projectId, new AbortController().signal),
    ).rejects.toThrow('项目候选列表读取未完成')
  })

  it('hides groups without an approved auto-approval task from export selection', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = new URL(String(input), 'http://localhost')
      if (url.pathname.endsWith('/annotation-tasks')) {
        // Only 视频 2 and 视频 3 passed dual evidence + auto-approval;
        // 视频 1 (no hand) never received an approved task.
        return Promise.resolve(
          jsonResponse(
            taskList(groups.slice(1, 3).map((group) => approvedGroupTask(group)), 'PROJECT'),
          ),
        )
      }
      const secondPage = url.searchParams.has('cursor')
      return Promise.resolve(
        jsonResponse({
          items: (secondPage ? groups.slice(1) : groups.slice(0, 2)).map(summaryFromGroup),
          next_cursor: secondPage ? null : 'page-2',
        }),
      )
    })
    vi.stubGlobal('fetch', fetchMock)

    render(
      <AntdApp>
        <ExportDataMenu projectId={projectId} />
      </AntdApp>,
    )
    await user.click(screen.getByRole('button', { name: '导出数据' }))
    const dialog = screen.getByRole('dialog', { name: '导出数据' })
    await within(dialog).findByRole('checkbox', { name: '选择导出 视频 2' })

    // 视频 1 (no hand / not auto-approved) never appears in the dialog.
    expect(within(dialog).queryByText('视频 1')).not.toBeInTheDocument()
    expect(within(dialog).queryByText('未检出手部')).not.toBeInTheDocument()
    expect(within(dialog).getAllByRole('checkbox', { name: /选择导出/ })).toHaveLength(2)

    // Clicking 全选 only selects exportable rows (视频 2 and 视频 3).
    await user.click(within(dialog).getByRole('checkbox', { name: '全选' }))
    expect(within(dialog).getByText('已选择 2 条')).toBeInTheDocument()
    expect(within(dialog).getByRole('checkbox', { name: '选择导出 视频 2' })).toBeChecked()
    expect(within(dialog).getByRole('checkbox', { name: '选择导出 视频 3' })).toBeChecked()
  })

  it('triggers background export, closes dialog, displays floater with progress and supports cancellation', async () => {
    const user = userEvent.setup()
    stubCandidates()
    const createJob = vi.spyOn(annotationApi, 'createHdf5ExportJob').mockResolvedValueOnce({
      task_id: 'job-123',
      project_id: projectId,
      status: 'PROCESSING',
      current: 1,
      total: 1,
      percent: 50,
      current_label: '视频 1',
      created_at: new Date().toISOString(),
    })

    renderExport()
    await user.click(screen.getByRole('button', { name: '导出数据' }))
    const dialog = screen.getByRole('dialog', { name: '导出数据' })
    await user.click(await within(dialog).findByRole('checkbox', { name: '选择导出 视频 1' }))

    const exportBtn = within(dialog).getByRole('button', { name: '下载 HDF5' })
    await user.click(exportBtn)

    expect(createJob).toHaveBeenCalledWith(projectId, [groups[0]!.capture_group_id])
    expect(await screen.findByRole('region', { name: '后台导出任务卡片' })).toBeInTheDocument()
    expect(screen.getByText('正在后台导出 HDF5 数据集')).toBeInTheDocument()
  })
})

