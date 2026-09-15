import type { Page, Route } from '@playwright/test'

export const projectId = '30000000-0000-4000-8000-000000000001'
export const listGroupId = '60000000-0000-4000-8000-000000000001'
export const oneCameraGroupId = '60000000-0000-4000-8000-000000000002'
export const multiCameraGroupId = '60000000-0000-4000-8000-000000000003'
export const completedGroupId = '60000000-0000-4000-8000-000000000004'

export type CaptureGroupScenario = 'list' | 'n1-active' | 'n2-active' | 'completed' | 'unassigned'

type CameraStream = {
  camera_stream_id: string
  clip_asset_id: string
  client_file_id: string
  camera_channel_id: string
  camera_index: number
  camera_number: number
  camera_role: string
  mount_position: string
  record_start_utc: string
  time_offset_ms: number
  sync_status: 'PASS' | 'WARNING'
  stream_status: 'INGESTED'
  quality_status: 'PASS'
  enabled_for_hand_detection: true
}

type CaptureGroup = {
  capture_group_id: string
  project_id: string
  batch_id: string
  device_id: string
  expected_camera_count: number
  received_camera_count: number
  expected_camera_numbers: number[]
  received_camera_numbers: number[]
  missing_camera_numbers: number[]
  capture_group_key: string
  capture_group_key_source: string
  device_time_utc: string
  device_timezone: string
  server_receive_time: string
  assembly_status: 'COMPLETE' | 'INCOMPLETE'
  quality_status: 'PASS'
  receipt_state: 'COMPLETE' | 'INCOMPLETE'
  receipt_finalized_at: string
  camera_set_snapshot: {
    camera_set_snapshot_id: string
    source_camera_set_id: string
    camera_set_key: string
    configuration_version: number
    layout_version: string
    expected_camera_count: number
    members: Array<{
      camera_channel_id: string
      camera_index: number
      camera_number: number
      camera_role: string
      mount_position: string
      enabled: true
      enabled_for_hand_detection: true
    }>
  }
  streams: CameraStream[]
  created_at: string
}

type ViewSet = {
  schema_version: 'capture-group-view-set.v1'
  capture_group_id: string
  capture_group_version: number
  camera_set_snapshot_id: string
  receipt_state: 'COMPLETE' | 'INCOMPLETE'
  views: Array<{
    camera_number: number
    camera_stream_id: string | null
    clip_asset_id: string | null
    camera_channel_id: string | null
    present: boolean
    media_status: 'INGESTED' | 'MISSING'
    technical_quality_status: 'PASS' | null
    stream_version: number
    clip_asset_version: number
    asset_available: boolean
    quality_review_decision: 'QUALIFIED' | 'UNREVIEWED'
    quality_review_version: number
  }>
}

type QualityReview = {
  capture_group_quality_review_id: string
  project_id: string
  capture_group_id: string
  result: 'QUALIFIED' | 'QUALIFIED_WITH_EXCLUSIONS'
  reason: string
  reviewer_actor_id: string
  revision: number
  version: number
  decided_at: string
  confirmed_view_set: ViewSet
  input_version: string
  confirmed_view_set_sha256: null
}

type AnnotationTask = {
  annotation_task_id: string
  project_id: string
  capture_group_id: string
  primary_clip_asset_id: string
  primary_camera_number: number
  capture_group_quality_review_id: string
  group_quality_revision: number
  confirmed_view_set: ViewSet
  group_input_version: string
  label_schema_id: string
  label_schema_digest: string
  assignee_actor_id: string
  created_by: string
  status: 'ASSIGNED' | 'COMPLETED'
  can_submit: boolean
  can_select_primary: boolean
  can_review: false
  can_recheck_capture_group_cleaning: boolean
  annotation_revision: AnnotationRevision | null
  created_at: string
  updated_at: string
  version: number
}

type AnnotationRevision = {
  annotation_revision_id: string
  label_schema_id: string
  label_schema_digest: string
  revision_number: number
  annotation_document: {
    schema_version: 'annotation-document.v1'
    segments: Array<{ start_ms: number; end_ms: number; label: string }>
    note: string
  }
  submitted_by: string
  submitted_at: string
  capture_group_id: string
  primary_clip_asset_id: string
  primary_camera_number: number
  confirmed_view_set: ViewSet
  input_version: string
  review: {
    annotation_review_id: string
    project_id: string
    annotation_revision_id: string
    decision: 'APPROVED'
    review_note: null
    reviewed_by: string
    reviewed_at: string
    capture_group_id: string
    revision: number
    primary_clip_asset_id: string
    primary_camera_number: number
    confirmed_view_set: ViewSet
    input_version: string
    qualified_views: Array<Record<string, unknown>>
    rejected_missing_views: Array<Record<string, unknown>>
    candidate_evidence: Array<Record<string, unknown>>
  } | null
  capture_group_cleaning: {
    capture_group_cleaning_id: string
    project_id: string
    capture_group_id: string
    annotation_revision_id: string
    annotation_review_id: string
    annotation_review_decision: 'APPROVED'
    group_quality_result: 'QUALIFIED' | 'QUALIFIED_WITH_EXCLUSIONS'
    group_quality_revision: number
    primary_clip_asset_id: string
    primary_camera_number: number
    primary_interval: Array<Record<string, unknown>>
    view_evidence: Array<Record<string, unknown>>
    participating_views: Array<Record<string, unknown>>
    excluded_views: Array<Record<string, unknown>>
    mapping_provenance: Array<Record<string, unknown>>
    candidate_provenance: Array<Record<string, unknown>>
    rule_set_key: string
    rule_set_version: string
    rule_set_digest: string
    outcome: 'PASS'
    reason_codes: string[]
    executed_checks: string[]
    input_fingerprint: string
    evaluated_by: string
    authorization_basis: string
    evaluated_at: string
  } | null
}

type MockData = {
  group: CaptureGroup | null
  groups: CaptureGroup[]
  qualities: Map<string, QualityReview>
  tasks: AnnotationTask[]
  candidateStatus: 'RUNNING' | 'SUCCEEDED'
}

const createdAt = '2026-08-20T01:00:02Z'
const capturedAt = '2026-08-20T01:00:00Z'
const decidedAt = '2026-08-20T01:02:00Z'
const schemaId = '80000000-0000-4000-8000-000000000001'

export async function mockCaptureGroupApi(page: Page, scenario: CaptureGroupScenario) {
  const data = buildScenario(scenario)
  await page.route('**/api/v1/**', (route) => respondToApi(route, data))
}

async function respondToApi(route: Route, data: MockData) {
  const request = route.request()
  const url = new URL(request.url())
  const { pathname } = url

  if (pathname === '/api/v1/auth/session') {
    return json(route, { username: 'synthetic-reviewer', role: 'ADMIN' })
  }
  if (pathname === `/api/v1/projects/${projectId}`) {
    return json(route, {
      project_id: projectId,
      display_name: '合成数据项目',
      status: 'ACTIVE',
      current_role: 'ADMIN',
      created_at: createdAt,
    })
  }
  if (pathname.endsWith('/capture-groups')) {
    return json(route, {
      items: data.groups.map((group) => ({
        capture_group_id: group.capture_group_id,
        capture_group_key: group.capture_group_key,
        device_time_utc: group.device_time_utc,
        receipt_state: group.receipt_state,
        expected_camera_count: group.expected_camera_count,
        received_camera_count: group.received_camera_count,
        missing_camera_numbers: group.missing_camera_numbers,
        created_at: group.created_at,
      })),
      next_cursor: null,
    })
  }
  if (pathname.endsWith('/capture-group-quality-reviews')) {
    const requestedIds = url.searchParams.getAll('capture_group_id')
    return json(route, {
      items: requestedIds.flatMap((id) => {
        const quality = data.qualities.get(id)
        return quality ? [quality] : []
      }),
    })
  }
  if (pathname.endsWith('/annotation-tasks')) {
    return json(route, taskList(data.tasks))
  }
  if (pathname.endsWith('/annotation-label-schema')) {
    return json(route, labelSchema())
  }
  if (data.group && pathname === groupPath(data.group)) return json(route, data.group)
  if (data.group && pathname === `${groupPath(data.group)}/quality-review`) {
    return json(route, data.qualities.get(data.group.capture_group_id))
  }
  if (data.group && pathname === `${groupPath(data.group)}/quality-review/history`) {
    return json(route, { items: [data.qualities.get(data.group.capture_group_id)] })
  }

  const qualityMatch = pathname.match(/\/clip-assets\/([^/]+)\/quality-review$/)
  if (qualityMatch) return json(route, viewQuality(qualityMatch[1]!))

  const mediaReportMatch = pathname.match(/\/clip-assets\/([^/]+)\/media-report$/)
  if (mediaReportMatch) return json(route, mediaReport(mediaReportMatch[1]!))

  const clipAssetMatch = pathname.match(/\/clip-assets\/([^/]+)$/)
  if (clipAssetMatch) return json(route, clipAsset(clipAssetMatch[1]!, data.group))

  const candidateMatch = pathname.match(/\/videos\/([^/]+)\/hand-pose-candidates$/)
  if (candidateMatch) return json(route, handPoseResponse(candidateMatch[1]!, data))

  return route.fulfill({
    status: 500,
    contentType: 'application/json',
    body: JSON.stringify({ error_code: `UNMOCKED_API_${request.method()}` }),
  })
}

function buildScenario(scenario: CaptureGroupScenario): MockData {
  if (scenario === 'list') {
    const active = createGroup(listGroupId, [1, 2, 3], [1, 2, 3], '事件-2026-001')
    const completed = createGroup(
      '60000000-0000-4000-8000-000000000005',
      [1, 2, 3],
      [1, 2],
      '事件-2026-002',
    )
    const activeQuality = createQuality(active)
    const completedQuality = createQuality(completed, 'QUALIFIED_WITH_EXCLUSIONS')
    return {
      group: null,
      groups: [active, completed],
      qualities: new Map([
        [active.capture_group_id, activeQuality],
        [completed.capture_group_id, completedQuality],
      ]),
      tasks: [createTask(active), createTask(completed, 'COMPLETED')],
      candidateStatus: 'RUNNING',
    }
  }

  const group = createGroup(
    scenario === 'n1-active' || scenario === 'unassigned'
      ? oneCameraGroupId
      : scenario === 'n2-active'
        ? multiCameraGroupId
        : completedGroupId,
    scenario === 'n1-active' || scenario === 'unassigned' ? [1] : [1, 2],
    scenario === 'n1-active' || scenario === 'unassigned' ? [1] : [1, 2],
    scenario === 'n1-active' || scenario === 'unassigned'
      ? '事件-2026-N1'
      : scenario === 'n2-active'
        ? '事件-2026-N2'
        : '事件-2026-DONE',
  )
  const quality = createQuality(group)
  return {
    group,
    groups: [],
    qualities: new Map([[group.capture_group_id, quality]]),
    tasks:
      scenario === 'unassigned'
        ? []
        : [createTask(group, scenario === 'completed' ? 'COMPLETED' : 'ASSIGNED')],
    candidateStatus: scenario === 'completed' ? 'SUCCEEDED' : 'RUNNING',
  }
}

function createGroup(
  id: string,
  expectedCameraNumbers: number[],
  receivedCameraNumbers: number[],
  key: string,
): CaptureGroup {
  const missingCameraNumbers = expectedCameraNumbers.filter(
    (cameraNumber) => !receivedCameraNumbers.includes(cameraNumber),
  )
  const members = expectedCameraNumbers.map((cameraNumber) => ({
    camera_channel_id: `40000000-0000-4000-8000-${String(cameraNumber).padStart(12, '0')}`,
    camera_index: cameraNumber - 1,
    camera_number: cameraNumber,
    camera_role: `camera-${cameraNumber}`,
    mount_position: `position-${cameraNumber}`,
    enabled: true as const,
    enabled_for_hand_detection: true as const,
  }))
  const streams = receivedCameraNumbers.map(
    (cameraNumber): CameraStream => ({
      camera_stream_id: `41000000-0000-4000-8000-${String(cameraNumber).padStart(12, '0')}`,
      clip_asset_id: `50000000-0000-4000-8000-${String(cameraNumber).padStart(12, '0')}`,
      client_file_id: `camera-${cameraNumber}.mp4`,
      camera_channel_id: `40000000-0000-4000-8000-${String(cameraNumber).padStart(12, '0')}`,
      camera_index: cameraNumber - 1,
      camera_number: cameraNumber,
      camera_role: `camera-${cameraNumber}`,
      mount_position: `position-${cameraNumber}`,
      record_start_utc: capturedAt,
      time_offset_ms: cameraNumber * 10,
      sync_status: cameraNumber === 1 ? 'WARNING' : 'PASS',
      stream_status: 'INGESTED',
      quality_status: 'PASS',
      enabled_for_hand_detection: true,
    }),
  )
  const receiptState = missingCameraNumbers.length ? 'INCOMPLETE' : 'COMPLETE'
  return {
    capture_group_id: id,
    project_id: projectId,
    batch_id: '42000000-0000-4000-8000-000000000001',
    device_id: 'synthetic-device',
    expected_camera_count: expectedCameraNumbers.length,
    received_camera_count: receivedCameraNumbers.length,
    expected_camera_numbers: expectedCameraNumbers,
    received_camera_numbers: receivedCameraNumbers,
    missing_camera_numbers: missingCameraNumbers,
    capture_group_key: key,
    capture_group_key_source: 'UPSTREAM_MANIFEST_KEY',
    device_time_utc: capturedAt,
    device_timezone: 'Asia/Shanghai',
    server_receive_time: createdAt,
    assembly_status: receiptState,
    quality_status: 'PASS',
    receipt_state: receiptState,
    receipt_finalized_at: '2026-08-20T01:01:00Z',
    camera_set_snapshot: {
      camera_set_snapshot_id: '43000000-0000-4000-8000-000000000001',
      source_camera_set_id: '44000000-0000-4000-8000-000000000001',
      camera_set_key: 'synthetic-set',
      configuration_version: 1,
      layout_version: 'v1',
      expected_camera_count: expectedCameraNumbers.length,
      members,
    },
    streams,
    created_at: createdAt,
  }
}

function createViewSet(group: CaptureGroup): ViewSet {
  return {
    schema_version: 'capture-group-view-set.v1',
    capture_group_id: group.capture_group_id,
    capture_group_version: 1,
    camera_set_snapshot_id: group.camera_set_snapshot.camera_set_snapshot_id,
    receipt_state: group.receipt_state,
    views: group.expected_camera_numbers.map((cameraNumber) => {
      const stream = group.streams.find((candidate) => candidate.camera_number === cameraNumber)
      return {
        camera_number: cameraNumber,
        camera_stream_id: stream?.camera_stream_id ?? null,
        clip_asset_id: stream?.clip_asset_id ?? null,
        camera_channel_id: stream?.camera_channel_id ?? null,
        present: Boolean(stream),
        media_status: stream ? 'INGESTED' : 'MISSING',
        technical_quality_status: stream?.quality_status ?? null,
        stream_version: 1,
        clip_asset_version: stream ? 1 : 0,
        asset_available: Boolean(stream),
        quality_review_decision: stream ? 'QUALIFIED' : 'UNREVIEWED',
        quality_review_version: stream ? 1 : 0,
      }
    }),
  }
}

function createQuality(
  group: CaptureGroup,
  result: QualityReview['result'] = 'QUALIFIED',
): QualityReview {
  return {
    capture_group_quality_review_id: '45000000-0000-4000-8000-000000000001',
    project_id: projectId,
    capture_group_id: group.capture_group_id,
    result,
    reason: 'synthetic human decision',
    reviewer_actor_id: 'synthetic-reviewer',
    revision: 1,
    version: 1,
    decided_at: decidedAt,
    confirmed_view_set: createViewSet(group),
    input_version: 'capture-group-view-set.v1:synthetic',
    confirmed_view_set_sha256: null,
  }
}

function createTask(
  group: CaptureGroup,
  status: AnnotationTask['status'] = 'ASSIGNED',
): AnnotationTask {
  const viewSet = createViewSet(group)
  const primaryClipAssetId = group.streams[0]?.clip_asset_id ?? ''
  const revisionId = '47000000-0000-4000-8000-000000000001'
  const isCompleted = status === 'COMPLETED'
  const revision = isCompleted
    ? createRevision(group, viewSet, primaryClipAssetId, revisionId)
    : null
  return {
    annotation_task_id: `70000000-0000-4000-8000-${group.capture_group_id.slice(-12)}`,
    project_id: projectId,
    capture_group_id: group.capture_group_id,
    primary_clip_asset_id: primaryClipAssetId,
    primary_camera_number: 1,
    capture_group_quality_review_id: '45000000-0000-4000-8000-000000000001',
    group_quality_revision: 1,
    confirmed_view_set: viewSet,
    group_input_version: 'capture-group-view-set.v1:synthetic',
    label_schema_id: schemaId,
    label_schema_digest: 'a'.repeat(64),
    assignee_actor_id: 'synthetic-annotator',
    created_by: 'system',
    status,
    can_submit: !isCompleted,
    can_select_primary: !isCompleted,
    can_review: false,
    can_recheck_capture_group_cleaning: false,
    annotation_revision: revision,
    created_at: createdAt,
    updated_at: '2026-08-20T01:04:00Z',
    version: isCompleted ? 2 : 0,
  }
}

function createRevision(
  group: CaptureGroup,
  viewSet: ViewSet,
  primaryClipAssetId: string,
  revisionId: string,
): AnnotationRevision {
  const reviewId = '48000000-0000-4000-8000-000000000001'
  return {
    annotation_revision_id: revisionId,
    label_schema_id: schemaId,
    label_schema_digest: 'a'.repeat(64),
    revision_number: 1,
    annotation_document: {
      schema_version: 'annotation-document.v1',
      segments: [{ start_ms: 100, end_ms: 900, label: 'acquire_item' }],
      note: '本次正式动作 Revision 备注',
    },
    submitted_by: 'synthetic-annotator',
    submitted_at: '2026-08-20T01:03:00Z',
    capture_group_id: group.capture_group_id,
    primary_clip_asset_id: primaryClipAssetId,
    primary_camera_number: 1,
    confirmed_view_set: viewSet,
    input_version: 'capture-group-view-set.v1:synthetic',
    review: {
      annotation_review_id: reviewId,
      project_id: projectId,
      annotation_revision_id: revisionId,
      decision: 'APPROVED',
      review_note: null,
      reviewed_by: 'synthetic-reviewer',
      reviewed_at: '2026-08-20T01:04:00Z',
      capture_group_id: group.capture_group_id,
      revision: 1,
      primary_clip_asset_id: primaryClipAssetId,
      primary_camera_number: 1,
      confirmed_view_set: viewSet,
      input_version: 'capture-group-view-set.v1:synthetic',
      qualified_views: viewSet.views.filter((view) => view.present),
      rejected_missing_views: viewSet.views.filter((view) => !view.present),
      candidate_evidence: [
        {
          camera_number: 1,
          clip_asset_id: primaryClipAssetId,
          algorithm_key: 'MEDIAPIPE_HAND_LANDMARKER',
          algorithm_version: 'v1',
          candidate_count: 2,
        },
      ],
    },
    capture_group_cleaning: {
      capture_group_cleaning_id: '49000000-0000-4000-8000-000000000001',
      project_id: projectId,
      capture_group_id: group.capture_group_id,
      annotation_revision_id: revisionId,
      annotation_review_id: reviewId,
      annotation_review_decision: 'APPROVED',
      group_quality_result: group.missing_camera_numbers.length
        ? 'QUALIFIED_WITH_EXCLUSIONS'
        : 'QUALIFIED',
      group_quality_revision: 1,
      primary_clip_asset_id: primaryClipAssetId,
      primary_camera_number: 1,
      primary_interval: [{ start_ms: 100, end_ms: 900, label: 'acquire_item' }],
      view_evidence: viewSet.views.filter((view) => view.present),
      participating_views: viewSet.views.filter((view) => view.present),
      excluded_views: viewSet.views.filter((view) => !view.present),
      mapping_provenance: [],
      candidate_provenance: [],
      rule_set_key: 'capture-group-event-eligibility',
      rule_set_version: 'v1',
      rule_set_digest: 'b'.repeat(64),
      outcome: 'PASS',
      reason_codes: [],
      executed_checks: ['REVIEW_APPROVED', 'GROUP_QUALITY_ADMITTING', 'RAW_MEDIA_RETAINED'],
      input_fingerprint: 'c'.repeat(64),
      evaluated_by: 'system',
      authorization_basis: 'capture-group-cleaning-v1',
      evaluated_at: '2026-08-20T01:05:00Z',
    },
  }
}

function groupPath(group: CaptureGroup) {
  return `/api/v1/projects/${projectId}/capture-groups/${group.capture_group_id}`
}

function taskList(tasks: AnnotationTask[]) {
  return {
    items: tasks,
    progress: {
      scope: 'PROJECT',
      total: tasks.length,
      awaiting_submission: tasks.filter((task) => task.status === 'ASSIGNED').length,
      rework_required: 0,
      awaiting_review: 0,
      completed: tasks.filter((task) => task.status === 'COMPLETED').length,
    },
  }
}

function labelSchema() {
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

function viewQuality(clipAssetId: string) {
  return {
    quality_review_id: `46000000-0000-4000-8000-${clipAssetId.slice(-12)}`,
    project_id: projectId,
    clip_asset_id: clipAssetId,
    decision: 'QUALIFIED',
    reason_code: 'MEETS_REQUIREMENTS',
    reviewed_by: 'synthetic-reviewer',
    reviewed_at: decidedAt,
    version: 1,
  }
}

function clipAsset(clipAssetId: string, group: CaptureGroup | null) {
  const stream = group?.streams.find((candidate) => candidate.clip_asset_id === clipAssetId)
  return {
    clip_asset_id: clipAssetId,
    batch_id: group?.batch_id ?? '42000000-0000-4000-8000-000000000001',
    source_upload_file_id: 'source-file-synthetic',
    client_file_id: stream?.client_file_id ?? 'synthetic.mp4',
    content_type: 'video/mp4',
    size_bytes: 1024,
    sha256: 'c'.repeat(64),
    status: 'INGESTED',
    session_binding: null,
    created_at: createdAt,
    updated_at: createdAt,
  }
}

function mediaReport(clipAssetId: string) {
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
    created_at: createdAt,
  }
}

function handPoseResponse(clipAssetId: string, data: MockData) {
  const completed = data.candidateStatus === 'SUCCEEDED'
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
      status: data.candidateStatus,
      hand_result: completed ? 'HAND_DETECTED' : 'UNKNOWN',
      run_summary: completed
        ? {
            analysis_complete: true,
            hand_result: 'HAND_DETECTED',
            total_video_frames: 10,
            inference_frame_count: 10,
            frames_with_hand: 10,
            frames_without_hand: 0,
            left_hand_detection_count: 5,
            right_hand_detection_count: 5,
            invalid_landmark_count: 0,
            processing_duration_ms: 10,
            started_at: createdAt,
            completed_at: createdAt,
            failure_reason: null,
            retryable: false,
          }
        : null,
      requested_by: 'system',
      created_at: createdAt,
      updated_at: createdAt,
    },
    candidates: [],
  }
}

function json(route: Route, body: unknown) {
  return route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(body),
  })
}
