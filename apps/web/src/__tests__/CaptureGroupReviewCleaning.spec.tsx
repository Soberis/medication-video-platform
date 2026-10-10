import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'

import { renderRoute } from '@/test/render'
import CaptureGroupDetailView from '@/views/CaptureGroupDetailView'
import * as annotationApi from '@/api/annotation'

import {
  approvedGroupTask,
  cameraViewFixture,
  captureGroupFixture,
  groupId,
  projectId,
  qualityFixture,
  rejectedGroupTask,
  stubGroupDetail,
  submittedGroupTask,
} from './capture-group-fixtures'

afterEach(() => vi.unstubAllGlobals())

describe('CaptureGroup Review and event cleaning', () => {
  it('lets an authorized reviewer approve a submitted Revision and shows server cleaning', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2, 3],
      received: [1, 2, 3],
      receipt: 'COMPLETE',
    })
    const viewSet = qualityFixture(group, 'QUALIFIED').confirmed_view_set
    const submitted = submittedGroupTask(group, { confirmedViewSet: viewSet })
    const completed = approvedGroupTask(group, { confirmedViewSet: viewSet })
    const fetchMock = stubGroupDetail(group, {
      task: submitted,
      taskResponses: [submitted, completed],
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'NO_HAND_DETECTED',
    })
    const user = userEvent.setup()
    renderCaptureGroup()

    await user.click(await screen.findByRole('button', { name: '通过复核' }))

    await waitFor(() => {
      expect(
        fetchMock.mock.calls.some(
          ([input, init]) =>
            String(input).endsWith(
              `/annotation-revisions/${submitted.annotation_revision?.annotation_revision_id}/review`,
            ) && init?.method === 'POST',
        ),
      ).toBe(true)
    })
    const reviewCall = fetchMock.mock.calls.find(
      ([input, init]) =>
        String(input).endsWith(
          `/annotation-revisions/${submitted.annotation_revision?.annotation_revision_id}/review`,
        ) && init?.method === 'POST',
    )
    expect(JSON.parse(String(reviewCall?.[1]?.body))).toEqual({
      decision: 'APPROVED',
      reviewed_camera_numbers: [1, 2, 3],
      return_camera_numbers: [],
    })
    await user.click(await screen.findByText('查看复核详情'))
    expect(await screen.findByText('标注检查通过')).toBeInTheDocument()
    expect(screen.getByText('当前规则检查通过')).toBeInTheDocument()
    expect(screen.getByText('capture-group-event-eligibility')).toBeInTheDocument()
    expect(screen.getByText('REVIEW_APPROVED')).toBeInTheDocument()
  })

  it('reviews immutable per-camera intervals from the v2 revision snapshot', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    const task = submittedGroupTask(group)
    const camera1 = cameraViewFixture(1, {
      state: 'SUBMITTED',
      segments: [
        {
          segment_id: 'current-camera-1',
          start_ms: 2000,
          end_ms: 2400,
          label: 'acquire_item',
          source_candidate_ids: [],
        },
      ],
    })
    const camera2 = cameraViewFixture(2, {
      state: 'SUBMITTED',
      segments: [
        {
          segment_id: 'current-camera-2',
          start_ms: 3000,
          end_ms: 3500,
          label: 'acquire_item',
          source_candidate_ids: [],
        },
      ],
    })
    task.camera_views = [camera1, camera2]
    task.annotation_revision!.annotation_document = {
      schema_version: 'annotation-document.v2',
      capture_group_id: group.capture_group_id,
      expected_task_version: 1,
      input_version: 'capture-group-view-set.v1:synthetic',
      label_schema_id: task.label_schema_id,
      label_schema_digest: task.label_schema_digest,
      camera_views: [
        {
          camera_number: 1,
          camera_stream_id: camera1.camera_stream_id,
          clip_asset_id: camera1.clip_asset_id,
          clip_asset_sha256: camera1.source_asset_sha256,
          camera_stream_version: 1,
          clip_asset_version: 1,
          media_status: camera1.media_status,
          disposition: 'SUBMITTED',
          included: true,
          note: camera1.note,
          segments: [
            {
              segment_id: 'snapshot-camera-1',
              start_ms: 100,
              end_ms: 500,
              label: 'acquire_item',
              source_candidate_ids: [],
              correspondence_id: 'snapshot-correspondence',
              correspondence_status: 'CONFIRMED',
            },
          ],
        },
        {
          camera_number: 2,
          camera_stream_id: camera2.camera_stream_id,
          clip_asset_id: camera2.clip_asset_id,
          clip_asset_sha256: camera2.source_asset_sha256,
          camera_stream_version: 1,
          clip_asset_version: 1,
          media_status: camera2.media_status,
          disposition: 'SUBMITTED',
          included: true,
          note: camera2.note,
          segments: [
            {
              segment_id: 'snapshot-camera-2',
              start_ms: 150,
              end_ms: 650,
              label: 'acquire_item',
              source_candidate_ids: [],
              correspondence_id: 'snapshot-correspondence',
              correspondence_status: 'CONFIRMED',
            },
          ],
        },
      ],
      correspondences: [
        {
          correspondence_id: 'snapshot-correspondence',
          status: 'CONFIRMED',
          segments: [
            { camera_number: 1, segment_id: 'snapshot-camera-1' },
            { camera_number: 2, segment_id: 'snapshot-camera-2' },
          ],
        },
      ],
    }
    stubGroupDetail(group, {
      task,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'NO_HAND_DETECTED',
    })

    renderCaptureGroup()

    const reviewLanes = await screen.findByRole('region', { name: '逐机位复核' })
    expect(within(reviewLanes).getByText('00:00.100 → 00:00.500')).toBeInTheDocument()
    expect(within(reviewLanes).getByText('00:00.150 → 00:00.650')).toBeInTheDocument()
    expect(within(reviewLanes).queryByText('00:02.000 → 00:02.400')).not.toBeInTheDocument()
    expect(within(reviewLanes).queryByText('00:03.000 → 00:03.500')).not.toBeInTheDocument()
  })

  it('keeps long camera review intervals collapsed until requested', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    const task = submittedGroupTask(group)
    const annotationDocument = task.annotation_revision?.annotation_document
    const snapshotCamera1 =
      annotationDocument && annotationDocument.schema_version === 'annotation-document.v2'
        ? annotationDocument.camera_views.find((view) => view.camera_number === 1)
        : undefined
    if (!snapshotCamera1) {
      throw new Error('Expected camera 1 in submitted review fixture')
    }
    snapshotCamera1.segments = Array.from({ length: 8 }, (_, index) => ({
      segment_id: `compact-review-${index + 1}`,
      start_ms: index * 1000,
      end_ms: index * 1000 + 500,
      label: 'acquire_item',
      source_candidate_ids: [],
      correspondence_id: `compact-correspondence-${index + 1}`,
      correspondence_status: 'CONFIRMED' as const,
    }))
    stubGroupDetail(group, {
      task,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'NO_HAND_DETECTED',
    })

    const user = userEvent.setup()
    renderCaptureGroup()

    const reviewLanes = await screen.findByRole('region', { name: '逐机位复核' })
    expect(within(reviewLanes).getByText('8 段')).toBeInTheDocument()
    const expandButton = within(reviewLanes).getByRole('button', { name: '查看全部 8 段' })
    expect(expandButton).toBeInTheDocument()
    expect(within(reviewLanes).queryByText('00:07 → 00:07.500')).not.toBeInTheDocument()

    await user.click(expandButton)

    expect(within(reviewLanes).getByText('00:07 → 00:07.500')).toBeInTheDocument()
    expect(within(reviewLanes).getByRole('button', { name: '收起动作段' })).toBeInTheDocument()
  })

  it('does not expose Review actions when the server denies reviewer permission', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    const submitted = submittedGroupTask(group, { canReview: false })
    stubGroupDetail(group, {
      task: submitted,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'NO_HAND_DETECTED',
    })
    renderCaptureGroup()

    expect(await screen.findByText('当前任务状态：待复核。')).toBeInTheDocument()
    expect(await screen.findByRole('region', { name: '动作标注' })).toBeVisible()
    expect(screen.queryByRole('button', { name: '通过复核' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '退回修改' })).not.toBeInTheDocument()
  })

  it.each([false, true])(
    'returns a camera for rework while retaining the revision (excluded: %s)',
    async (excluded) => {
      const group = captureGroupFixture(groupId, {
        expected: [1, 2],
        received: [1, 2],
        receipt: 'COMPLETE',
      })
      const viewSet = qualityFixture(group, 'QUALIFIED').confirmed_view_set
      const submitted = submittedGroupTask(group, { confirmedViewSet: viewSet })
      const reworked = rejectedGroupTask(group, {
        confirmedViewSet: viewSet,
        reviewNote: '请补充动作结束位置。',
      })
      if (
        excluded &&
        submitted.annotation_revision?.annotation_document.schema_version ===
          'annotation-document.v2'
      ) {
        const view = submitted.annotation_revision.annotation_document.camera_views[0]!
        view.included = false
        view.exclusion_reason = '需要重新核对的排除决定'
        view.segments = []
      }

      const fetchMock = stubGroupDetail(group, {
        task: submitted,
        taskResponses: [submitted, reworked],
        quality: qualityFixture(group, 'QUALIFIED'),
        candidateStatus: 'NO_HAND_DETECTED',
      })
      const user = userEvent.setup()
      renderCaptureGroup()

      await user.click((await screen.findAllByRole('checkbox', { name: '退回此机位' }))[0]!)
      await user.click(await screen.findByRole('button', { name: '退回修改' }))
      const dialog = await screen.findByRole('dialog')
      await user.type(
        within(dialog).getByRole('textbox', { name: '退回修改说明（可选）' }),
        '请补充动作结束位置。',
      )
      await user.click(within(dialog).getByRole('button', { name: '退回修改' }))

      await waitFor(() => {
        expect(
          fetchMock.mock.calls.some(
            ([input, init]) =>
              String(input).endsWith(
                `/annotation-revisions/${submitted.annotation_revision?.annotation_revision_id}/review`,
              ) && init?.method === 'POST',
          ),
        ).toBe(true)
      })
      const reviewCall = fetchMock.mock.calls.find(
        ([input, init]) =>
          String(input).endsWith(
            `/annotation-revisions/${submitted.annotation_revision?.annotation_revision_id}/review`,
          ) && init?.method === 'POST',
      )
      expect(JSON.parse(String(reviewCall?.[1]?.body))).toEqual({
        decision: 'REJECTED',
        review_note: '请补充动作结束位置。',
        reviewed_camera_numbers: [2],
        return_camera_numbers: [1],
      })
      expect((await screen.findAllByText('待返工')).length).toBeGreaterThan(0)
      expect(screen.getAllByText('第 1 次正式修订').length).toBeGreaterThan(0)
      expect(screen.getAllByText('复核 · 已退回').length).toBeGreaterThan(0)
      expect(screen.getAllByText('请补充动作结束位置。').length).toBeGreaterThan(0)
      expect(screen.queryByText('标注检查通过')).not.toBeInTheDocument()
    },
  )

  it('surfaces a server rejection when Group Quality was downgraded before Review', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    const submitted = submittedGroupTask(group)
    const fetchMock = stubGroupDetail(group, {
      task: submitted,
      quality: qualityFixture(group, 'NEEDS_REVIEW'),
      candidateStatus: 'NO_HAND_DETECTED',
      reviewError: {
        status: 409,
        body: { error_code: 'ANNOTATION_REVISION_NO_LONGER_QUALIFIED' },
      },
    })
    const user = userEvent.setup()
    renderCaptureGroup()

    await user.click(await screen.findByRole('button', { name: '通过复核' }))

    expect(await screen.findByText('该采集记录当前不满足标注复核条件。')).toBeInTheDocument()
    expect(
      fetchMock.mock.calls.filter(
        ([input, init]) =>
          String(input).endsWith(
            '/annotation-revisions/' +
              submitted.annotation_revision?.annotation_revision_id +
              '/review',
          ) && init?.method === 'POST',
      ),
    ).toHaveLength(1)
    expect(screen.queryByText('标注检查通过')).not.toBeInTheDocument()
  })

  it('keeps missing and rejected views visible and gives supporting views no action timestamps', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2, 3],
      received: [1, 3],
      receipt: 'COMPLETE',
    })
    const quality = qualityFixture(group, 'QUALIFIED_WITH_EXCLUSIONS', { rejectedCamera: 2 })
    const viewSet = quality.confirmed_view_set
    const completed = approvedGroupTask(group, { confirmedViewSet: viewSet })
    stubGroupDetail(group, {
      task: completed,
      quality,
      candidateStatus: 'NO_HAND_DETECTED',
    })
    const user = userEvent.setup()
    renderCaptureGroup()

    await user.click(await screen.findByText('查看复核详情'))

    const rejectedViews = await screen.findByRole('list', { name: '排除 / 缺失机位' })
    expect(within(rejectedViews).getByText('机位 2')).toBeInTheDocument()
    expect(within(rejectedViews).getByText(/机位缺失/)).toBeInTheDocument()
    expect(
      within(rejectedViews).queryByText(/MISSING_VIEW|VIEW_NOT_QUALIFIED/),
    ).not.toBeInTheDocument()

    const eventEvidence = screen.getByRole('list', { name: '采集记录级参考视图证据' })
    const supportingItem = within(eventEvidence).getByText('机位 3').closest('li')
    expect(supportingItem).not.toBeNull()
    expect(
      within(supportingItem as HTMLElement).getByText('采集记录级参考证据'),
    ).toBeInTheDocument()
    expect(
      within(supportingItem as HTMLElement).getByText('不生成参考视图动作时间点'),
    ).toBeInTheDocument()
    expect(within(supportingItem as HTMLElement).queryByText(/100|900/)).not.toBeInTheDocument()
    expect(screen.getByText('历史正式动作区间')).toBeInTheDocument()
    expect(screen.queryByText(/first_timestamp_ms|last_timestamp_ms/)).not.toBeInTheDocument()
  })

  it('does not offer cleaning recheck actions after approval', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    const completed = approvedGroupTask(group)
    stubGroupDetail(group, {
      task: completed,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'NO_HAND_DETECTED',
    })
    const user = userEvent.setup()
    renderCaptureGroup()

    await user.click(await screen.findByText('查看复核详情'))
    expect(screen.queryByRole('button', { name: '重新执行标注检查' })).not.toBeInTheDocument()
  })

  it('lets an authorized reviewer approve a revision and immediately trigger HDF5 download', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    const viewSet = qualityFixture(group, 'QUALIFIED').confirmed_view_set
    const submitted = submittedGroupTask(group, { confirmedViewSet: viewSet })
    const completed = approvedGroupTask(group, { confirmedViewSet: viewSet })
    stubGroupDetail(group, {
      task: submitted,
      taskResponses: [submitted, completed],
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'HAND_DETECTED',
    })
    const download = vi
      .spyOn(annotationApi, 'downloadHumanDemonstrationHdf5')
      .mockResolvedValueOnce({ blob: new Blob(['synthetic']), fileName: 'synthetic.hdf5' })
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:synthetic-download')
    const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)

    const user = userEvent.setup()
    renderCaptureGroup()

    await user.click(await screen.findByRole('button', { name: '通过并下载 HDF5' }))

    await waitFor(() => {
      expect(download).toHaveBeenCalledWith(projectId, [groupId])
    })
    expect(click).toHaveBeenCalledTimes(1)
    expect(revoke).toHaveBeenCalledWith('blob:synthetic-download')
    expect(await screen.findByText('已开始下载 synthetic.hdf5。')).toBeInTheDocument()
  })

  it('does not render duplicate HDF5 download button in the approved review banner', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    const completed = approvedGroupTask(group)
    stubGroupDetail(group, {
      task: completed,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'HAND_DETECTED',
    })

    renderCaptureGroup()

    await screen.findByText('人工复核已通过')
    expect(screen.queryByRole('button', { name: '下载当前组 HDF5' })).not.toBeInTheDocument()
    expect(await screen.findByRole('button', { name: '下载 HDF5' })).toBeInTheDocument()
  })

  it('allows direct HDF5 download from the page header', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    const completed = approvedGroupTask(group)
    stubGroupDetail(group, {
      task: completed,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'HAND_DETECTED',
    })
    const download = vi
      .spyOn(annotationApi, 'downloadHumanDemonstrationHdf5')
      .mockResolvedValueOnce({ blob: new Blob(['synthetic']), fileName: 'synthetic.hdf5' })
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:synthetic-download')
    const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)

    const user = userEvent.setup()
    renderCaptureGroup()

    const headerDownloadBtn = await screen.findByRole('button', { name: '下载 HDF5' })
    await user.click(headerDownloadBtn)
    await waitFor(() => {
      expect(download).toHaveBeenCalledWith(projectId, [groupId])
    })
    expect(revoke).toHaveBeenCalledWith('blob:synthetic-download')
  })

  it('disables the header HDF5 download button when no hand pose was detected', async () => {
    const group = captureGroupFixture(groupId, {
      expected: [1, 2],
      received: [1, 2],
      receipt: 'COMPLETE',
    })
    const completed = approvedGroupTask(group)
    stubGroupDetail(group, {
      task: completed,
      quality: qualityFixture(group, 'QUALIFIED'),
      candidateStatus: 'NO_HAND_DETECTED',
    })

    renderCaptureGroup()

    const headerDownloadBtn = await screen.findByRole('button', { name: '下载 HDF5' })
    await waitFor(() => {
      expect(headerDownloadBtn).toBeDisabled()
    })
    expect(await screen.findByText(/人工复核已通过 · 未检出手部/)).toBeInTheDocument()
  })
})

function renderCaptureGroup() {
  renderRoute(<CaptureGroupDetailView />, {
    path: '/projects/:projectId/capture-groups/:captureGroupId',
    route: `/projects/${projectId}/capture-groups/${groupId}?focus=review`,
  })
}
