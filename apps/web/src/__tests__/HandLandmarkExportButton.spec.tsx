import { App as AntdApp } from 'antd'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { HandLandmarkExportButton } from '@/features/capture-group/HandLandmarkExportButton'

const projectId = '30000000-0000-4000-8000-000000000001'
const captureGroupId = '49de2828-0000-4000-8000-000000000001'

afterEach(() => vi.unstubAllGlobals())

describe('hand landmark export scopes', () => {
  it.each([
    ['导出手部关键点', undefined, '整个项目', ''],
    ['导出手部关键点', captureGroupId, '当前采集记录', `?capture_group_id=${captureGroupId}`],
  ])('uses the natural scope for %s', async (label, scope, scopeLabel, query) => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(preview(scope)))
    vi.stubGlobal('fetch', fetchMock)
    render(
      <AntdApp>
        <HandLandmarkExportButton projectId={projectId} captureGroupId={scope} />
      </AntdApp>,
    )

    await userEvent.click(screen.getByRole('button', { name: label }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(scopeLabel)).toBeInTheDocument()
    expect(within(dialog).getByText(preview(scope).file_name)).toBeInTheDocument()
    expect(within(dialog).queryByRole('combobox')).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/hand-landmarks/v1/preview${query}`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
  })

  it('shows partial export facts instead of masking unfinished views as complete', async () => {
    const partialPreview = {
      ...preview(),
      hand_detection_view_count: 3,
      completed_view_count: 2,
      running_view_count: 1,
    }
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(partialPreview))
    vi.stubGlobal('fetch', fetchMock)
    render(
      <AntdApp>
        <HandLandmarkExportButton projectId={projectId} />
      </AntdApp>,
    )

    await userEvent.click(screen.getByRole('button', { name: '导出手部关键点' }))

    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('部分可导出')
    expect(dialog).toHaveTextContent('1 个视图仍在检测；本次仅导出已验证样本。')
  })
})

function preview(scope?: string) {
  return {
    schema_version: 'hand-landmarks.v1',
    hand_detection_view_count: 2,
    completed_view_count: 2,
    views_with_hand: 2,
    sample_count: 2,
    no_job_view_count: 0,
    queued_view_count: 0,
    running_view_count: 0,
    failed_view_count: 0,
    no_hand_view_count: 0,
    file_name: scope
      ? 'mediapipe_hand_landmarks_v1_event_49de2828_20260903T112530Z.csv'
      : 'mediapipe_hand_landmarks_v1_project_20260903T112530Z.csv',
    generated_at: '2026-09-03T11:25:30Z',
    media_type: 'text/csv; charset=UTF-8',
  }
}

function jsonResponse(body: object) {
  return new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } })
}
