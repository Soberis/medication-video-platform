import { screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { paths } from '@/app/paths'
import { renderRoute } from '@/test/render'
import CaptureGroupListView from '@/views/CaptureGroupListView'

import {
  captureGroupFixture,
  groupId,
  groupTask,
  projectId,
  stubGroupList,
} from './capture-group-fixtures'

afterEach(() => vi.unstubAllGlobals())

describe('CaptureGroup data-group list', () => {
  it('renders receipt, machine-evidence and export-boundary facts without quality enrichment', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1],
      receipt: 'INCOMPLETE',
      key: 'machine-evidence-group',
    })
    const fetchMock = stubGroupList([group], [], {
      qualityListError: { status: 503 },
    })

    renderRoute(<CaptureGroupListView />, {
      path: '/projects/:projectId/capture-groups',
      route: `/projects/${projectId}/capture-groups`,
    })

    const list = await screen.findByRole('list', { name: '采集记录列表' })
    const row = within(list).getByRole('listitem', { name: /machine-evidence-group/ })
    expect(screen.getByRole('heading', { name: '视频' })).toBeInTheDocument()
    expect(row).toHaveTextContent('1/2 机位已收 · 接收完成 · 有缺失')
    expect(row).toHaveTextContent('缺失：机位 2')
    expect(row).not.toHaveTextContent('MediaPipe machine evidence')
    expect(row).not.toHaveTextContent('Media Probe · hand-landmarks.v1')
    expect(row).not.toHaveTextContent('整体质量')
    expect(
      fetchMock.mock.calls.some(([input]) =>
        String(input).includes('capture-group-quality-reviews'),
      ),
    ).toBe(false)
  })

  it('shows the human-label task status without adding a second row link', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1],
      received: [1],
      receipt: 'COMPLETE',
      key: 'human-label-group',
    })
    stubGroupList([group], [groupTask(groupId, true)])

    renderRoute(<CaptureGroupListView />, {
      path: '/projects/:projectId/capture-groups',
      route: `/projects/${projectId}/capture-groups`,
    })

    const row = await screen.findByRole('listitem', { name: /human-label-group/ })
    expect(row).toHaveTextContent('待提交')
    expect(within(row).getByRole('link', { name: /查看视频/ })).toHaveAttribute(
      'href',
      paths.captureGroup(projectId, groupId),
    )
    expect(within(row).getAllByRole('link')).toHaveLength(1)
  })

  it('keeps the data-group error boundary independent from optional task enrichment', async () => {
    stubGroupList([], [], {
      groupListError: { status: 500, errorCode: 'CAPTURE_GROUP_LIST_FAILED' },
      taskListError: true,
    })

    renderRoute(<CaptureGroupListView />, {
      path: '/projects/:projectId/capture-groups',
      route: `/projects/${projectId}/capture-groups`,
    })

    expect(await screen.findByText('暂时无法读取采集记录，请稍后重试。')).toBeInTheDocument()
    expect(screen.getByText('CAPTURE_GROUP_LIST_FAILED')).toBeInTheDocument()
  })
})
