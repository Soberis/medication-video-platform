import { csrfHeaders } from '@/api/http'

export type CaptureSessionAssemblyStatus =
  | 'OPEN'
  | 'ASSEMBLING'
  | 'COMPLETE'
  | 'INCOMPLETE'
  | 'CLOSED'

export type CaptureQualityStatus = 'PENDING' | 'PASS' | 'WARNING' | 'REJECT'
export type CameraStreamStatus = 'BOUND' | 'INGESTED' | 'INGEST_FAILED' | 'UNAVAILABLE'
export type DeclaredSyncStatus = 'UNKNOWN' | 'PASS' | 'WARNING' | 'REJECT'
export type ClipAssetStatus = 'UPLOADED' | 'INGESTING' | 'INGESTED' | 'INGEST_FAILED'

export interface CaptureSessionListItem {
  capture_session_id: string
  batch_id: string
  device_id: string
  camera_set_id: string
  camera_set_key: string
  camera_set_configuration_version: number
  camera_layout_version: string
  expected_camera_count: number
  bound_stream_count: number
  received_camera_count: number
  ingest_failed_stream_count: number
  sync_group_id: string | null
  device_time_utc: string | null
  server_receive_time: string
  assembly_status: CaptureSessionAssemblyStatus
  quality_status: CaptureQualityStatus
  created_at: string
}

export interface CameraSetSnapshotMember {
  camera_channel_id: string
  camera_index: number
  camera_role: string
  mount_position: string
  enabled: boolean
}

export interface CameraSetSnapshot {
  camera_set_snapshot_id: string
  source_camera_set_id: string
  camera_set_key: string
  configuration_version: number
  layout_version: string
  expected_camera_count: number
  members: CameraSetSnapshotMember[]
}

export interface CameraStream {
  camera_stream_id: string
  clip_asset_id: string
  client_file_id: string
  camera_channel_id: string
  camera_index: number
  camera_role: string
  mount_position: string
  record_start_utc: string | null
  time_offset_ms: number
  sync_status: DeclaredSyncStatus
  stream_status: CameraStreamStatus
  quality_status: CaptureQualityStatus
}

export interface CaptureSessionDetail {
  capture_session_id: string
  project_id: string
  batch_id: string
  device_id: string
  metadata_upload_file_id: string | null
  metadata_schema_version: string
  metadata_sha256: string | null
  expected_camera_count: number
  received_camera_count: number
  sync_group_id: string | null
  device_time_utc: string | null
  device_timezone: string | null
  server_receive_time: string
  assembly_status: CaptureSessionAssemblyStatus
  quality_status: CaptureQualityStatus
  camera_set_snapshot: CameraSetSnapshot
  streams: CameraStream[]
  created_at: string
}

export type DatasetQualityReviewDecision = 'UNREVIEWED' | 'QUALIFIED' | 'REJECTED' | 'NEEDS_REVIEW'
export type DatasetAnnotationReviewDecision = 'UNREVIEWED' | 'APPROVED' | 'REJECTED'
export type DatasetPostCleaningDecision = 'MISSING' | 'PASS' | 'WARNING' | 'REJECT'
export type DatasetAssetStatus = ClipAssetStatus | 'MISSING'

export interface DatasetAdmissionPreviewStream {
  camera_stream_id: string
  clip_asset_id: string
  camera_index: number
  eligible: boolean
  stream_status: CameraStreamStatus
  stream_quality_status: CaptureQualityStatus
  asset_status: DatasetAssetStatus
  asset_deleted: boolean
  quality_review_decision: DatasetQualityReviewDecision
  annotation_revision_id?: string | null
  annotation_review_decision: DatasetAnnotationReviewDecision
  post_cleaning_decision: DatasetPostCleaningDecision
  annotation_document_sha256?: string | null
  label_schema_digest?: string | null
  blocking_reasons: string[]
}

export interface DatasetAdmissionPreview {
  capture_session_id: string
  eligible: boolean
  assembly_status: CaptureSessionAssemblyStatus
  quality_status: CaptureQualityStatus
  expected_camera_count: number
  received_camera_count: number
  blocking_reasons: string[]
  streams: DatasetAdmissionPreviewStream[]
}

export type DatasetVersionStatus = 'DRAFT' | 'BUILDING' | 'VALIDATING' | 'FROZEN'
export type DatasetExportProfileKey = 'p0-dataset-export-v1' | 'p0-dataset-export-v2'

export interface DatasetVersionSummary {
  dataset_version_id: string
  project_id: string
  display_name: string
  status: DatasetVersionStatus
  created_by: string
  created_at: string
}

export interface DatasetVersionDraft extends DatasetVersionSummary {
  status: 'DRAFT'
}

export interface DatasetVersionMemberStream {
  camera_stream_id: string
  clip_asset_id: string
  camera_index: number
  asset_sha256: string
  quality_review_decision: 'QUALIFIED'
  quality_review_version: number
  annotation_revision_id: string
  annotation_document_sha256: string
  label_schema_digest: string
  cleaning_result_id: string
  cleaning_run_id: string
  cleaning_rule_set_digest: string
  post_cleaning_outcome: 'PASS'
}

export interface DatasetVersionMemberSession {
  capture_session_id: string
  candidate_index: number
  expected_camera_count: number
  received_camera_count: number
  assembly_status: CaptureSessionAssemblyStatus
  quality_status: CaptureQualityStatus
  streams: DatasetVersionMemberStream[]
}

export type DatasetSplit = 'PILOT' | 'HOLDOUT'

export interface DatasetSplitAssignment {
  capture_session_id: string
  candidate_index: number
  leakage_group_type: 'CAPTURE_SESSION'
  leakage_group_id: string
  split: DatasetSplit
  assignment_rank: number
}

export interface DatasetSplitPlan {
  dataset_split_plan_id: string
  dataset_version_id: string
  policy_version: 'CAPTURE_SESSION_HASH_RANK_V1'
  split_seed: number
  pilot_count: number
  holdout_count: number
  created_by: string
  created_at: string
  assignments: DatasetSplitAssignment[]
}

export interface DatasetBuildSpecification {
  dataset_build_specification_id: string
  dataset_version_id: string
  selection_rule_version: 'EXPLICIT_CAPTURE_SESSION_SNAPSHOT_V1'
  label_schema_digest: string
  cleaning_rule_set_digest: string
  dataset_split_plan_id: string
  export_profile_key: DatasetExportProfileKey
  export_profile_digest: string
  created_by: string
  created_at: string
}

export interface DatasetBuildSnapshot {
  dataset_build_snapshot_id: string
  dataset_version_id: string
  dataset_build_specification_id: string
  dataset_split_plan_id: string
  snapshot_schema_version: 'DATASET_RELATIONAL_SNAPSHOT_V1'
  input_variant_policy: 'RAW_CLIP_ASSET_V1'
  member_count: number
  stream_count: number
  snapshot_digest: string
  created_by: string
  created_at: string
}

export type DatasetVersionValidationReason =
  | 'CURRENT_ADMISSION_NOT_ELIGIBLE'
  | 'SNAPSHOT_EVIDENCE_CHANGED'
  | 'SNAPSHOT_REFERENCE_MISMATCH'
  | 'SNAPSHOT_STRUCTURE_INVALID'
  | 'SNAPSHOT_DIGEST_MISMATCH'
  | 'SPLIT_LEAKAGE_DETECTED'
  | 'BUILD_CONTRACT_MISMATCH'

export type DatasetVersionValidationCheck =
  | 'CURRENT_ADMISSION'
  | 'REFERENCE_INTEGRITY'
  | 'SNAPSHOT_DIGEST'
  | 'SPLIT_ISOLATION'
  | 'BUILD_CONTRACT'

export interface DatasetVersionValidation {
  dataset_version_validation_id: string
  dataset_version_id: string
  dataset_build_snapshot_id: string
  validation_profile_version: 'DATASET_FREEZE_VALIDATION_V1'
  outcome: 'PASS' | 'FAIL'
  reason_codes: DatasetVersionValidationReason[]
  executed_checks: DatasetVersionValidationCheck[]
  validated_snapshot_digest: string
  validated_by: string
  validated_at: string
}

export interface DatasetVersionFreezeApproval {
  approval_request_id: string
  validation_id: string
  status: 'PENDING' | 'APPROVED'
  requested_by: string
  requested_at: string
  approval_decision_id: string | null
  approved_by: string | null
  approved_at: string | null
}

export interface DatasetVersionFreeze {
  dataset_version_id: string
  project_id: string
  status: 'FROZEN'
  validation_id: string
  approval_request_id: string
  approval_decision_id: string
  frozen_by: string
  frozen_at: string
}

export interface DatasetExportApproval {
  approval_request_id: string
  validation_id: string
  status: 'PENDING' | 'APPROVED'
  requested_by: string
  requested_at: string
  approval_decision_id: string | null
  decided_by: string | null
  decided_at: string | null
}

export interface DatasetExportAttempt {
  dataset_export_attempt_id: string
  attempt: number
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'LOST'
  started_at: string | null
  completed_at: string | null
}

export interface DatasetExportArtifact {
  export_artifact_id: string
  file_name: 'dataset-export.zip'
  media_type: 'application/zip'
  size_bytes: number
  sha256: string
  created_at: string
}

export interface DatasetExport {
  dataset_export_id: string
  project_id: string
  dataset_version_id: string
  status: 'REQUESTED' | 'QUEUED' | 'RUNNING' | 'RETRY_WAIT' | 'AVAILABLE' | 'FAILED'
  purpose: 'MODEL_TRAINING'
  expires_at: string
  dataset_build_specification_id: string
  dataset_build_snapshot_id: string
  snapshot_digest: string
  dataset_version_validation_id: string
  export_profile_key: DatasetExportProfileKey
  export_profile_digest: string
  approval: DatasetExportApproval
  requested_by: string
  requested_at: string
  approved_by: string | null
  approved_at: string | null
  started_at: string | null
  completed_at: string | null
  attempt: DatasetExportAttempt | null
  artifact: DatasetExportArtifact | null
}

export interface DatasetExportDownloadAuthorization {
  dataset_export_id: string
  export_artifact_id: string
  download_url: string
  expires_at: string
  file_name: 'dataset-export.zip'
  media_type: 'application/zip'
  size_bytes: number
  sha256: string
}

export type DatasetExportApprovalBlocker =
  | 'APPROVER_ADMIN_REQUIRED'
  | 'SELF_APPROVAL_FORBIDDEN'
  | 'EXPORT_AUTHORIZATION_EXPIRED'
  | 'DATASET_VERSION_NOT_FROZEN'
  | 'EXPORT_REQUEST_INVALID'
  | 'APPROVAL_REQUEST_INVALID'
  | 'EXPORT_PROFILE_NOT_EXECUTABLE'
  | 'FROZEN_EVIDENCE_INVALID'
  | 'SNAPSHOT_VALIDATION_MISMATCH'
  | 'CURRENT_ADMISSION_NOT_ELIGIBLE'
  | 'SNAPSHOT_EVIDENCE_CHANGED'
  | 'SNAPSHOT_REFERENCE_MISMATCH'
  | 'SNAPSHOT_STRUCTURE_INVALID'
  | 'SNAPSHOT_DIGEST_MISMATCH'
  | 'SPLIT_LEAKAGE_DETECTED'
  | 'BUILD_CONTRACT_MISMATCH'

export interface DatasetExportApprovalReadiness {
  dataset_export_id: string
  dataset_version_id: string
  approval_request_id: string
  ready_for_approval: boolean
  blocking_reasons: DatasetExportApprovalBlocker[]
  checked_at: string
}

export interface DatasetVersionDetail extends DatasetVersionSummary {
  members: DatasetVersionMemberSession[]
  split_plan: DatasetSplitPlan | null
  can_create_split_plan: boolean
  build_specification: DatasetBuildSpecification | null
  can_create_build_specification: boolean
  build_snapshot: DatasetBuildSnapshot | null
  can_create_build_snapshot: boolean
  validation: DatasetVersionValidation | null
  can_validate: boolean
  freeze_approval: DatasetVersionFreezeApproval | null
  can_freeze: boolean
  export_request: DatasetExport | null
  can_request_export: boolean
}

export interface DatasetVersionCurrentAdmissionEvidenceChange {
  scope: 'SESSION' | 'STREAM'
  camera_stream_id: string | null
  change_codes: string[]
}

export interface DatasetVersionCurrentAdmissionMember {
  capture_session_id: string
  candidate_index: number
  current_admission: DatasetAdmissionPreview
  evidence_changes: DatasetVersionCurrentAdmissionEvidenceChange[]
}

export type DatasetVersionBuildBlocker =
  | 'SELECTION_RULE_NOT_FIXED'
  | 'LABEL_SCHEMA_NOT_FIXED'
  | 'LABEL_SCHEMA_NOT_UNIFORM'
  | 'CLEANING_RULE_SET_NOT_FIXED'
  | 'CLEANING_RULE_SET_NOT_UNIFORM'
  | 'SPLIT_PLAN_NOT_FIXED'
  | 'EXPORT_PROFILE_NOT_FIXED'
  | 'CURRENT_ADMISSION_NOT_ELIGIBLE'
  | 'SNAPSHOT_EVIDENCE_CHANGED'

export interface DatasetVersionBuildReadiness {
  ready_for_build: boolean
  blocking_reasons: DatasetVersionBuildBlocker[]
  immutable_evidence_still_current: boolean
  uniform_label_schema_digest: string | null
  uniform_cleaning_rule_set_digest: string | null
  dataset_split_plan_id: string | null
  build_specification: DatasetBuildSpecification | null
}

export interface DatasetVersionCurrentAdmission {
  dataset_version_id: string
  all_members_currently_admission_eligible: boolean
  members: DatasetVersionCurrentAdmissionMember[]
  build_readiness: DatasetVersionBuildReadiness
  can_start_build: boolean
}

export interface DatasetVersionBuildStart {
  dataset_version_id: string
  status: 'BUILDING'
  started_by: string
  started_at: string
}

export interface ClipAssetSessionBinding {
  capture_session_id: string
  camera_stream_id: string
  camera_channel_id: string
  camera_index: number
  camera_role: string
  mount_position: string
  record_start_utc: string | null
  time_offset_ms: number
  declared_sync_status: DeclaredSyncStatus
  stream_status: CameraStreamStatus
  quality_status: CaptureQualityStatus
}

export interface ClipAssetRead {
  clip_asset_id: string
  batch_id: string
  source_upload_file_id: string
  client_file_id: string
  content_type: string
  size_bytes: number
  sha256: string
  status: ClipAssetStatus
  folder_id: string | null
  session_binding: ClipAssetSessionBinding | null
  created_at: string
  updated_at: string
}

export interface ClipAssetActivityItem {
  event_id: string
  occurred_at_utc: string
  actor_type: 'USER' | 'SERVICE' | 'SYSTEM'
  actor_id: string
  action: string
  object_type: string
  object_id: string
  object_version: string | null
  outcome: 'SUCCEEDED' | 'DENIED' | 'FAILED'
  reason_code: string
  request_id: string
}

export interface CaptureEventLogRead {
  capture_event_log_id: string
  batch_id: string
  source_upload_file_id: string
  client_file_id: string
  device_id: string
  content_type: string
  size_bytes: number
  sha256: string
  indexed_at: string
}

export interface ClipAssetPlaybackGrant {
  grant_id: string
  purpose: 'DATA_REVIEW'
  media_url: string
  expires_at: string
  content_type: string
}

export interface MediaProbeJob {
  media_job_id: string
  project_id: string
  clip_asset_id: string
  job_type: 'MEDIA_PROBE'
  status: 'REQUESTED' | 'QUEUED' | 'RUNNING' | 'RETRY_WAIT' | 'SUCCEEDED' | 'FAILED'
}

export interface MediaJobDeliveryRecovery {
  recovery_id: string
  media_job_id: string
  source_message_id: string
  source_attempt: number
  new_attempt: number
  resulting_job_status: 'QUEUED'
  created_at: string
}

export interface MediaReport {
  media_report_id: string
  media_job_id: string
  project_id: string
  clip_asset_id: string
  container_format: string
  duration_microseconds: number
  video_codec: string
  video_profile: string | null
  width: number
  height: number
  pixel_format: string | null
  primary_video_bitrate_bps: number | null
  average_frame_rate_numerator: number | null
  average_frame_rate_denominator: number | null
  frame_count: number | null
  variable_frame_rate: boolean
  audio_stream_count: number
  decode_verified: true
  input_digest_verified: true
  warning_codes: string[]
  created_at: string
}

export interface CatalogPage<T> {
  items: T[]
  next_cursor: string | null
}

export interface CaptureSessionFilters {
  deviceId?: string
  assemblyStatus?: CaptureSessionAssemblyStatus
  qualityStatus?: CaptureQualityStatus
  recordedFrom?: string
  recordedBefore?: string
}

export interface ClipAssetFilters {
  captureSessionId?: string
  batchId?: string
  deviceId?: string
  cameraRole?: string
  status?: ClipAssetStatus
  folderId?: string
  unclassified?: boolean
  recordedFrom?: string
  recordedBefore?: string
}

export interface CaptureEventLogFilters {
  batchId?: string
  deviceId?: string
  indexedFrom?: string
  indexedBefore?: string
}

interface ApiErrorBody {
  error_code?: unknown
  error_message?: unknown
  retryable?: unknown
  request_id?: unknown
}

const errorMessages: Record<string, string> = {
  INVALID_CURSOR: '分页位置已经失效，请重新加载第一页。',
  INVALID_TIME_RANGE: '时间范围无效，开始时间必须早于结束时间。',
  INVALID_FOLDER_FILTER: '文件夹和未分类筛选不能同时使用。',
  CAPTURE_SESSION_NOT_FOUND: '未找到该配药操作记录，或它不属于当前项目。',
  CLIP_ASSET_NOT_FOUND: '未找到该视频资产，或它不属于当前项目。',
  CAPTURE_EVENT_LOG_NOT_FOUND: '未找到该采集事件日志索引，或它不属于当前项目。',
  PROJECT_NOT_FOUND: '未找到该项目，或当前用户尚未加入该项目。',
  PROJECT_ACCESS_DENIED: '当前用户无权访问该项目中的数据。',
  PLAYBACK_ACCESS_DENIED: '当前账号无权播放该项目的视频。',
  CLIP_ASSET_NOT_PROBEABLE: '当前视频不处于可检测状态，请刷新后重试。',
  MEDIA_JOB_NOT_FOUND: '当前视频没有可恢复的媒体检测任务，请先发起检测或刷新状态。',
  MEDIA_JOB_DELIVERY_RECOVERY_NOT_CONFIRMED:
    '原检测任务尚未确认发布，不能执行恢复。请刷新状态后重试。',
  MEDIA_JOB_DELIVERY_RECOVERY_STATE_CONFLICT:
    '当前检测任务已不再处于可恢复的排队状态，请刷新后确认。',
  MEDIA_JOB_DELIVERY_RECOVERY_SOURCE_REUSED: '当前检测任务已经恢复过，不能再次使用原消息。',
  MEDIA_JOB_DELIVERY_RECOVERY_LIMIT_REACHED: '媒体检测已达到最大尝试次数，不能继续恢复。',
  PLAYBACK_UNSUPPORTED_MEDIA: '当前仅支持播放 MP4 格式的视频。',
  CLIP_ASSET_DELETED: '该视频已删除，不能再播放。',
  MEDIA_REPORT_NOT_FOUND: '媒体检测报告尚未就绪，请在检测完成后刷新状态。',
  OBJECT_STORAGE_UNAVAILABLE: '对象存储暂时无法签发播放地址，请稍后重试。',
  DATASET_VERSION_SESSION_NOT_ELIGIBLE: '当前配药操作尚未满足创建数据集草稿的条件，请刷新后重试。',
  DATASET_VERSION_CAPTURE_SESSION_DUPLICATE: '同一配药操作不能重复加入数据集草稿。',
  DATASET_VERSION_IDEMPOTENCY_KEY_REUSED: '本次创建请求与已提交请求冲突，请刷新后重试。',
  DATASET_VERSION_ACCESS_DENIED: '只有项目管理员可以创建数据集草稿。',
  DATASET_VERSION_NOT_FOUND: '未找到该数据集草稿，或它不属于当前项目。',
  DATASET_VERSION_CURRENT_ADMISSION_UNAVAILABLE: '暂时无法核对当前准入事实，请稍后重试。',
  DATASET_SPLIT_PLAN_TOO_FEW_MEMBERS: '至少需要两条候选配药操作才能建立试用组和留出组。',
  DATASET_SPLIT_PLAN_HOLDOUT_COUNT_INVALID: '留出组数量必须小于候选配药操作总数。',
  DATASET_SPLIT_PLAN_ALREADY_EXISTS: '该草稿已经固定数据划分，请刷新页面查看。',
  DATASET_SPLIT_PLAN_IDEMPOTENCY_KEY_REUSED: '本次划分请求与已提交请求冲突，请刷新后重试。',
  DATASET_BUILD_SPECIFICATION_ALREADY_EXISTS: '该草稿已经固定构建配置，请刷新页面查看。',
  DATASET_BUILD_SPECIFICATION_IDEMPOTENCY_KEY_REUSED:
    '本次构建配置请求与已提交请求冲突，请刷新后重试。',
  DATASET_BUILD_SPLIT_PLAN_REQUIRED: '请先固定试用组和留出组划分。',
  DATASET_BUILD_EVIDENCE_MISSING: '草稿缺少可固定的视频证据，请新建数据集草稿。',
  DATASET_BUILD_LABEL_SCHEMA_NOT_UNIFORM: '候选视频的标签规范不一致，请重新选择候选数据。',
  DATASET_BUILD_CLEANING_RULE_SET_NOT_UNIFORM: '候选视频的清洗规则不一致，请重新选择候选数据。',
  DATASET_BUILD_SPECIFICATION_STATUS_INVALID: '只有草稿状态的数据集可以固定构建配置。',
  DATASET_VERSION_BUILD_BLOCKED: '当前准入或固定证据已经变化，请重新检查构建准备。',
  DATASET_VERSION_BUILD_STATUS_INVALID: '该数据集已不再是草稿状态，请刷新页面查看。',
  DATASET_VERSION_BUILD_IDEMPOTENCY_KEY_REUSED: '本次开始构建请求与已提交请求冲突，请刷新后重试。',
  DATASET_BUILD_SNAPSHOT_INCOMPLETE: '固定证据不完整或不一致，暂时无法生成构建快照。',
  DATASET_BUILD_SNAPSHOT_STATUS_INVALID: '当前版本不处于构建阶段，请刷新页面查看。',
  DATASET_BUILD_SNAPSHOT_ALREADY_EXISTS: '该版本已经生成构建快照，请刷新页面查看。',
  DATASET_BUILD_SNAPSHOT_IDEMPOTENCY_KEY_REUSED: '本次快照请求与已提交请求冲突，请刷新后重试。',
  DATASET_VERSION_VALIDATION_INCOMPLETE: '固定快照不完整，无法执行冻结前校验。',
  DATASET_VERSION_VALIDATION_STATUS_INVALID: '当前版本不处于校验阶段，请刷新页面查看。',
  DATASET_VERSION_VALIDATION_ALREADY_EXISTS: '该版本已经完成冻结前校验，请刷新页面查看。',
  DATASET_VERSION_VALIDATION_IDEMPOTENCY_KEY_REUSED: '本次校验请求与已提交请求冲突，请刷新后重试。',
  DATASET_VERSION_FREEZE_STATUS_INVALID: '当前版本不处于可冻结阶段，请刷新页面查看。',
  DATASET_VERSION_FREEZE_VALIDATION_REQUIRED: '请先完成冻结前技术校验。',
  DATASET_VERSION_FREEZE_VALIDATION_FAILED: '冻结前技术校验未通过，当前版本不能冻结。',
  DATASET_VERSION_FREEZE_APPROVAL_MISSING: '冻结审批请求缺失，请刷新页面后重试。',
  DATASET_VERSION_FREEZE_APPROVAL_INVALID: '冻结审批请求已失效，请刷新页面查看。',
  DATASET_VERSION_FREEZE_SELF_APPROVAL_FORBIDDEN:
    '当前账号不满足受限管理员自审条件，请由合格的平台项目管理员处理。',
  DATASET_VERSION_FREEZE_EVIDENCE_CHANGED: '校验后数据证据发生变化，请修正后新建数据集版本。',
  DATASET_VERSION_ALREADY_FROZEN: '该数据集版本已经冻结。',
  DATASET_VERSION_FREEZE_IDEMPOTENCY_KEY_REUSED: '本次冻结请求与已提交请求冲突，请刷新后重试。',
  INVALID_DATASET_VERSION_REQUEST: '数据集草稿请求参数无效，请检查后重试。',
  INVALID_REQUEST: '筛选条件格式不正确，请检查后重试。',
}

export class CatalogApiError extends Error {
  readonly errorCode: string
  readonly retryable: boolean
  readonly requestId: string | null
  readonly status: number

  constructor(
    message: string,
    errorCode = 'CATALOG_REQUEST_FAILED',
    retryable = false,
    requestId: string | null = null,
    status = 0,
  ) {
    super(message)
    this.name = 'CatalogApiError'
    this.errorCode = errorCode
    this.retryable = retryable
    this.requestId = requestId
    this.status = status
  }
}

export function listCaptureSessions(
  projectId: string,
  filters: CaptureSessionFilters,
  cursor: string | null,
  signal?: AbortSignal,
) {
  const query = new URLSearchParams({ limit: '20' })
  add(query, 'device_id', filters.deviceId)
  add(query, 'assembly_status', filters.assemblyStatus)
  add(query, 'quality_status', filters.qualityStatus)
  add(query, 'recorded_from', filters.recordedFrom)
  add(query, 'recorded_before', filters.recordedBefore)
  add(query, 'cursor', cursor ?? undefined)
  return readJson<CatalogPage<CaptureSessionListItem>>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/capture-sessions?${query.toString()}`,
    signal,
  )
}

export function getCaptureSession(
  projectId: string,
  captureSessionId: string,
  signal?: AbortSignal,
) {
  return readJson<CaptureSessionDetail>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/capture-sessions/${encodeURIComponent(captureSessionId)}`,
    signal,
  )
}

export function getCaptureSessionDatasetAdmissionPreview(
  projectId: string,
  captureSessionId: string,
  signal?: AbortSignal,
) {
  return readJson<DatasetAdmissionPreview>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/capture-sessions/${encodeURIComponent(captureSessionId)}/dataset-admission-preview`,
    signal,
  )
}

export async function createDatasetVersionDraft(
  projectId: string,
  displayName: string,
  captureSessionIds: string[],
  idempotencyKey: string,
  signal?: AbortSignal,
): Promise<DatasetVersionDraft> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/dataset-versions`,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
        ...csrfHeaders(),
      },
      body: JSON.stringify({ display_name: displayName, capture_session_ids: captureSessionIds }),
      cache: 'no-store',
      signal,
    },
  )
  if (response.ok) return (await response.json()) as DatasetVersionDraft
  throw await toCatalogError(response)
}

export function listDatasetVersions(
  projectId: string,
  cursor: string | null,
  signal?: AbortSignal,
) {
  const query = new URLSearchParams({ limit: '20' })
  add(query, 'cursor', cursor ?? undefined)
  return readJson<CatalogPage<DatasetVersionSummary>>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/dataset-versions?${query.toString()}`,
    signal,
  )
}

export function getDatasetVersion(
  projectId: string,
  datasetVersionId: string,
  signal?: AbortSignal,
) {
  return readJson<DatasetVersionDetail>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/dataset-versions/${encodeURIComponent(datasetVersionId)}`,
    signal,
  )
}

export function getDatasetVersionCurrentAdmission(
  projectId: string,
  datasetVersionId: string,
  signal?: AbortSignal,
) {
  return readJson<DatasetVersionCurrentAdmission>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/dataset-versions/${encodeURIComponent(datasetVersionId)}/current-admission`,
    signal,
  )
}

export async function createDatasetSplitPlan(
  projectId: string,
  datasetVersionId: string,
  holdoutCount: number,
  idempotencyKey: string,
  signal?: AbortSignal,
): Promise<DatasetSplitPlan> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/dataset-versions/${encodeURIComponent(datasetVersionId)}/split-plans`,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
        ...csrfHeaders(),
      },
      body: JSON.stringify({ holdout_count: holdoutCount }),
      cache: 'no-store',
      signal,
    },
  )
  if (response.ok) return (await response.json()) as DatasetSplitPlan
  throw await toCatalogError(response)
}

export async function createDatasetBuildSpecification(
  projectId: string,
  datasetVersionId: string,
  idempotencyKey: string,
  signal?: AbortSignal,
): Promise<DatasetBuildSpecification> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/dataset-versions/${encodeURIComponent(datasetVersionId)}/build-specifications`,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Idempotency-Key': idempotencyKey,
        ...csrfHeaders(),
      },
      cache: 'no-store',
      signal,
    },
  )
  if (response.ok) return (await response.json()) as DatasetBuildSpecification
  throw await toCatalogError(response)
}

export async function startDatasetVersionBuild(
  projectId: string,
  datasetVersionId: string,
  idempotencyKey: string,
  signal?: AbortSignal,
): Promise<DatasetVersionBuildStart> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/dataset-versions/${encodeURIComponent(datasetVersionId)}/build`,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Idempotency-Key': idempotencyKey,
        ...csrfHeaders(),
      },
      cache: 'no-store',
      signal,
    },
  )
  if (response.ok) return (await response.json()) as DatasetVersionBuildStart
  throw await toCatalogError(response)
}

export async function createDatasetBuildSnapshot(
  projectId: string,
  datasetVersionId: string,
  idempotencyKey: string,
  signal?: AbortSignal,
): Promise<DatasetBuildSnapshot> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/dataset-versions/${encodeURIComponent(datasetVersionId)}/build-snapshots`,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Idempotency-Key': idempotencyKey,
        ...csrfHeaders(),
      },
      cache: 'no-store',
      signal,
    },
  )
  if (response.ok) return (await response.json()) as DatasetBuildSnapshot
  throw await toCatalogError(response)
}

export async function validateDatasetVersion(
  projectId: string,
  datasetVersionId: string,
  idempotencyKey: string,
  signal?: AbortSignal,
): Promise<DatasetVersionValidation> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/dataset-versions/${encodeURIComponent(datasetVersionId)}/validations`,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Idempotency-Key': idempotencyKey,
        ...csrfHeaders(),
      },
      cache: 'no-store',
      signal,
    },
  )
  if (response.ok) return (await response.json()) as DatasetVersionValidation
  throw await toCatalogError(response)
}

export async function freezeDatasetVersion(
  projectId: string,
  datasetVersionId: string,
  idempotencyKey: string,
  signal?: AbortSignal,
): Promise<DatasetVersionFreeze> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/dataset-versions/${encodeURIComponent(datasetVersionId)}/freezes`,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Idempotency-Key': idempotencyKey,
        ...csrfHeaders(),
      },
      cache: 'no-store',
      signal,
    },
  )
  if (response.ok) return (await response.json()) as DatasetVersionFreeze
  throw await toCatalogError(response)
}

export async function requestDatasetExport(
  projectId: string,
  datasetVersionId: string,
  expiresAt: string,
  idempotencyKey: string,
  signal?: AbortSignal,
): Promise<DatasetExport> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/dataset-versions/${encodeURIComponent(datasetVersionId)}/exports`,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
        ...csrfHeaders(),
      },
      body: JSON.stringify({ purpose: 'MODEL_TRAINING', expires_at: expiresAt }),
      cache: 'no-store',
      signal,
    },
  )
  if (response.ok) return (await response.json()) as DatasetExport
  throw await toCatalogError(response)
}

export function getDatasetExportApprovalReadiness(
  projectId: string,
  datasetVersionId: string,
  signal?: AbortSignal,
): Promise<DatasetExportApprovalReadiness> {
  return readJson<DatasetExportApprovalReadiness>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/dataset-versions/${encodeURIComponent(datasetVersionId)}/exports/approval-readiness`,
    signal,
  )
}

export async function approveDatasetExport(
  projectId: string,
  datasetVersionId: string,
  idempotencyKey: string,
  signal?: AbortSignal,
): Promise<DatasetExport> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/dataset-versions/${encodeURIComponent(datasetVersionId)}/exports/approvals`,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Idempotency-Key': idempotencyKey,
        ...csrfHeaders(),
      },
      cache: 'no-store',
      signal,
    },
  )
  if (response.ok) return (await response.json()) as DatasetExport
  throw await toCatalogError(response)
}

export async function authorizeDatasetExportDownload(
  projectId: string,
  datasetVersionId: string,
  signal?: AbortSignal,
): Promise<DatasetExportDownloadAuthorization> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/dataset-versions/${encodeURIComponent(datasetVersionId)}/exports/download-authorizations`,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        ...csrfHeaders(),
      },
      cache: 'no-store',
      signal,
    },
  )
  if (response.ok) return (await response.json()) as DatasetExportDownloadAuthorization
  throw await toCatalogError(response)
}

export function listClipAssets(
  projectId: string,
  filters: ClipAssetFilters,
  cursor: string | null,
  signal?: AbortSignal,
) {
  const query = new URLSearchParams({ limit: '20' })
  add(query, 'capture_session_id', filters.captureSessionId)
  add(query, 'batch_id', filters.batchId)
  add(query, 'device_id', filters.deviceId)
  add(query, 'camera_role', filters.cameraRole)
  add(query, 'status', filters.status)
  add(query, 'folder_id', filters.folderId)
  if (filters.unclassified) query.set('unclassified', 'true')
  add(query, 'recorded_from', filters.recordedFrom)
  add(query, 'recorded_before', filters.recordedBefore)
  add(query, 'cursor', cursor ?? undefined)
  return readJson<CatalogPage<ClipAssetRead>>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/clip-assets?${query.toString()}`,
    signal,
  )
}

export function listCaptureEventLogs(
  projectId: string,
  filters: CaptureEventLogFilters,
  cursor: string | null,
  signal?: AbortSignal,
) {
  const query = new URLSearchParams({ limit: '20' })
  add(query, 'batch_id', filters.batchId)
  add(query, 'device_id', filters.deviceId)
  add(query, 'indexed_from', filters.indexedFrom)
  add(query, 'indexed_before', filters.indexedBefore)
  add(query, 'cursor', cursor ?? undefined)
  return readJson<CatalogPage<CaptureEventLogRead>>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/capture-event-logs?${query.toString()}`,
    signal,
  )
}

export function getClipAsset(projectId: string, clipAssetId: string, signal?: AbortSignal) {
  return readJson<ClipAssetRead>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/clip-assets/${encodeURIComponent(clipAssetId)}`,
    signal,
  )
}

export function listClipAssetActivity(
  projectId: string,
  clipAssetId: string,
  cursor: string | null,
  signal?: AbortSignal,
) {
  const query = new URLSearchParams({ limit: '20' })
  add(query, 'cursor', cursor ?? undefined)
  return readJson<CatalogPage<ClipAssetActivityItem>>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/clip-assets/${encodeURIComponent(clipAssetId)}/activity?${query.toString()}`,
    signal,
  )
}

export function getClipAssetMediaReport(
  projectId: string,
  clipAssetId: string,
  signal?: AbortSignal,
) {
  return readJson<MediaReport>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/clip-assets/${encodeURIComponent(clipAssetId)}/media-report`,
    signal,
  )
}

export async function deleteClipAsset(projectId: string, clipAssetId: string, reason: string) {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/clip-assets/${encodeURIComponent(clipAssetId)}`,
    {
      method: 'DELETE',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'Idempotency-Key': crypto.randomUUID(),
        ...csrfHeaders(),
      },
      body: JSON.stringify({ reason }),
      cache: 'no-store',
    },
  )
  if (response.status === 204) return
  throw await toCatalogError(response)
}

export async function authorizeClipAssetPlayback(projectId: string, clipAssetId: string) {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/clip-assets/${encodeURIComponent(clipAssetId)}/playback-grants`,
    {
      method: 'POST',
      headers: { Accept: 'application/json', ...csrfHeaders() },
      cache: 'no-store',
    },
  )
  if (response.ok) return (await response.json()) as ClipAssetPlaybackGrant
  throw await toCatalogError(response)
}

export async function requestClipAssetMediaProbe(projectId: string, clipAssetId: string) {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/clip-assets/${encodeURIComponent(clipAssetId)}/media-probe-jobs`,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Idempotency-Key': crypto.randomUUID(),
        ...csrfHeaders(),
      },
      cache: 'no-store',
    },
  )
  if (response.ok) return (await response.json()) as MediaProbeJob
  throw await toCatalogError(response)
}

export async function recoverCurrentClipAssetMediaProbeDelivery(
  projectId: string,
  clipAssetId: string,
  changeReference: string,
  idempotencyKey: string,
) {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/clip-assets/${encodeURIComponent(clipAssetId)}/media-probe-delivery-recoveries`,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
        ...csrfHeaders(),
      },
      body: JSON.stringify({ change_reference: changeReference }),
      cache: 'no-store',
    },
  )
  if (response.ok) return (await response.json()) as MediaJobDeliveryRecovery
  throw await toCatalogError(response)
}

async function readJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(path, {
    method: 'GET',
    headers: { Accept: 'application/json' },
    cache: 'no-store',
    signal,
  })

  if (response.ok) {
    return (await response.json()) as T
  }

  throw await toCatalogError(response)
}

async function toCatalogError(response: Response): Promise<CatalogApiError> {
  let body: ApiErrorBody = {}
  let hasReadableErrorBody = false
  try {
    body = (await response.json()) as ApiErrorBody
    hasReadableErrorBody = true
  } catch {
    // Use the safe fallback below for proxy and non-JSON failures.
  }
  const errorCode = typeof body.error_code === 'string' ? body.error_code : 'CATALOG_REQUEST_FAILED'
  return new CatalogApiError(
    errorMessages[errorCode] ?? '暂时无法读取视频数据，请稍后重试。',
    errorCode,
    body.retryable === true || response.status >= 500 || !hasReadableErrorBody,
    typeof body.request_id === 'string' ? body.request_id : null,
    response.status,
  )
}

function add(query: URLSearchParams, name: string, value: string | undefined) {
  const normalized = value?.trim()
  if (normalized) {
    query.set(name, normalized)
  }
}
