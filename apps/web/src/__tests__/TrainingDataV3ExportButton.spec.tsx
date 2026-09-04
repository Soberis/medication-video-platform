import { App as AntdApp } from 'antd'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { TrainingDataV3ExportButton } from '@/features/capture-group/TrainingDataV3ExportButton'

const projectId = '30000000-0000-4000-8000-000000000001'

afterEach(() => vi.unstubAllGlobals())

describe('training-data.v3 export preview', () => {
  it('explains that the project export is an eligible event manifest', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        schema_version: 'training-data.v3',
        eligible_group_count: 2,
        event_count: 2,
        file_name: 'training_data_v3.json',
        content_type: 'application/vnd.medication.training-data.v3+json',
        split_unit: 'capture_group',
        includes_mp4: false,
      }),
    )
    vi.stubGlobal('fetch', fetchMock)
    render(
      <AntdApp>
        <TrainingDataV3ExportButton projectId={projectId} />
      </AntdApp>,
    )

    await userEvent.click(screen.getByRole('button', { name: '导出训练资格数据' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('可以导出')).toBeInTheDocument()
    expect(dialog).toHaveTextContent('来源 manifest，不是手部关键点样本，也不包含 MP4')
    expect(dialog).toHaveTextContent('符合资格事件')
    expect(dialog).toHaveTextContent('capture_group')
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/training-data/v3/preview`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
  })
})

function jsonResponse(body: object) {
  return new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } })
}
