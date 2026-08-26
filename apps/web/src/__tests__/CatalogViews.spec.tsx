import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { renderRoute } from '@/test/render'
import AssetDetailView from '@/views/AssetDetailView'

const projectId = '30000000-0000-4000-8000-000000000001'
const assetId = '50000000-0000-4000-8000-000000000001'

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('catalog views', () => {
  it('keeps a page heading and safe retry for an initial detail-load failure', async () => {
    const fetchMock = stubAsset({ role: 'MEMBER', status: 'UPLOADED', detailFailureOnce: true })
    const user = userEvent.setup()
    renderAssetDetail()

    expect(screen.getByRole('heading', { level: 1, name: '视频详情' })).toBeInTheDocument()
    expect(await screen.findByText('暂时无法读取视频数据，请稍后重试。')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /重\s*试/ }))

    expect(await screen.findByText('视频可播放')).toBeInTheDocument()
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(
      fetchMock.mock.calls.filter(([input]) => String(input).endsWith(`/clip-assets/${assetId}`)),
    ).toHaveLength(2)
  })

  it('keeps loaded detail facts visible when a manual refresh fails', async () => {
    stubAsset({ role: 'MEMBER', status: 'UPLOADED', detailFailureAfterFirst: true })
    const user = userEvent.setup()
    renderAssetDetail()

    expect(await screen.findByText('视频可播放')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '刷新状态' }))

    expect(await screen.findByText('暂时无法读取视频数据，请稍后重试。')).toBeInTheDocument()
    expect(screen.getByText('视频可播放')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: '视频详情' })).toBeInTheDocument()
  })

  it('offers direct viewing for an uploaded video without exposing storage or using an identifier as the page title', async () => {
    stubAsset({ role: 'MEMBER', status: 'UPLOADED' })
    const user = userEvent.setup()
    renderAssetDetail()
    expect(await screen.findByText('视频可播放')).toBeInTheDocument()
    const heading = screen.getByRole('heading', { level: 1 })
    expect(heading).toHaveTextContent('视频详情')
    const headingCopy = heading.parentElement?.parentElement
    expect(headingCopy).not.toHaveTextContent('相机')
    expect(headingCopy).not.toHaveTextContent('资产 ID')
    expect(screen.getByRole('link', { name: '视频管理' })).toHaveAttribute(
      'href',
      `/projects/${projectId}/videos`,
    )
    expect(screen.getByText(/临时只读地址/)).toBeInTheDocument()
    expect(screen.getByTitle('90000000-0000-4000-8000-000000000001')).toHaveTextContent(
      '90000000…0001',
    )
    expect(screen.getByText(/尚未绑定到配药操作记录/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '播放视频' })).toBeInTheDocument()
    expect(screen.queryByLabelText('视频预览')).not.toBeInTheDocument()
    const workflow = screen.getByRole('complementary', { name: '视频处理流程' })
    expect(within(workflow).getByText('格式')).toBeInTheDocument()
    expect(within(workflow).queryByText('上传文件 ID')).not.toBeInTheDocument()
    expect(within(workflow).queryByText('创建时间')).not.toBeInTheDocument()
    const additionalDetails = screen.getByText('更多信息').closest('details')
    expect(additionalDetails).not.toHaveAttribute('open')
    await user.click(screen.getByText('更多信息'))
    expect(additionalDetails).toHaveAttribute('open')
    expect(screen.getByText('视频资产 ID')).toBeInTheDocument()
    expect(document.body.innerHTML).not.toContain('object_key')
    expect(document.body.innerHTML).not.toContain('storage_bucket')
  })

  it('does not offer the destructive action to a project member', async () => {
    stubAsset({ role: 'MEMBER', status: 'UPLOADED' })
    renderAssetDetail()
    await screen.findByText('视频可播放')
    expect(screen.queryByRole('button', { name: '删除视频' })).not.toBeInTheDocument()
  })

  it('does not show the legacy folder classification surface in LEAN-P0', async () => {
    stubAsset({ role: 'ADMIN', status: 'UPLOADED' })
    renderAssetDetail()
    expect(await screen.findByRole('heading', { level: 1, name: '视频详情' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '更新分类' })).not.toBeInTheDocument()
    expect(screen.queryByText('视频分类')).not.toBeInTheDocument()
  })

  it('lets a project member request playback only after an explicit click', async () => {
    const fetchMock = stubAsset({
      role: 'MEMBER',
      status: 'INGESTED',
      mediaReport: verifiedMediaReport(),
      quality: unreviewedQuality(),
      playback: true,
    })
    const user = userEvent.setup()
    renderAssetDetail({ username: 'reviewer', role: 'MEMBER' })
    expect(await screen.findByRole('button', { name: '播放视频' })).toBeInTheDocument()
    expect(screen.queryByLabelText('视频预览')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '播放视频' }))
    const video = await screen.findByLabelText('视频预览')
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${assetId}/playback-grants`,
      expect.objectContaining({ method: 'POST' }),
    )
    expect(video).toHaveAttribute('src', expect.stringContaining('signature=synthetic'))
    expect(video).toHaveAttribute('preload', 'none')
    expect(screen.getByText('人工查看')).toBeInTheDocument()
    expect(screen.getByText('完整解码已验证')).toBeInTheDocument()
    expect(screen.getByText('0:02')).toBeInTheDocument()
    expect(screen.queryByText('人工隐私评估')).not.toBeInTheDocument()
    fireEvent.error(video)
    expect(await screen.findByText(/视频加载失败/)).toBeInTheDocument()
    expect(screen.queryByLabelText('视频预览')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重新获取播放授权' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '保存质量结论' }))
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        `/api/v1/projects/${projectId}/clip-assets/${assetId}/quality-review`,
        expect.objectContaining({
          method: 'PUT',
          body: JSON.stringify({
            decision: 'QUALIFIED',
            reason_code: 'MEETS_REQUIREMENTS',
            expected_version: 0,
          }),
        }),
      ),
    )
    expect((await screen.findAllByText('符合训练数据要求')).length).toBeGreaterThan(0)
  })

  it('offers a local retry when the media report request fails', async () => {
    const fetchMock = stubAsset({
      role: 'MEMBER',
      status: 'INGESTED',
      mediaReportFailureOnce: true,
    })
    const user = userEvent.setup()
    renderAssetDetail({ username: 'reviewer', role: 'MEMBER' })

    expect(await screen.findByText('暂时无法读取视频数据，请稍后重试。')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重试读取' }))

    expect(await screen.findByText('完整解码已验证')).toBeInTheDocument()
    expect(
      fetchMock.mock.calls.filter(([input]) => String(input).endsWith('/media-report')),
    ).toHaveLength(2)
  })

  it('loads minimized asset activity only after an explicit request', async () => {
    const fetchMock = stubAsset({
      role: 'MEMBER',
      status: 'UPLOADED',
      activity: true,
    })
    const user = userEvent.setup()
    renderAssetDetail({ username: 'reviewer', role: 'MEMBER' })
    const button = await screen.findByRole('button', { name: '查看记录' })
    expect(
      fetchMock.mock.calls.some(([input]) =>
        String(input).includes(`/clip-assets/${assetId}/activity`),
      ),
    ).toBe(false)

    await user.click(button)

    expect(await screen.findByText('获取视频播放授权')).toBeInTheDocument()
    expect(screen.getByText('成员 · reviewer')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/projects/${projectId}/clip-assets/${assetId}/activity?limit=20`,
      expect.objectContaining({ method: 'GET', cache: 'no-store' }),
    )
    expect(screen.queryByText('must-not-be-returned')).not.toBeInTheDocument()
  })

  it('lets a project member play an uploaded video without requiring a manual media-detection step', async () => {
    stubAsset({
      role: 'MEMBER',
      status: 'UPLOADED',
      playback: true,
      mediaProbe: true,
    })
    const user = userEvent.setup()
    renderAssetDetail({ username: 'reviewer', role: 'MEMBER' })
    expect((await screen.findAllByText('待媒体检测')).length).toBeGreaterThan(0)
    await user.click(screen.getByRole('button', { name: '播放视频' }))
    expect(await screen.findByLabelText('视频预览')).toBeInTheDocument()
    expect(screen.queryByText('检测媒体信息')).not.toBeInTheDocument()
    expect(screen.getByLabelText('视频预览')).toBeInTheDocument()
  })

  it('lets an administrator recover a confirmed missing media probe delivery from the asset detail', async () => {
    const fetchMock = stubAsset({ role: 'ADMIN', status: 'UPLOADED', deliveryRecovery: true })
    vi.stubGlobal('crypto', { randomUUID: vi.fn().mockReturnValue('media-recovery-ui-0001') })
    const user = userEvent.setup()
    renderAssetDetail({ username: 'admin', role: 'ADMIN' })

    await user.click(await screen.findByRole('button', { name: '恢复丢失的检测任务' }))
    await user.type(screen.getByPlaceholderText('例如：INC-20260805-001'), 'INC-20260805-001')
    await user.click(screen.getByRole('button', { name: '确认恢复' }))

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        `/api/v1/projects/${projectId}/clip-assets/${assetId}/media-probe-delivery-recoveries`,
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ change_reference: 'INC-20260805-001' }),
          headers: expect.objectContaining({ 'Idempotency-Key': 'media-recovery-ui-0001' }),
        }),
      ),
    )
    expect(await screen.findByText(/媒体检测已恢复为第 2 次尝试/)).toBeInTheDocument()
  })

  it('automatically creates and assigns one task when a member records a qualified conclusion', async () => {
    const fetchMock = stubAsset({
      role: 'MEMBER',
      status: 'INGESTED',
      mediaReport: verifiedMediaReport(),
      quality: unreviewedQuality(),
      autoAssignedAfterQuality: true,
    })
    const user = userEvent.setup()
    renderAssetDetail({ username: 'reviewer', role: 'MEMBER' })

    await user.click(await screen.findByRole('button', { name: '保存质量结论' }))
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        `/api/v1/projects/${projectId}/clip-assets/${assetId}/quality-review`,
        expect.objectContaining({
          method: 'PUT',
          body: JSON.stringify({
            decision: 'QUALIFIED',
            reason_code: 'MEETS_REQUIREMENTS',
            expected_version: 0,
          }),
        }),
      ),
    )
    expect(await screen.findByText('已创建并分配')).toBeInTheDocument()
    expect(screen.getByText('执行人：reviewer')).toBeInTheDocument()
    expect(screen.getByText(/系统已自动分配/)).toBeInTheDocument()
    expect(
      fetchMock.mock.calls.some(
        ([input, init]) =>
          String(input).endsWith(`/api/v1/projects/${projectId}/annotation-tasks`) &&
          init?.method === 'POST',
      ),
    ).toBe(false)
  })

  it('renders persisted annotation, human review and cleaning facts after a detail refresh', async () => {
    stubAsset({
      role: 'ADMIN',
      status: 'INGESTED',
      quality: {
        ...unreviewedQuality(),
        decision: 'QUALIFIED',
        reason_code: 'MEETS_REQUIREMENTS',
      },
      existingAnnotationTask: completedAnnotationTask(),
    })

    const user = userEvent.setup()
    renderAssetDetail()

    const workflow = await screen.findByRole('complementary', { name: '视频处理流程' })
    expect(screen.getByRole('heading', { level: 2, name: '人工处理' })).toBeInTheDocument()
    expect(
      within(workflow).getByRole('heading', { name: /^(下一步|当前结果)$/ }),
    ).toBeInTheDocument()
    expect(within(workflow).getByRole('heading', { name: '流程状态' })).toBeInTheDocument()
    expect(within(workflow).getByRole('heading', { name: '视频信息' })).toBeInTheDocument()
    expect(within(workflow).getByRole('heading', { name: '手部检测' })).toBeInTheDocument()
    expect(
      within(workflow.querySelector('.workflow-facts-list') as HTMLElement).getByText('手部检测'),
    ).toBeInTheDocument()
    expect(within(workflow).getAllByText('人工复核')).toHaveLength(2)
    expect(within(workflow).getAllByText('当前规则检查通过')).toHaveLength(1)
    expect(within(workflow).getByText('当前结果')).toBeInTheDocument()
    const workflowDetails = within(workflow).getByText('查看说明').closest('details')
    expect(workflowDetails).not.toHaveAttribute('open')
    await user.click(within(workflow).getByText('查看说明'))
    expect(workflowDetails).toHaveAttribute('open')
    expect(within(workflow).getAllByText('当前数据已通过清洗，可用于训练')).toHaveLength(2)
    expect(screen.queryByRole('button', { name: '创建配药动作标注' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: '查看配药动作标注' })).toBeInTheDocument()
    expect(document.querySelector('.workbench-primary')?.tagName).toBe('SECTION')
  })

  it('keeps a historical Cleaning PASS behind a current rejected quality decision', async () => {
    stubAsset({
      role: 'ADMIN',
      status: 'INGESTED',
      quality: {
        ...unreviewedQuality(),
        quality_review_id: 'c0000000-0000-4000-8000-000000000002',
        decision: 'REJECTED',
        reason_code: 'OTHER_QUALITY_FAILURE',
        reviewed_by: 'local-synthetic-admin',
        reviewed_at: '2026-08-11T01:00:00Z',
        version: 1,
      },
      existingAnnotationTask: completedAnnotationTask(),
    })

    const user = userEvent.setup()
    renderAssetDetail()

    expect(await screen.findByRole('button', { name: '查看质量结论' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '导出训练数据' })).not.toBeInTheDocument()
    const workflow = screen.getByRole('complementary', { name: '视频处理流程' })
    await user.click(within(workflow).getByText('查看说明'))
    expect(
      screen.getByText('历史清洗已通过；当前质量结论未确认或未通过，暂不能用于训练'),
    ).toBeInTheDocument()
  })

  it('keeps automatic hand-pose detection auxiliary and does not add a manual start action', async () => {
    const fetchMock = stubAsset({ role: 'MEMBER', status: 'INGESTED', handPose: true })
    const user = userEvent.setup()
    renderAssetDetail({ username: 'reviewer', role: 'MEMBER' })

    const handPoseSummary = await screen.findByText('检测详情')
    expect(handPoseSummary.closest('details')).not.toHaveAttribute('open')
    expect(await screen.findByText('等待自动检测')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '检测手部姿态' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重新检测' })).not.toBeInTheDocument()
    await user.click(handPoseSummary)
    expect(handPoseSummary.closest('details')).toHaveAttribute('open')
    expect(screen.getByText(/检测结果不是人工标注/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '填写质量结论' })).toBeInTheDocument()
    expect(
      fetchMock.mock.calls.some(
        ([input, init]) =>
          String(input).endsWith('/hand-pose-detections') && init?.method === 'POST',
      ),
    ).toBe(false)
  })

  it('does not offer an AssetDetail retry when the failed run is not retryable', async () => {
    stubAsset({
      role: 'MEMBER',
      status: 'INGESTED',
      handPoseState: failedHandPoseJob(false),
    })
    const user = userEvent.setup()
    renderAssetDetail({ username: 'reviewer', role: 'MEMBER' })

    expect((await screen.findAllByText('检测失败')).length).toBeGreaterThanOrEqual(2)
    expect(screen.queryByRole('button', { name: '重试检测' })).not.toBeInTheDocument()
    await user.click(screen.getByText('检测详情'))
    expect(screen.getByText(/AI_HAND_POSE_INFERENCE_FAILED/)).toBeInTheDocument()
    expect(screen.getAllByText(/当前手部检测处理失败且不可重试，但可继续人工处理/)).toHaveLength(1)
    expect(screen.getByRole('button', { name: '填写质量结论' })).toBeInTheDocument()
  })

  it('uses the AssetDetail retry endpoint only for a retryable failed run', async () => {
    const fetchMock = stubAsset({
      role: 'MEMBER',
      status: 'INGESTED',
      handPoseState: failedHandPoseJob(true),
    })
    vi.stubGlobal('crypto', { randomUUID: vi.fn().mockReturnValue('asset-retry-ui-0001') })
    const user = userEvent.setup()
    renderAssetDetail({ username: 'reviewer', role: 'MEMBER' })

    await user.click(await screen.findByText('检测详情'))
    expect(await screen.findByText(/AI_HAND_POSE_INFERENCE_FAILED/)).toBeInTheDocument()
    expect(screen.getByText(/可以重新检测/)).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: '重新检测' }))
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        `/api/v1/projects/${projectId}/videos/${assetId}/hand-pose-detections/retry`,
        expect.objectContaining({
          method: 'POST',
          headers: expect.objectContaining({ 'Idempotency-Key': 'asset-retry-ui-0001' }),
        }),
      ),
    )
    expect(
      fetchMock.mock.calls.some(
        ([input, init]) =>
          String(input).endsWith('/hand-pose-detections') && init?.method === 'POST',
      ),
    ).toBe(false)
  })

  it('polls media ingest until it becomes stable, then stops', async () => {
    vi.useFakeTimers()
    let detailReads = 0
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input)
      if (url === `/api/v1/projects/${projectId}`)
        return Promise.resolve(jsonResponse(project('MEMBER')))
      if (url === `/api/v1/projects/${projectId}/clip-assets/${assetId}`) {
        detailReads += 1
        return Promise.resolve(
          jsonResponse(assetDetail(detailReads === 1 ? 'INGESTING' : 'INGESTED')),
        )
      }
      if (url.endsWith('/media-report')) return Promise.resolve(jsonResponse(verifiedMediaReport()))
      if (url.endsWith('/quality-review')) return Promise.resolve(jsonResponse(unreviewedQuality()))
      if (url.endsWith('/annotation-tasks'))
        return Promise.resolve(jsonResponse(emptyAnnotationTaskList()))
      if (url.endsWith('/hand-pose-candidates'))
        return Promise.resolve(jsonResponse({ error_code: 'AI_HAND_POSE_JOB_NOT_FOUND' }, 404))
      return Promise.resolve(jsonResponse(assetDetail('INGESTING')))
    })
    vi.stubGlobal('fetch', fetchMock)

    renderAssetDetail({ username: 'reviewer', role: 'MEMBER' })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(screen.getAllByText('媒体检测中').length).toBeGreaterThan(0)
    expect(detailReads).toBe(1)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_500)
    })
    expect(screen.getAllByText('媒体检测完成').length).toBeGreaterThanOrEqual(2)
    expect(detailReads).toBe(2)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000)
    })
    expect(detailReads).toBe(2)
  })

  it('shows that a processing status is stale when background refresh fails', async () => {
    vi.useFakeTimers()
    let detailReads = 0
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input)
      if (url === `/api/v1/projects/${projectId}`)
        return Promise.resolve(jsonResponse(project('MEMBER')))
      if (url === `/api/v1/projects/${projectId}/clip-assets/${assetId}`) {
        detailReads += 1
        return detailReads === 1
          ? Promise.resolve(jsonResponse(assetDetail('INGESTING')))
          : Promise.reject(new TypeError('Failed to fetch'))
      }
      return Promise.resolve(jsonResponse(assetDetail('INGESTING')))
    })
    vi.stubGlobal('fetch', fetchMock)

    renderAssetDetail({ username: 'reviewer', role: 'MEMBER' })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(screen.getAllByText('媒体检测中').length).toBeGreaterThan(0)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_500)
    })

    expect(screen.getByText('状态自动刷新失败')).toBeInTheDocument()
    expect(screen.getByText('暂时无法刷新检测状态')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重新刷新' })).toBeInTheDocument()
    expect(detailReads).toBe(2)
  })

  it('polls a running hand-pose job and keeps the human quality action available', async () => {
    vi.useFakeTimers()
    let handPoseReads = 0
    let detailReads = 0
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input)
      if (url === `/api/v1/projects/${projectId}`)
        return Promise.resolve(jsonResponse(project('MEMBER')))
      if (url === `/api/v1/projects/${projectId}/clip-assets/${assetId}`) {
        detailReads += 1
        return Promise.resolve(jsonResponse(assetDetail('INGESTED')))
      }
      if (url.endsWith('/media-report')) return Promise.resolve(jsonResponse(verifiedMediaReport()))
      if (url.endsWith('/quality-review')) return Promise.resolve(jsonResponse(unreviewedQuality()))
      if (url.endsWith('/annotation-tasks'))
        return Promise.resolve(jsonResponse(emptyAnnotationTaskList()))
      if (url.endsWith('/hand-pose-candidates')) {
        handPoseReads += 1
        return Promise.resolve(
          jsonResponse({
            job: handPoseReads === 1 ? runningHandPoseJob() : succeededHandPoseJob('HAND_DETECTED'),
            candidates: [],
          }),
        )
      }
      return Promise.resolve(jsonResponse(assetDetail('INGESTED')))
    })
    vi.stubGlobal('fetch', fetchMock)

    renderAssetDetail({ username: 'reviewer', role: 'MEMBER' })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(screen.getAllByText('检测中').length).toBeGreaterThanOrEqual(2)
    expect(screen.getByRole('button', { name: '填写质量结论' })).toBeInTheDocument()

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_500)
    })
    expect(screen.getAllByText('已检测到手部').length).toBeGreaterThanOrEqual(2)
    expect(screen.getByText('手部检测完成')).toBeInTheDocument()
    expect(handPoseReads).toBe(2)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000)
    })
    expect(handPoseReads).toBe(2)
    expect(detailReads).toBe(2)
  })

  it('notifies when a running hand-pose job fails without blocking manual quality review', async () => {
    vi.useFakeTimers()
    let handPoseReads = 0
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input)
      if (url === `/api/v1/projects/${projectId}`)
        return Promise.resolve(jsonResponse(project('MEMBER')))
      if (url === `/api/v1/projects/${projectId}/clip-assets/${assetId}`)
        return Promise.resolve(jsonResponse(assetDetail('INGESTED')))
      if (url.endsWith('/media-report')) return Promise.resolve(jsonResponse(verifiedMediaReport()))
      if (url.endsWith('/quality-review')) return Promise.resolve(jsonResponse(unreviewedQuality()))
      if (url.endsWith('/annotation-tasks'))
        return Promise.resolve(jsonResponse(emptyAnnotationTaskList()))
      if (url.endsWith('/hand-pose-candidates')) {
        handPoseReads += 1
        return Promise.resolve(
          jsonResponse({
            job: handPoseReads === 1 ? runningHandPoseJob() : failedHandPoseJob(true),
            candidates: [],
          }),
        )
      }
      return Promise.resolve(jsonResponse(assetDetail('INGESTED')))
    })
    vi.stubGlobal('fetch', fetchMock)

    renderAssetDetail({ username: 'reviewer', role: 'MEMBER' })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(screen.getAllByText('检测中').length).toBeGreaterThanOrEqual(2)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_500)
    })

    expect(screen.getAllByText('检测失败').length).toBeGreaterThanOrEqual(2)
    expect(screen.getByText('手部检测处理失败，仍可继续人工处理。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '填写质量结论' })).toBeInTheDocument()
  })

  it('keeps the human quality step when MediaPipe finds no hand', async () => {
    stubAsset({
      role: 'MEMBER',
      status: 'INGESTED',
      handPoseState: succeededHandPoseJob('NO_HAND_DETECTED'),
    })
    renderAssetDetail({ username: 'reviewer', role: 'MEMBER' })

    expect((await screen.findAllByText('未检测到手部')).length).toBeGreaterThanOrEqual(2)
    expect(screen.getByRole('button', { name: '填写质量结论' })).toBeInTheDocument()
  })
})

function renderAssetDetail(session?: { username: string; role: 'ADMIN' | 'MEMBER' }) {
  return renderRoute(<AssetDetailView />, {
    route: `/projects/${projectId}/assets/${assetId}`,
    path: '/projects/:projectId/assets/:id',
    session,
  })
}
function stubAsset(options: {
  role: 'ADMIN' | 'MEMBER'
  status: 'UPLOADED' | 'INGESTING' | 'INGESTED' | 'INGEST_FAILED'
  detailFailureOnce?: boolean
  detailFailureAfterFirst?: boolean
  mediaReport?: object
  mediaReportFailureOnce?: boolean
  quality?: object
  playback?: boolean
  mediaProbe?: boolean
  handPose?: boolean
  handPoseState?: object
  deliveryRecovery?: boolean
  autoAssignedAfterQuality?: boolean
  existingAnnotationTask?: object
  activity?: boolean
}) {
  let qualitySaved = false
  let detailReads = 0
  let mediaReportReads = 0
  const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url.endsWith(`/api/v1/projects/${projectId}`))
      return Promise.resolve(
        jsonResponse({
          project_id: projectId,
          display_name: '合成测试项目',
          status: 'ACTIVE',
          current_role: options.role,
          created_at: '2026-07-15T01:03:03Z',
        }),
      )
    if (url.endsWith(`/api/v1/projects/${projectId}/clip-assets/${assetId}`)) {
      detailReads += 1
      if (
        (options.detailFailureOnce && detailReads === 1) ||
        (options.detailFailureAfterFirst && detailReads > 1)
      )
        return Promise.resolve(jsonResponse({ error_code: 'CLIP_ASSET_TEMPORARY' }, 503))
      return Promise.resolve(jsonResponse(assetDetail(options.status)))
    }
    if (url.endsWith('/media-report')) {
      mediaReportReads += 1
      if (options.mediaReportFailureOnce && mediaReportReads === 1) {
        return Promise.resolve(jsonResponse({ error_code: 'MEDIA_REPORT_TEMPORARY' }, 503))
      }
      return Promise.resolve(jsonResponse(options.mediaReport ?? verifiedMediaReport()))
    }
    if (url.includes(`/clip-assets/${assetId}/activity`) && options.activity)
      return Promise.resolve(
        jsonResponse({
          items: [
            {
              event_id: 'f0000000-0000-4000-8000-000000000001',
              occurred_at_utc: '2026-07-29T02:00:00Z',
              actor_type: 'USER',
              actor_id: 'reviewer',
              action: 'CLIP_ASSET_PLAYBACK_GRANTED',
              object_type: 'ClipAsset',
              object_id: assetId,
              object_version: '1',
              outcome: 'SUCCEEDED',
              reason_code: 'DATA_REVIEW',
              request_id: 'playback-request-0001',
            },
          ],
          next_cursor: null,
        }),
      )
    if (url.endsWith('/playback-grants') && options.playback)
      return Promise.resolve(
        jsonResponse({
          grant_id: 'b0000000-0000-4000-8000-000000000001',
          purpose: 'DATA_REVIEW',
          media_url: 'https://storage.invalid/review.mp4?signature=synthetic',
          expires_at: new Date(Date.now() + 300_000).toISOString(),
          content_type: 'video/mp4',
        }),
      )
    if (url.endsWith('/media-probe-jobs') && options.mediaProbe)
      return Promise.resolve(
        jsonResponse(
          {
            media_job_id: 'b0000000-0000-4000-8000-000000000001',
            project_id: projectId,
            clip_asset_id: assetId,
            job_type: 'MEDIA_PROBE',
            status: 'QUEUED',
          },
          202,
        ),
      )
    if (url.endsWith('/hand-pose-detections') && options.handPose)
      return Promise.resolve(
        jsonResponse(
          {
            ai_job_id: 'b1000000-0000-4000-8000-000000000001',
            project_id: projectId,
            clip_asset_id: assetId,
            algorithm_key: 'MEDIAPIPE_HAND_LANDMARKER',
            algorithm_version: 'v1',
            model_sha256: 'a'.repeat(64),
            input_sha256: 'b'.repeat(64),
            input_size_bytes: 8_388_608,
            sampling_fps: 10,
            status: 'QUEUED',
            requested_by: 'local-synthetic-admin',
            created_at: '2026-08-10T01:00:00Z',
            updated_at: '2026-08-10T01:00:00Z',
          },
          202,
        ),
      )
    if (url.endsWith('/hand-pose-candidates') && options.handPoseState)
      return Promise.resolve(jsonResponse({ job: options.handPoseState, candidates: [] }))
    if (url.endsWith('/hand-pose-detections/retry') && options.handPoseState)
      return Promise.resolve(
        jsonResponse({ ...options.handPoseState, status: 'QUEUED', run_summary: null }, 202),
      )
    if (url.endsWith('/media-probe-delivery-recoveries') && options.deliveryRecovery)
      return Promise.resolve(
        jsonResponse(
          {
            recovery_id: 'c0000000-0000-4000-8000-000000000001',
            media_job_id: 'b0000000-0000-4000-8000-000000000001',
            source_message_id: 'd0000000-0000-4000-8000-000000000001',
            source_attempt: 1,
            new_attempt: 2,
            resulting_job_status: 'QUEUED',
            created_at: '2026-08-05T01:02:03Z',
          },
          202,
        ),
      )
    if (url.endsWith('/quality-review')) {
      const current = options.quality ?? unreviewedQuality()
      if (init?.method === 'PUT') qualitySaved = true
      return Promise.resolve(
        jsonResponse(
          init?.method === 'PUT'
            ? {
                ...current,
                quality_review_id: 'c0000000-0000-4000-8000-000000000001',
                decision: 'QUALIFIED',
                reason_code: 'MEETS_REQUIREMENTS',
                reviewed_by: 'reviewer',
                reviewed_at: '2026-07-22T01:02:03Z',
              }
            : current,
        ),
      )
    }
    if (url.endsWith(`/api/v1/projects/${projectId}/annotation-tasks`) && init?.method === 'GET') {
      const automaticTask =
        options.autoAssignedAfterQuality && qualitySaved ? assignedAnnotationTask() : null
      const items = options.existingAnnotationTask
        ? [options.existingAnnotationTask]
        : automaticTask
          ? [automaticTask]
          : []
      return Promise.resolve(
        jsonResponse({
          items,
          progress: {
            scope: options.role === 'ADMIN' ? 'PROJECT' : 'ASSIGNEE',
            total: items.length,
            awaiting_submission: automaticTask ? 1 : 0,
            rework_required: 0,
            awaiting_review: 0,
            completed: options.existingAnnotationTask ? 1 : 0,
          },
        }),
      )
    }
    return Promise.resolve(jsonResponse(assetDetail(options.status)))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}
function assetDetail(status: 'UPLOADED' | 'INGESTING' | 'INGESTED' | 'INGEST_FAILED') {
  return {
    clip_asset_id: assetId,
    batch_id: '60000000-0000-4000-8000-000000000001',
    source_upload_file_id: '80000000-0000-4000-8000-000000000001',
    client_file_id: '90000000-0000-4000-8000-000000000001',
    content_type: 'video/mp4',
    size_bytes: 8_388_608,
    sha256: 'a'.repeat(64),
    status,
    session_binding: null,
    created_at: '2026-07-15T01:03:03Z',
    updated_at: '2026-07-15T01:03:03Z',
  }
}

function runningHandPoseJob() {
  return {
    ...succeededHandPoseJob('UNKNOWN'),
    status: 'RUNNING',
    run_summary: null,
  }
}

function succeededHandPoseJob(handResult: 'HAND_DETECTED' | 'NO_HAND_DETECTED' | 'UNKNOWN') {
  return {
    ai_job_id: 'b1000000-0000-4000-8000-000000000001',
    project_id: projectId,
    clip_asset_id: assetId,
    algorithm_key: 'MEDIAPIPE_HAND_LANDMARKER',
    algorithm_version: 'v1',
    model_sha256: 'a'.repeat(64),
    input_sha256: 'b'.repeat(64),
    input_size_bytes: 8_388_608,
    sampling_fps: 10,
    profile_sha256: 'c'.repeat(64),
    status: 'SUCCEEDED',
    hand_result: handResult,
    run_summary: {
      analysis_complete: true,
      hand_result: handResult,
      total_video_frames: 10,
      inference_frame_count: 10,
      frames_with_hand: handResult === 'HAND_DETECTED' ? 10 : 0,
      frames_without_hand: handResult === 'NO_HAND_DETECTED' ? 10 : 0,
      left_hand_detection_count: handResult === 'HAND_DETECTED' ? 5 : 0,
      right_hand_detection_count: handResult === 'HAND_DETECTED' ? 5 : 0,
      invalid_landmark_count: 0,
      processing_duration_ms: 100,
      started_at: '2026-08-11T02:00:00Z',
      completed_at: '2026-08-11T02:00:01Z',
      failure_reason: null,
      retryable: false,
    },
    requested_by: 'local-synthetic-admin',
    created_at: '2026-08-11T02:00:00Z',
    updated_at: '2026-08-11T02:00:01Z',
  }
}

function failedHandPoseJob(retryable: boolean) {
  return {
    ai_job_id: 'b1000000-0000-4000-8000-000000000001',
    project_id: projectId,
    clip_asset_id: assetId,
    algorithm_key: 'MEDIAPIPE_HAND_LANDMARKER',
    algorithm_version: 'v1',
    model_sha256: 'a'.repeat(64),
    input_sha256: 'b'.repeat(64),
    input_size_bytes: 8_388_608,
    sampling_fps: 10,
    profile_sha256: 'c'.repeat(64),
    status: 'FAILED',
    hand_result: 'UNKNOWN',
    run_summary: {
      analysis_complete: false,
      hand_result: 'UNKNOWN',
      total_video_frames: 10,
      inference_frame_count: 1,
      frames_with_hand: 0,
      frames_without_hand: 1,
      left_hand_detection_count: 0,
      right_hand_detection_count: 0,
      invalid_landmark_count: 0,
      processing_duration_ms: 10,
      started_at: '2026-08-11T02:00:00Z',
      completed_at: '2026-08-11T02:00:01Z',
      failure_reason: 'AI_HAND_POSE_INFERENCE_FAILED',
      retryable,
    },
    requested_by: 'local-synthetic-admin',
    created_at: '2026-08-11T02:00:00Z',
    updated_at: '2026-08-11T02:00:01Z',
  }
}

function completedAnnotationTask() {
  return {
    annotation_task_id: 'e0000000-0000-4000-8000-000000000001',
    project_id: projectId,
    clip_asset_id: assetId,
    label_schema_id: 'e1000000-0000-4000-8000-000000000001',
    label_schema_digest: 'a'.repeat(64),
    assignee_actor_id: 'local-synthetic-uploader',
    created_by: 'local-synthetic-admin',
    status: 'COMPLETED',
    can_submit: false,
    can_review: false,
    can_recheck_post_cleaning: false,
    annotation_revision: {
      annotation_revision_id: 'e2000000-0000-4000-8000-000000000001',
      label_schema_id: 'e1000000-0000-4000-8000-000000000001',
      label_schema_digest: 'a'.repeat(64),
      revision_number: 1,
      annotation_document: {
        schema_version: 'annotation-document.v1',
        segments: [{ start_ms: 0, end_ms: 1_000, label: 'PREPARE' }],
      },
      submitted_by: 'local-synthetic-uploader',
      submitted_at: '2026-08-10T01:01:00Z',
      review: {
        annotation_review_id: 'e3000000-0000-4000-8000-000000000001',
        project_id: projectId,
        annotation_revision_id: 'e2000000-0000-4000-8000-000000000001',
        decision: 'APPROVED',
        review_note: null,
        reviewed_by: 'local-synthetic-admin',
        reviewed_at: '2026-08-10T01:02:00Z',
      },
      post_cleaning_result: {
        cleaning_result_id: 'e4000000-0000-4000-8000-000000000001',
        cleaning_run_id: 'e5000000-0000-4000-8000-000000000001',
        rule_set_key: 'p0-post-annotation',
        rule_set_version: 'v1',
        rule_set_digest: 'b'.repeat(64),
        outcome: 'PASS',
        reason_codes: [],
        executed_checks: ['REVIEW_APPROVED'],
        evaluated_at: '2026-08-10T01:02:01Z',
      },
    },
    created_at: '2026-08-10T01:00:00Z',
    updated_at: '2026-08-10T01:02:01Z',
    version: 2,
  }
}
function assignedAnnotationTask() {
  return {
    annotation_task_id: 'e0000000-0000-4000-8000-000000000002',
    project_id: projectId,
    clip_asset_id: assetId,
    label_schema_id: 'e1000000-0000-4000-8000-000000000001',
    label_schema_digest: 'a'.repeat(64),
    assignee_actor_id: 'reviewer',
    created_by: 'reviewer',
    status: 'ASSIGNED',
    can_submit: true,
    can_review: false,
    can_recheck_post_cleaning: false,
    annotation_revision: null,
    created_at: '2026-08-20T01:00:00Z',
    updated_at: '2026-08-20T01:00:00Z',
    version: 0,
  }
}
function jsonResponse(body: object, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function emptyAnnotationTaskList() {
  return {
    items: [],
    progress: {
      scope: 'ASSIGNEE',
      total: 0,
      awaiting_submission: 0,
      rework_required: 0,
      awaiting_review: 0,
      completed: 0,
    },
  }
}
function unreviewedQuality() {
  return {
    quality_review_id: null,
    project_id: projectId,
    clip_asset_id: assetId,
    decision: 'UNREVIEWED',
    reason_code: null,
    reviewed_by: null,
    reviewed_at: null,
    version: 0,
  }
}
function verifiedMediaReport() {
  return {
    media_report_id: 'a0000000-0000-4000-8000-000000000001',
    media_job_id: 'b0000000-0000-4000-8000-000000000001',
    project_id: projectId,
    clip_asset_id: assetId,
    container_format: 'mov',
    duration_microseconds: 2_000_000,
    video_codec: 'h264',
    video_profile: 'High',
    width: 1280,
    height: 720,
    pixel_format: 'yuv420p',
    primary_video_bitrate_bps: 2_000_000,
    average_frame_rate_numerator: 30,
    average_frame_rate_denominator: 1,
    frame_count: 60,
    variable_frame_rate: false,
    audio_stream_count: 1,
    decode_verified: true,
    input_digest_verified: true,
    warning_codes: ['AUDIO_PRESENT'],
    created_at: '2026-07-22T01:02:03Z',
  }
}

function project(currentRole: 'ADMIN' | 'MEMBER') {
  return {
    project_id: projectId,
    display_name: '合成配药项目',
    status: 'ACTIVE',
    current_role: currentRole,
    created_at: '2026-07-15T01:00:00Z',
  }
}
