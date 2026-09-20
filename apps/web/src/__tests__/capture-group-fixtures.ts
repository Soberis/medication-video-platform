import { vi } from 'vitest'

import type {
  AnnotationLabelSchema,
  AnnotationReview,
  AnnotationTask,
  CaptureGroupCleaning,
  CaptureGroupEvidence,
  CaptureGroupViewSet,
} from '@/api/annotation'
import type {
  CaptureGroup,
  CaptureGroupQualityReview,
  CaptureGroupSummary,
} from '@/api/capture-groups'
import type { ClipAssetPlaybackGrant } from '@/api/catalog'
import type { QualityReview } from '@/api/quality'

export const projectId = '30000000-0000-4000-8000-000000000001'
export const groupId = '60000000-0000-4000-8000-000000000001'
export const taskId = '70000000-0000-4000-8000-000000000001'
export const schemaId = '80000000-0000-4000-8000-000000000001'
export const clipIds = [1, 2, 4, 5].map(
  (cameraNumber) => `50000000-0000-4000-8000-${String(cameraNumber).padStart(12, '0')}`,
)

export function stubGroupList(
  groups: CaptureGroup[],
  tasks: AnnotationTask[],
  options: {
    qualityByGroup?: Record<string, CaptureGroupQualityReview>
    taskScope?: 'PROJECT' | 'ASSIGNEE'
    projectRole?: 'ADMIN' | 'MEMBER'
    taskListError?: boolean
    qualityListError?: { status: number; errorCode?: string }
    groupListError?: { status: number; errorCode?: string }
    omitQualityFor?: string[]
    nextCursor?: string | null
  } = {},
) {
  const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
    const url = String(input)
    const path = new URL(url, 'http://localhost').pathname
    if (path === `/api/v1/projects/${projectId}`) {
      return Promise.resolve(
        jsonResponse({
          project_id: projectId,
          display_name: '合成数据项目',
          status: 'ACTIVE',
          current_role: options.projectRole ?? 'ADMIN',
          created_at: '2026-09-08T00:00:00Z',
        }),
      )
    }
    if (path === '/api/v1/projects') {
      return Promise.resolve(
        jsonResponse({
          items: [
            {
              project_id: projectId,
              display_name: '合成数据项目',
              status: 'ACTIVE',
              current_role: options.projectRole ?? 'ADMIN',
              created_at: '2026-09-08T00:00:00Z',
            },
          ],
          can_create_projects: true,
        }),
      )
    }
    if (path.endsWith('/capture-groups')) {
      if (options.groupListError) {
        return Promise.resolve(
          jsonResponse(
            { error_code: options.groupListError.errorCode ?? 'CAPTURE_GROUP_LIST_FAILED' },
            { status: options.groupListError.status },
          ),
        )
      }
      return Promise.resolve(
        jsonResponse({
          items: groups.map(summaryFromGroup),
          next_cursor: options.nextCursor ?? null,
        }),
      )
    }
    if (path.endsWith('/capture-group-quality-reviews')) {
      if (options.qualityListError) {
        return Promise.resolve(
          jsonResponse(
            {
              error_code:
                options.qualityListError.errorCode ?? 'CAPTURE_GROUP_QUALITY_REQUEST_FAILED',
            },
            { status: options.qualityListError.status },
          ),
        )
      }
      const requestedIds = new URL(url, 'http://localhost').searchParams.getAll('capture_group_id')
      return Promise.resolve(
        jsonResponse({
          items: requestedIds
            .filter((captureGroupId) => !options.omitQualityFor?.includes(captureGroupId))
            .map((captureGroupId) => {
              const qualityGroup = groups.find(
                (candidate) => candidate.capture_group_id === captureGroupId,
              )
              return (
                (qualityGroup && options.qualityByGroup?.[qualityGroup.capture_group_id]) ??
                (qualityGroup
                  ? qualityFixture(
                      qualityGroup,
                      qualityGroup.missing_camera_numbers.length
                        ? 'QUALIFIED_WITH_EXCLUSIONS'
                        : 'QUALIFIED',
                    )
                  : qualityFixture(groups[0]!, 'UNREVIEWED'))
              )
            }),
        }),
      )
    }
    if (path.endsWith('/annotation-tasks')) {
      if (options.taskListError) return Promise.reject(new Error('TASK_LIST_UNAVAILABLE'))
      return Promise.resolve(jsonResponse(taskList(tasks, options.taskScope)))
    }
    if (path.endsWith('/annotation-label-schema'))
      return Promise.resolve(jsonResponse(labelSchema()))
    const group = groups.find((candidate) =>
      url.endsWith(`/capture-groups/${candidate.capture_group_id}`),
    )
    if (group) return Promise.resolve(jsonResponse(group))
    if (url.includes('/quality-review')) {
      const qualityGroup = groups.find((candidate) => url.includes(candidate.capture_group_id))
      return Promise.resolve(
        jsonResponse(
          (qualityGroup && options.qualityByGroup?.[qualityGroup.capture_group_id]) ??
            (qualityGroup
              ? qualityFixture(
                  qualityGroup,
                  qualityGroup.missing_camera_numbers.length
                    ? 'QUALIFIED_WITH_EXCLUSIONS'
                    : 'QUALIFIED',
                )
              : qualityFixture(groups[0]!, 'QUALIFIED')),
        ),
      )
    }
    return Promise.reject(new Error(`Unexpected fetch: ${url}`))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

export function stubPagedGroupList(
  pages: Array<{ group: CaptureGroup; nextCursor: string | null }>,
  tasks: AnnotationTask[],
  options: {
    qualityListErrors?: Array<{ status: number; errorCode?: string } | null>
    taskListErrors?: boolean[]
    qualityByGroup?: Record<string, CaptureGroupQualityReview>
  } = {},
) {
  let pageIndex = 0
  let currentPageIndex = 0
  const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
    const url = String(input)
    const path = new URL(url, 'http://localhost').pathname
    if (path.endsWith('/capture-groups')) {
      currentPageIndex = Math.min(pageIndex++, pages.length - 1)
      const page = pages[currentPageIndex]!
      return Promise.resolve(
        jsonResponse({ items: [summaryFromGroup(page.group)], next_cursor: page.nextCursor }),
      )
    }
    if (path.endsWith('/capture-group-quality-reviews')) {
      const qualityError = options.qualityListErrors?.[currentPageIndex]
      if (qualityError) {
        return Promise.resolve(
          jsonResponse(
            { error_code: qualityError.errorCode ?? 'CAPTURE_GROUP_QUALITY_REQUEST_FAILED' },
            { status: qualityError.status },
          ),
        )
      }
      const requestedIds = new URL(url, 'http://localhost').searchParams.getAll('capture_group_id')
      return Promise.resolve(
        jsonResponse({
          items: requestedIds.map((captureGroupId) => {
            const page =
              pages.find(({ group }) => group.capture_group_id === captureGroupId) ?? pages[0]!
            return (
              options.qualityByGroup?.[captureGroupId] ?? qualityFixture(page.group, 'QUALIFIED')
            )
          }),
        }),
      )
    }
    if (path.endsWith('/annotation-tasks')) {
      if (options.taskListErrors?.[currentPageIndex]) {
        return Promise.reject(new Error('TASK_LIST_UNAVAILABLE'))
      }
      return Promise.resolve(jsonResponse(taskList(tasks)))
    }
    if (url.includes('/quality-review')) {
      const page = pages.find(({ group }) => url.includes(group.capture_group_id)) ?? pages[0]!
      return Promise.resolve(jsonResponse(qualityFixture(page.group, 'QUALIFIED')))
    }
    return Promise.reject(new Error(`Unexpected fetch: ${url}`))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

export function stubGroupDetail(
  group: CaptureGroup,
  options: {
    task: AnnotationTask | null
    taskResponses?: Array<AnnotationTask | null>
    taskAfterAssignment?: AnnotationTask
    groupResponses?: CaptureGroup[]
    taskScope?: 'PROJECT' | 'ASSIGNEE'
    quality: CaptureGroupQualityReview
    qualityError?: { status: number; errorCode?: string }
    receiptFinalizeResponse?: CaptureGroup
    receiptFinalizeError?: { status: number; errorCode?: string }
    receiptFinalizeGate?: Promise<void>
    playbackGrant?: ClipAssetPlaybackGrant
    candidateStatus: 'FAILED' | 'NO_HAND_DETECTED' | 'HAND_DETECTED' | 'UNKNOWN'
    candidateStatusByCamera?: Record<
      number,
      'FAILED' | 'NO_HAND_DETECTED' | 'HAND_DETECTED' | 'UNKNOWN'
    >
    viewQualityByCamera?: Record<number, QualityReview>
    viewQualityAfterSaveByCamera?: Record<number, QualityReview>
    reviewResponse?: Partial<AnnotationReview>
    reviewError?: { status: number; body: object }
    actionCandidates?: unknown[]
    actionCandidateJob?: unknown | null
  },
) {
  let taskResponseIndex = 0
  let groupResponseIndex = 0
  const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url.endsWith(`/capture-groups/${group.capture_group_id}/receipt/finalize`)) {
      if (options.receiptFinalizeError) {
        return Promise.resolve(
          jsonResponse(
            {
              error_code:
                options.receiptFinalizeError.errorCode ?? 'CAPTURE_GROUP_RECEIPT_FINALIZE_FAILED',
            },
            { status: options.receiptFinalizeError.status },
          ),
        )
      }
      if (options.receiptFinalizeGate) {
        return options.receiptFinalizeGate.then(() =>
          jsonResponse(options.receiptFinalizeResponse ?? group),
        )
      }
      return Promise.resolve(jsonResponse(options.receiptFinalizeResponse ?? group))
    }
    if (url.endsWith(`/capture-groups/${group.capture_group_id}`)) {
      const groupResponses = options.groupResponses ?? [group]
      const groupResponse =
        groupResponses[Math.min(groupResponseIndex++, groupResponses.length - 1)] ?? group
      return Promise.resolve(jsonResponse(groupResponse))
    }
    if (url.endsWith('/playback-grants') && init?.method === 'POST') {
      return Promise.resolve(
        jsonResponse(
          options.playbackGrant ?? {
            grant_id: 'playback-grant-synthetic',
            purpose: 'DATA_REVIEW',
            media_url: '/synthetic/authorized-video.mp4',
            expires_at: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
            content_type: 'video/mp4',
          },
        ),
      )
    }
    if (url.endsWith(`/capture-groups/${group.capture_group_id}/quality-review`)) {
      if (options.qualityError) {
        return Promise.resolve(
          jsonResponse(
            {
              error_code: options.qualityError.errorCode ?? 'CAPTURE_GROUP_QUALITY_REQUEST_FAILED',
            },
            { status: options.qualityError.status },
          ),
        )
      }
      return Promise.resolve(jsonResponse(options.quality))
    }
    if (url.endsWith(`/capture-groups/${group.capture_group_id}/quality-review/history`)) {
      return Promise.resolve(
        jsonResponse({ items: options.quality.result === 'UNREVIEWED' ? [] : [options.quality] }),
      )
    }
    if (
      url.endsWith(`/capture-groups/${group.capture_group_id}/annotation-tasks`) &&
      init?.method === 'POST'
    ) {
      return Promise.resolve(jsonResponse(options.taskAfterAssignment ?? options.task ?? {}))
    }
    if (url.endsWith('/annotation-tasks')) {
      const taskResponses = options.taskResponses ?? [options.task]
      const taskResponse =
        taskResponses[Math.min(taskResponseIndex++, taskResponses.length - 1)] ?? null
      return Promise.resolve(
        jsonResponse(taskList(taskResponse ? [taskResponse] : [], options.taskScope)),
      )
    }
    if (url.endsWith('/annotation-label-schema')) {
      return Promise.resolve(jsonResponse(labelSchema()))
    }
    if (url.includes('/quality-review') && url.includes('/clip-assets/')) {
      const clipAssetId = url.split('/clip-assets/')[1]?.split('/')[0]
      const cameraNumber = group.streams.find(
        (stream) => stream.clip_asset_id === clipAssetId,
      )?.camera_number
      if (init?.method === 'PUT' && cameraNumber !== undefined) {
        const current =
          options.viewQualityByCamera?.[cameraNumber] ??
          (viewQuality(clipAssetId ?? '', options.quality) as QualityReview)
        const request = JSON.parse(String(init.body ?? '{}')) as {
          decision?: QualityReview['decision']
          reason_code?: QualityReview['reason_code']
        }
        const next =
          options.viewQualityAfterSaveByCamera?.[cameraNumber] ??
          ({
            ...current,
            decision: request.decision ?? 'QUALIFIED',
            reason_code: request.reason_code ?? 'MEETS_REQUIREMENTS',
            reviewed_by: 'reviewer',
            reviewed_at: '2026-08-20T01:02:00Z',
            version: current.version + 1,
          } satisfies QualityReview)
        if (options.viewQualityByCamera) options.viewQualityByCamera[cameraNumber] = next
        return Promise.resolve(jsonResponse(next))
      }
      return Promise.resolve(
        jsonResponse(
          cameraNumber !== undefined
            ? (options.viewQualityByCamera?.[cameraNumber] ??
                (viewQuality(clipAssetId ?? '', options.quality) as QualityReview))
            : viewQuality(clipAssetId ?? '', options.quality),
        ),
      )
    }
    if (
      url.includes('/clip-assets/') &&
      !url.includes('/quality-review') &&
      !url.includes('/media-report')
    ) {
      const clipAssetId = url.split('/clip-assets/')[1]
      return Promise.resolve(jsonResponse(clipAsset(group, clipAssetId ?? '')))
    }
    if (url.endsWith('/hand-pose-candidates')) {
      const clipAssetId = url.split('/videos/')[1]?.split('/')[0]
      const cameraNumber = group.streams.find(
        (stream) => stream.clip_asset_id === clipAssetId,
      )?.camera_number
      const status =
        (cameraNumber !== undefined
          ? options.candidateStatusByCamera?.[cameraNumber]
          : undefined) ?? options.candidateStatus
      return Promise.resolve(jsonResponse(handPoseResponse(group, clipAssetId ?? '', status)))
    }
    if (url.endsWith('/media-report')) {
      return Promise.resolve(jsonResponse(mediaReport(group, url)))
    }
    if (url.endsWith('/primary-view') && init?.method === 'PUT') {
      return Promise.resolve(jsonResponse(options.task ?? {}))
    }
    if (
      url.includes('/annotation-revisions/') &&
      url.endsWith('/review') &&
      init?.method === 'POST'
    ) {
      if (options.reviewError) {
        return Promise.resolve(
          jsonResponse(options.reviewError.body, { status: options.reviewError.status }),
        )
      }
      const request = JSON.parse(String(init.body ?? '{}')) as {
        decision?: AnnotationReview['decision']
        review_note?: string
      }
      const revision = options.task?.annotation_revision
      const decision = request.decision ?? 'APPROVED'
      return Promise.resolve(
        jsonResponse({
          ...captureGroupReviewFixture(
            group,
            revision?.annotation_revision_id ?? 'revision-1',
            decision,
            {
              viewSet: revision?.confirmed_view_set ?? options.quality.confirmed_view_set,
            },
          ),
          review_note: request.review_note ?? null,
          ...options.reviewResponse,
        }),
      )
    }
    if (url.endsWith('/revisions') && init?.method === 'POST') {
      return Promise.resolve(
        jsonResponse({
          annotation_revision_id: '90000000-0000-4000-8000-000000000001',
          project_id: projectId,
          annotation_task_id: taskId,
          clip_asset_id: group.streams[0]?.clip_asset_id,
          revision_number: 1,
          submitted_by: 'reviewer',
          submitted_at: '2026-08-20T01:00:00Z',
        }),
      )
    }
    if (url.endsWith('/action-candidates')) {
      return Promise.resolve(jsonResponse(options.actionCandidates ?? []))
    }
    if (url.endsWith('/action-candidate-job')) {
      return Promise.resolve(jsonResponse(options.actionCandidateJob ?? null))
    }
    if (url.endsWith('/action-candidate-jobs') && init?.method === 'POST') {
      return Promise.resolve(
        jsonResponse({
          job_id: 'synthetic-action-job',
          project_id: projectId,
          capture_group_id: group.capture_group_id,
          clip_asset_id: group.streams[0]?.clip_asset_id ?? '',
          status: 'QUEUED',
          algorithm_key: 'QWEN3_5_ACTION',
          error_code: null,
          error_message: null,
          created_at: '2026-09-10T00:00:00Z',
          updated_at: '2026-09-10T00:00:00Z',
        }),
      )
    }
    return Promise.reject(new Error(`Unexpected fetch: ${init?.method ?? 'GET'} ${url}`))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

export function captureGroupFixture(
  captureGroupId: string,
  options: {
    expected: number[]
    received: number[]
    receipt: 'RECEIVING' | 'COMPLETE' | 'INCOMPLETE'
    key?: string
    deviceTime?: string | null
  },
): CaptureGroup {
  const members = options.expected.map((cameraNumber) => ({
    camera_channel_id: `40000000-0000-4000-8000-${String(cameraNumber).padStart(12, '0')}`,
    camera_index: cameraNumber - 1,
    camera_number: cameraNumber,
    camera_role: `camera-${cameraNumber}`,
    mount_position: `position-${cameraNumber}`,
    enabled: true,
    enabled_for_hand_detection: true,
  }))
  const streams: CaptureGroup['streams'] = options.received.map((cameraNumber) => ({
    camera_stream_id: `41000000-0000-4000-8000-${String(cameraNumber).padStart(12, '0')}`,
    clip_asset_id: `50000000-0000-4000-8000-${String(cameraNumber).padStart(12, '0')}`,
    client_file_id: `camera-${cameraNumber}.mp4`,
    camera_channel_id: `40000000-0000-4000-8000-${String(cameraNumber).padStart(12, '0')}`,
    camera_index: cameraNumber - 1,
    camera_number: cameraNumber,
    camera_role: `camera-${cameraNumber}`,
    mount_position: `position-${cameraNumber}`,
    record_start_utc: '2026-08-20T01:00:00Z',
    time_offset_ms: cameraNumber * 10,
    sync_status: cameraNumber === 1 ? 'WARNING' : 'PASS',
    stream_status: 'INGESTED',
    quality_status: 'PASS',
    enabled_for_hand_detection: true,
  }))
  return {
    capture_session_id: captureGroupId,
    capture_group_id: captureGroupId,
    project_id: projectId,
    batch_id: '42000000-0000-4000-8000-000000000001',
    device_id: 'synthetic-device',
    metadata_upload_file_id: null,
    metadata_schema_version: 'capture-session-metadata-v1',
    metadata_sha256: null,
    expected_camera_count: options.expected.length,
    received_camera_count: options.received.length,
    sync_group_id: 'sync-synthetic',
    device_time_utc: options.deviceTime === undefined ? '2026-08-20T01:00:00Z' : options.deviceTime,
    device_timezone: 'Asia/Shanghai',
    server_receive_time: '2026-08-20T01:00:02Z',
    assembly_status: options.receipt === 'RECEIVING' ? 'OPEN' : options.receipt,
    quality_status: 'PASS',
    capture_group_key: options.key ?? 'capture-1',
    capture_group_key_source: 'UPSTREAM_MANIFEST_KEY',
    capture_group_manifest_id: null,
    expected_camera_numbers: options.expected,
    received_camera_numbers: options.received,
    missing_camera_numbers: options.expected.filter(
      (cameraNumber) => !options.received.includes(cameraNumber),
    ),
    receipt_state: options.receipt,
    receipt_finalized_at: options.receipt === 'RECEIVING' ? null : '2026-08-20T01:01:00Z',
    camera_set_snapshot: {
      camera_set_snapshot_id: '43000000-0000-4000-8000-000000000001',
      source_camera_set_id: '44000000-0000-4000-8000-000000000001',
      camera_set_key: 'synthetic-set',
      configuration_version: 1,
      layout_version: 'v1',
      expected_camera_count: options.expected.length,
      members,
    },
    streams,
    created_at: '2026-08-20T01:00:02Z',
  }
}

export function qualityFixture(
  group: CaptureGroup,
  result: CaptureGroupQualityReview['result'],
  options: { rejectedCamera?: number } = {},
): CaptureGroupQualityReview {
  return {
    capture_group_quality_review_id:
      result === 'UNREVIEWED' ? null : '45000000-0000-4000-8000-000000000001',
    project_id: projectId,
    capture_group_id: group.capture_group_id,
    result,
    reason: result === 'UNREVIEWED' ? null : 'synthetic human decision',
    reviewer_actor_id: result === 'UNREVIEWED' ? null : 'reviewer',
    revision: result === 'UNREVIEWED' ? 0 : 1,
    version: result === 'UNREVIEWED' ? 0 : 1,
    decided_at: result === 'UNREVIEWED' ? null : '2026-08-20T01:02:00Z',
    confirmed_view_set:
      result === 'UNREVIEWED'
        ? null
        : {
            schema_version: 'capture-group-view-set.v1',
            capture_group_id: group.capture_group_id,
            capture_group_version: 1,
            camera_set_snapshot_id: group.camera_set_snapshot.camera_set_snapshot_id,
            receipt_state: group.receipt_state,
            views: group.expected_camera_numbers.map((cameraNumber) => {
              const stream = group.streams.find(
                (candidate) => candidate.camera_number === cameraNumber,
              )
              const rejected = options.rejectedCamera === cameraNumber
              return {
                camera_number: cameraNumber,
                camera_stream_id: stream?.camera_stream_id ?? null,
                clip_asset_id: stream?.clip_asset_id ?? null,
                camera_channel_id: stream?.camera_channel_id ?? null,
                present: Boolean(stream),
                media_status: stream?.stream_status ?? 'MISSING',
                technical_quality_status: stream?.quality_status ?? null,
                stream_version: 1,
                clip_asset_version: stream ? 1 : 0,
                asset_available: Boolean(stream),
                quality_review_decision: rejected
                  ? 'REJECTED'
                  : stream
                    ? 'QUALIFIED'
                    : 'UNREVIEWED',
                quality_review_version: rejected || stream ? 1 : 0,
              }
            }),
          },
    input_version: result === 'UNREVIEWED' ? null : 'capture-group-view-set.v1:synthetic',
    confirmed_view_set_sha256: null,
  }
}

export function viewQuality(clipAssetId: string, quality: CaptureGroupQualityReview) {
  const view = quality.confirmed_view_set?.views.find(
    (candidate) => candidate.clip_asset_id === clipAssetId,
  )
  return {
    quality_review_id:
      view?.quality_review_decision === 'REJECTED' ? '46000000-0000-4000-8000-000000000001' : null,
    project_id: projectId,
    clip_asset_id: clipAssetId,
    decision: view?.quality_review_decision === 'REJECTED' ? 'REJECTED' : 'QUALIFIED',
    reason_code:
      view?.quality_review_decision === 'REJECTED' ? 'OTHER_QUALITY_FAILURE' : 'MEETS_REQUIREMENTS',
    reviewed_by: 'reviewer',
    reviewed_at: '2026-08-20T01:02:00Z',
    version: view?.quality_review_version ?? 0,
  }
}

export function groupTask(
  captureGroupId: string,
  canSubmit: boolean,
  options: { revision?: boolean } = {},
): AnnotationTask {
  const primaryClipAssetId = clipIds[0] ?? '50000000-0000-4000-8000-000000000001'
  const confirmedViewSet = taskViewSet(captureGroupId)
  return {
    annotation_task_id: taskId,
    project_id: projectId,
    capture_group_id: captureGroupId,
    primary_clip_asset_id: primaryClipAssetId,
    primary_camera_number: 1,
    capture_group_quality_review_id: '45000000-0000-4000-8000-000000000001',
    group_quality_revision: 1,
    confirmed_view_set: confirmedViewSet,
    group_input_version: 'capture-group-view-set.v1:synthetic',
    label_schema_id: schemaId,
    label_schema_digest: 'a'.repeat(64),
    assignee_actor_id: 'reviewer',
    created_by: 'system',
    status: options.revision ? 'COMPLETED' : 'ASSIGNED',
    can_submit: options.revision ? false : canSubmit,
    can_select_primary: options.revision ? false : true,
    can_review: false,
    can_recheck_capture_group_cleaning: Boolean(options.revision),
    annotation_revision: options.revision
      ? {
          annotation_revision_id: '47000000-0000-4000-8000-000000000001',
          label_schema_id: schemaId,
          label_schema_digest: 'a'.repeat(64),
          revision_number: 1,
          annotation_document: {
            schema_version: 'annotation-document.v1',
            segments: [{ start_ms: 0, end_ms: 1000, label: 'acquire_item' }],
          },
          submitted_by: 'reviewer',
          submitted_at: '2026-08-20T01:03:00Z',
          capture_group_id: captureGroupId,
          primary_clip_asset_id: primaryClipAssetId,
          primary_camera_number: 1,
          confirmed_view_set: confirmedViewSet,
          input_version: 'capture-group-view-set.v1:synthetic',
          review: {
            annotation_review_id: '48000000-0000-4000-8000-000000000001',
            project_id: projectId,
            annotation_revision_id: '47000000-0000-4000-8000-000000000001',
            decision: 'APPROVED',
            review_note: null,
            reviewed_by: 'admin',
            reviewed_at: '2026-08-20T01:04:00Z',
            capture_group_id: captureGroupId,
            revision: 1,
            primary_clip_asset_id: primaryClipAssetId,
            primary_camera_number: 1,
            confirmed_view_set: confirmedViewSet,
            input_version: 'capture-group-view-set.v1:synthetic',
            qualified_views: [],
            rejected_missing_views: [],
            candidate_evidence: [],
          },
          capture_group_cleaning: {
            capture_group_cleaning_id: '49000000-0000-4000-8000-000000000001',
            project_id: projectId,
            capture_group_id: captureGroupId,
            annotation_revision_id: '47000000-0000-4000-8000-000000000001',
            annotation_review_id: '48000000-0000-4000-8000-000000000001',
            group_quality_result: 'QUALIFIED',
            group_quality_revision: 1,
            primary_clip_asset_id: clipIds[0] ?? '',
            primary_camera_number: 1,
            participating_views: [],
            excluded_views: [],
            rule_set_key: 'capture-group-cleaning',
            rule_set_version: 'v1',
            rule_set_digest: 'b'.repeat(64),
            outcome: 'PASS',
            reason_codes: [],
            executed_checks: ['GROUP_QUALITY'],
            evaluated_at: '2026-08-20T01:05:00Z',
          },
        }
      : null,
    created_at: '2026-08-20T01:02:00Z',
    updated_at: '2026-08-20T01:04:00Z',
    version: options.revision ? 2 : 0,
  }
}

export function captureGroupReviewFixture(
  group: CaptureGroup,
  annotationRevisionId: string,
  decision: AnnotationReview['decision'],
  options: {
    viewSet?: CaptureGroupViewSet | null
    reviewNote?: string | null
  } = {},
): AnnotationReview {
  const viewSet =
    options.viewSet ?? qualityFixture(group, 'QUALIFIED_WITH_EXCLUSIONS').confirmed_view_set
  if (!viewSet) throw new Error('The synthetic CaptureGroup review fixture requires a View Set.')
  return {
    annotation_review_id: '48000000-0000-4000-8000-000000000001',
    project_id: projectId,
    annotation_revision_id: annotationRevisionId,
    decision,
    review_note: options.reviewNote ?? null,
    reviewed_by: 'admin',
    reviewed_at: '2026-08-20T01:04:00Z',
    capture_group_id: group.capture_group_id,
    revision: 1,
    primary_clip_asset_id:
      viewSet?.views.find((view) => view.camera_number === 1)?.clip_asset_id ??
      group.streams[0]?.clip_asset_id ??
      clipIds[0]!,
    primary_camera_number: 1,
    confirmed_view_set: viewSet,
    input_version: 'capture-group-view-set.v1:synthetic',
    qualified_views: qualifiedViewsFromViewSet(viewSet),
    rejected_missing_views: rejectedViewsFromViewSet(viewSet),
    candidate_evidence: [
      {
        camera_number: 1,
        clip_asset_id:
          viewSet?.views.find((view) => view.camera_number === 1)?.clip_asset_id ??
          group.streams[0]?.clip_asset_id ??
          null,
        ai_job_id: '4b000000-0000-4000-8000-000000000001',
        algorithm_key: 'MEDIAPIPE_HAND_LANDMARKER',
        algorithm_version: 'v1',
        model_sha256: 'd'.repeat(64),
        profile_sha256: 'f'.repeat(64),
        candidate_count: 2,
      },
    ],
  }
}

export function captureGroupCleaningFixture(
  group: CaptureGroup,
  options: {
    outcome?: CaptureGroupCleaning['outcome']
    viewSet?: CaptureGroupViewSet | null
    annotationRevisionId?: string
  } = {},
): CaptureGroupCleaning {
  const outcome = options.outcome ?? 'PASS'
  const viewSet =
    options.viewSet ?? qualityFixture(group, 'QUALIFIED_WITH_EXCLUSIONS').confirmed_view_set
  const primaryCameraNumber = 1
  const primaryClipAssetId =
    viewSet?.views.find((view) => view.camera_number === primaryCameraNumber)?.clip_asset_id ??
    group.streams[0]?.clip_asset_id ??
    clipIds[0]!
  const primaryInterval: CaptureGroupEvidence[] =
    outcome === 'PASS' ? [{ start_ms: 100, end_ms: 900, label: 'acquire_item' }] : []
  const participatingViews = qualifiedViewsFromViewSet(viewSet)
  const excludedViews = rejectedViewsFromViewSet(viewSet)
  const viewEvidence = participatingViews.map((view) => {
    const isPrimary = numberValue(view, 'camera_number') === primaryCameraNumber
    return {
      ...view,
      interval_source: isPrimary ? 'PRIMARY_AUTHORITATIVE' : 'EVENT_LEVEL_EVIDENCE',
      action_aligned_interval: isPrimary,
      ...(isPrimary ? { interval: primaryInterval } : {}),
      evidence_scope: isPrimary ? 'FORMAL_ACTION_TIMELINE' : 'EVENT_LEVEL',
    }
  })
  return {
    capture_group_cleaning_id: '4c000000-0000-4000-8000-000000000001',
    project_id: projectId,
    capture_group_id: group.capture_group_id,
    annotation_revision_id: options.annotationRevisionId ?? '47000000-0000-4000-8000-000000000001',
    annotation_review_id: '48000000-0000-4000-8000-000000000001',
    annotation_review_decision: 'APPROVED',
    group_quality_result: 'QUALIFIED_WITH_EXCLUSIONS',
    group_quality_revision: 1,
    primary_clip_asset_id: primaryClipAssetId,
    primary_camera_number: primaryCameraNumber,
    primary_interval: primaryInterval,
    view_evidence: viewEvidence,
    participating_views: participatingViews,
    excluded_views: excludedViews,
    mapping_provenance: [],
    candidate_provenance: [],
    rule_set_key: 'capture-group-event-eligibility',
    rule_set_version: 'v1',
    rule_set_digest: 'b'.repeat(64),
    outcome,
    reason_codes: outcome === 'PASS' ? [] : ['GROUP_QUALITY_NOT_ADMITTING'],
    executed_checks: [
      'REVIEW_APPROVED',
      'GROUP_QUALITY_ADMITTING',
      'PRIMARY_VIEW_AUTHORITATIVE_INTERVAL',
      'QUALIFIED_VIEWS_ONLY',
      'SUPPORTING_VIEWS_EVENT_LEVEL_EVIDENCE',
      'RAW_MEDIA_RETAINED',
    ],
    input_fingerprint: 'c'.repeat(64),
    evaluated_by: 'system',
    authorization_basis: 'capture-group-cleaning-v1',
    evaluated_at: '2026-08-20T01:05:00Z',
  }
}

export function submittedGroupTask(
  group: CaptureGroup,
  options: {
    canReview?: boolean
    confirmedViewSet?: CaptureGroupViewSet | null
    annotationRevisionId?: string
    note?: string
  } = {},
): AnnotationTask {
  const base = groupTask(group.capture_group_id, false, { revision: true })
  const viewSet =
    options.confirmedViewSet ??
    qualityFixture(group, 'QUALIFIED_WITH_EXCLUSIONS').confirmed_view_set
  if (!viewSet) throw new Error('The synthetic CaptureGroup task fixture requires a View Set.')
  const revision = base.annotation_revision!
  const annotationRevisionId = options.annotationRevisionId ?? revision.annotation_revision_id
  return {
    ...base,
    status: 'SUBMITTED',
    can_submit: false,
    can_review: options.canReview ?? true,
    can_recheck_capture_group_cleaning: false,
    confirmed_view_set: viewSet,
    annotation_revision: {
      ...revision,
      annotation_revision_id: annotationRevisionId,
      annotation_document: {
        ...revision.annotation_document,
        note: options.note ?? '本次正式动作 Revision 备注',
      },
      submitted_by: 'annotator',
      submitted_at: '2026-08-20T01:03:00Z',
      capture_group_id: group.capture_group_id,
      primary_clip_asset_id: group.streams[0]?.clip_asset_id ?? clipIds[0]!,
      primary_camera_number: 1,
      confirmed_view_set: viewSet,
      input_version: 'capture-group-view-set.v1:synthetic',
      review: null,
      capture_group_cleaning: null,
    },
    version: 1,
  }
}

function taskViewSet(captureGroupId: string): CaptureGroupViewSet {
  return {
    schema_version: 'capture-group-view-set.v1',
    capture_group_id: captureGroupId,
    capture_group_version: 1,
    camera_set_snapshot_id: '43000000-0000-4000-8000-000000000001',
    receipt_state: 'COMPLETE',
    views: [],
  }
}

export function rejectedGroupTask(
  group: CaptureGroup,
  options: Parameters<typeof submittedGroupTask>[1] & { reviewNote?: string | null } = {},
): AnnotationTask {
  const submitted = submittedGroupTask(group, options)
  const revision = submitted.annotation_revision!
  const review = captureGroupReviewFixture(group, revision.annotation_revision_id, 'REJECTED', {
    viewSet: revision.confirmed_view_set,
    reviewNote: options.reviewNote,
  })
  return {
    ...submitted,
    status: 'ASSIGNED',
    can_submit: true,
    can_review: false,
    annotation_revision: { ...revision, review },
    version: 2,
  }
}

export function approvedGroupTask(
  group: CaptureGroup,
  options: Parameters<typeof submittedGroupTask>[1] & {
    cleaning?: CaptureGroupCleaning
    canRecheck?: boolean
  } = {},
): AnnotationTask {
  const submitted = submittedGroupTask(group, options)
  const revision = submitted.annotation_revision!
  const review = captureGroupReviewFixture(group, revision.annotation_revision_id, 'APPROVED', {
    viewSet: revision.confirmed_view_set,
  })
  const cleaning =
    options.cleaning ??
    captureGroupCleaningFixture(group, {
      viewSet: revision.confirmed_view_set,
      annotationRevisionId: revision.annotation_revision_id,
    })
  return {
    ...submitted,
    status: 'COMPLETED',
    can_submit: false,
    can_review: false,
    can_recheck_capture_group_cleaning: options.canRecheck ?? false,
    annotation_revision: { ...revision, review, capture_group_cleaning: cleaning },
    version: 2,
  }
}

function qualifiedViewsFromViewSet(viewSet: CaptureGroupViewSet | null | undefined) {
  return (viewSet?.views ?? [])
    .filter(
      (view) =>
        view.present &&
        view.asset_available &&
        view.media_status === 'INGESTED' &&
        view.quality_review_decision === 'QUALIFIED',
    )
    .map((view) => ({
      camera_number: view.camera_number,
      clip_asset_id: view.clip_asset_id,
      present: view.present,
      media_status: view.media_status,
      quality_decision: view.quality_review_decision,
    }))
}

function rejectedViewsFromViewSet(viewSet: CaptureGroupViewSet | null | undefined) {
  return (viewSet?.views ?? [])
    .filter(
      (view) =>
        !view.present ||
        !view.asset_available ||
        view.media_status !== 'INGESTED' ||
        view.quality_review_decision !== 'QUALIFIED',
    )
    .map((view) => ({
      camera_number: view.camera_number,
      clip_asset_id: view.clip_asset_id,
      present: view.present,
      media_status: view.media_status,
      quality_decision: view.quality_review_decision,
      rejection_reason: !view.present ? 'MISSING_VIEW' : 'VIEW_NOT_QUALIFIED',
    }))
}

function numberValue(evidence: CaptureGroupEvidence, key: string) {
  const value = evidence[key]
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

export function taskList(items: AnnotationTask[], scope: 'PROJECT' | 'ASSIGNEE' = 'PROJECT') {
  return {
    items,
    progress: {
      scope,
      total: items.length,
      awaiting_submission: items.filter((item) => item.status === 'ASSIGNED').length,
      rework_required: 0,
      awaiting_review: items.filter((item) => item.status === 'SUBMITTED').length,
      completed: items.filter((item) => item.status === 'COMPLETED').length,
    },
  }
}

export function labelSchema(): AnnotationLabelSchema {
  return {
    label_schema_id: schemaId,
    schema_key: 'medication-primary-step',
    schema_version: 'p0-atomic-action.v4',
    schema_digest: 'a'.repeat(64),
    layer: 'primary_step',
    labels: [
      {
        label_id: 'acquire_item',
        display_name: '拿取容器/工具',
        description:
          '手明确到达并取得将参与配药的容器或工具，物体离开原稳定位置并进入操作区；不标持续持有、调整位置或重复抓握',
        sort_order: 1,
      },
      {
        label_id: 'open_container',
        display_name: '开启容器',
        description:
          '明确打开药品主容器，如掰开安瓿或移除西林瓶/输液容器的保护部件；不包括拆注射器包装',
        sort_order: 2,
      },
      {
        label_id: 'disinfect_access',
        display_name: '消毒容器接口',
        description: '看到棉签或其他消毒用品实际擦拭瓶塞、安瓿颈或容器接口；仅出现用品不构成该动作',
        sort_order: 3,
      },
      {
        label_id: 'insert_needle',
        display_name: '针头进入容器',
        description: '清楚看到针头穿过瓶塞或接口并进入目标药品容器；仅靠近或被遮挡时不标注',
        sort_order: 4,
      },
      {
        label_id: 'draw_liquid',
        display_name: '抽取药液',
        description:
          '针头已进入容器且活塞向后移动，能看到药液进入注射器；仅进针没有抽液证据时不标注',
        sort_order: 5,
      },
      {
        label_id: 'transfer_liquid',
        display_name: '转移药液',
        description:
          '使用注射器把药液从一个容器推入另一个目标容器；要求能确认来源、目标和推注方向，不表示面向患者给药',
        sort_order: 6,
      },
      {
        label_id: 'agitate_solution',
        display_name: '混匀药液',
        description: '明确摇晃、翻转或振荡装有药液的容器以混匀；仅拿着或移动容器不标注',
        sort_order: 7,
      },
      {
        label_id: 'discard_sharps',
        display_name: '处置锐器',
        description: '明确把针头、安瓿或其他锐器放入锐器盒；仅出现锐器盒或丢弃普通耗材不标注',
        sort_order: 8,
      },
      {
        label_id: 'label_prepared_medication',
        display_name: '给成品贴签',
        description:
          '看到标签实际粘贴到注射器、药瓶或成品输液容器；仅查看、拿着标签或成品包装不标注',
        sort_order: 9,
      },
    ],
  }
}

export function summaryFromGroup(group: CaptureGroup): CaptureGroupSummary {
  return {
    capture_group_id: group.capture_group_id,
    capture_group_key: group.capture_group_key,
    device_time_utc: group.device_time_utc,
    receipt_state: group.receipt_state,
    expected_camera_count: group.expected_camera_count,
    received_camera_count: group.received_camera_count,
    missing_camera_numbers: group.missing_camera_numbers,
    created_at: group.created_at,
  }
}

function clipAsset(group: CaptureGroup, clipAssetId: string) {
  const stream = group.streams.find((candidate) => candidate.clip_asset_id === clipAssetId)
  return {
    clip_asset_id: clipAssetId,
    batch_id: group.batch_id,
    source_upload_file_id: 'source-file',
    client_file_id: stream?.client_file_id ?? 'unknown.mp4',
    content_type: 'video/mp4',
    size_bytes: 1024,
    sha256: 'c'.repeat(64),
    status: stream?.stream_status === 'INGESTED' ? 'INGESTED' : 'INGEST_FAILED',
    session_binding: null,
    created_at: group.created_at,
    updated_at: group.created_at,
  }
}

function handPoseResponse(
  group: CaptureGroup,
  clipAssetId: string,
  status: 'FAILED' | 'NO_HAND_DETECTED' | 'HAND_DETECTED' | 'UNKNOWN',
) {
  const jobStatus = status === 'FAILED' ? 'FAILED' : 'SUCCEEDED'
  const handResult =
    status === 'HAND_DETECTED'
      ? 'HAND_DETECTED'
      : status === 'NO_HAND_DETECTED'
        ? 'NO_HAND_DETECTED'
        : 'UNKNOWN'
  return {
    job: {
      ai_job_id: '4b000000-0000-4000-8000-000000000001',
      project_id: projectId,
      clip_asset_id: clipAssetId,
      algorithm_key: 'MEDIAPIPE_HAND_LANDMARKER',
      algorithm_version: 'v1',
      model_sha256: 'd'.repeat(64),
      input_sha256: 'e'.repeat(64),
      input_size_bytes: 1024,
      sampling_fps: 10,
      profile_sha256: 'f'.repeat(64),
      status: jobStatus,
      hand_result: handResult,
      run_summary: {
        analysis_complete: status !== 'FAILED',
        hand_result: handResult,
        total_video_frames: 10,
        inference_frame_count: 10,
        frames_with_hand: handResult === 'HAND_DETECTED' ? 10 : 0,
        frames_without_hand: handResult === 'HAND_DETECTED' ? 0 : 10,
        left_hand_detection_count: handResult === 'HAND_DETECTED' ? 5 : 0,
        right_hand_detection_count: handResult === 'HAND_DETECTED' ? 5 : 0,
        invalid_landmark_count: 0,
        processing_duration_ms: 10,
        started_at: group.created_at,
        completed_at: group.created_at,
        failure_reason: status === 'FAILED' ? 'AI_HAND_POSE_INFERENCE_FAILED' : null,
        retryable: status === 'FAILED',
      },
      requested_by: 'system',
      created_at: group.created_at,
      updated_at: group.created_at,
    },
    candidates: [],
  }
}

function mediaReport(group: CaptureGroup, url: string) {
  const clipAssetId = url.split('/clip-assets/')[1]?.split('/')[0] ?? ''
  return {
    media_report_id: '4c000000-0000-4000-8000-000000000001',
    media_job_id: '4d000000-0000-4000-8000-000000000001',
    project_id: projectId,
    clip_asset_id: clipAssetId,
    container_format: 'mp4',
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
    audio_stream_count: 0,
    decode_verified: true,
    input_digest_verified: true,
    warning_codes: [],
    created_at: group.created_at,
  }
}

export function jsonResponse(body: unknown, init?: ResponseInit) {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
}
