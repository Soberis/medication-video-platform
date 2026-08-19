package com.medicationvideo.platform.assetcapture;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.medicationvideo.platform.PlatformApiApplication;
import com.medicationvideo.platform.support.ProjectMembershipTestData;

@SpringBootTest(classes = PlatformApiApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DataManagementSummaryContractTests {

	private static final String TOKEN = "synthetic-test-bearer-token-do-not-reuse";

	private static final String SHA_ONE = "a".repeat(64);

	private static final String SHA_TWO = "b".repeat(64);

	private static final String SHA_THREE = "c".repeat(64);

	private static final String SHA_FOUR = "d".repeat(64);

	private static final String RECIPE_DIGEST = "e".repeat(64);

	private static final String SOFTWARE_DIGEST = "sha256:" + "f".repeat(64);

	private static final String LABEL_SCHEMA_ID = "10000000-0000-0000-0000-000000000001";

	private static final UUID LABEL_SCHEMA_UUID = UUID.fromString(LABEL_SCHEMA_ID);

	private static final String LABEL_SCHEMA_DIGEST = "305e92dcd10343e6c97b5780e59485b63183fadad7e9b87bc85db4bb037aa203";

	private static final String CLEANING_RULE_SET_ID = "30000000-0000-0000-0000-000000000001";

	private static final UUID CLEANING_RULE_SET_UUID = UUID.fromString(CLEANING_RULE_SET_ID);

	private static final String CLEANING_RULE_SET_DIGEST = "7c1aa3606467412b84964a3455af8695bd73c85cd9b5d08ce079061ed840fa0a";

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private MockMvc mockMvc;

	private UUID projectId;

	private UUID otherProjectId;

	@BeforeEach
	void ensureProject() {
		this.projectId = UUID.randomUUID();
		this.otherProjectId = UUID.randomUUID();
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, this.projectId);
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, this.otherProjectId);
	}

	@AfterEach
	void deleteSyntheticData() {
		deleteProjectData(this.projectId);
		deleteProjectData(this.otherProjectId);
	}

	@Test
	void returnsProjectScopedAggregateCountersWithoutPerVideoCalls() throws Exception {
		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/video-data/summary", this.projectId).header("Authorization",
					"Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total_videos").value(0))
			.andExpect(jsonPath("$.total_duration").value(0))
			.andExpect(jsonPath("$.storage_bytes").value(0))
			.andExpect(jsonPath("$.pending_annotation").value(0))
			.andExpect(jsonPath("$.pending_review").value(0))
			.andExpect(jsonPath("$.pending_cleaning").value(0))
			.andExpect(jsonPath("$.usable_count").value(0));
	}

	@Test
	void usableRequiresCurrentQualifiedQualityAndAnnotationRevision() throws Exception {
		var cameraSetId = insertCameraConfiguration(this.projectId);
		insertVideo(this.projectId, cameraSetId, new VideoSpec("video-summary-usable", 1, 128, "0".repeat(64),
				1_000_000L, "PLATFORM_LOCAL", "INGESTED", Instant.parse("2026-08-10T01:00:00Z"), WorkflowState.USABLE));
		var clipAssetId = this.jdbcTemplate.queryForObject("SELECT clip_asset_id FROM clip_asset WHERE project_id = ?",
				UUID.class, this.projectId);

		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/video-data/summary", this.projectId).header("Authorization",
					"Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.usable_count").value(1))
			.andExpect(jsonPath("$.pending_annotation").value(0))
			.andExpect(jsonPath("$.pending_review").value(0))
			.andExpect(jsonPath("$.pending_cleaning").value(0));
		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/videos", this.projectId).header("Authorization",
					"Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].cleaning_status").value("PASS"));

		// A newer quality-review version invalidates the cleaning result produced for
		// the previous quality facts until cleaning is rerun.
		this.jdbcTemplate.update("UPDATE quality_review SET version = 1 WHERE project_id = ? AND clip_asset_id = ?",
				this.projectId, clipAssetId);
		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/video-data/summary", this.projectId).header("Authorization",
					"Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.usable_count").value(0))
			.andExpect(jsonPath("$.pending_cleaning").value(1));
		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/videos", this.projectId).header("Authorization",
					"Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].cleaning_status").value("NOT_STARTED"));

		// Restore the quality version and submit a newer annotation revision. The
		// old cleaning result must not be reused for this current revision either.
		this.jdbcTemplate.update("UPDATE quality_review SET version = 0 WHERE project_id = ? AND clip_asset_id = ?",
				this.projectId, clipAssetId);
		var taskId = this.jdbcTemplate.queryForObject(
				"SELECT annotation_task_id FROM annotation_task WHERE project_id = ?", UUID.class, this.projectId);
		var now = Instant.parse("2026-08-10T02:00:00Z");
		var revisionId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO annotation_revision (
				    annotation_revision_id, project_id, annotation_task_id, clip_asset_id, revision_number,
				    annotation_schema_version, annotation_document, document_sha256, submitted_by,
				    idempotency_key, submitted_at, label_schema_id, label_schema_digest)
				VALUES (?, ?, ?, ?, 2, 'annotation-document.v1', '{"segments": []}'::jsonb, ?,
				        'summary-reviewer', ?, ?, ?, ?)
				""", revisionId, this.projectId, taskId, clipAssetId, "7".repeat(64),
				"summary-revision-drift-" + UUID.randomUUID(), timestamp(now), LABEL_SCHEMA_UUID, LABEL_SCHEMA_DIGEST);
		this.jdbcTemplate.update("""
				INSERT INTO annotation_review (
				    annotation_review_id, project_id, annotation_revision_id, decision, review_note,
				    reviewed_by, idempotency_key, reviewed_at)
				VALUES (?, ?, ?, 'APPROVED', NULL, 'summary-reviewer', ?, ?)
				""", UUID.randomUUID(), this.projectId, revisionId, "summary-review-drift-" + UUID.randomUUID(),
				timestamp(now));

		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/video-data/summary", this.projectId).header("Authorization",
					"Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.usable_count").value(0))
			.andExpect(jsonPath("$.pending_cleaning").value(1));
		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/videos", this.projectId).header("Authorization",
					"Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].cleaning_status").value("NOT_STARTED"));
	}

	@Test
	void aggregatesSyntheticVideosBySourceCameraStatusAndWorkflowState() throws Exception {
		var cameraSetId = insertCameraConfiguration(this.projectId);
		insertVideo(this.projectId, cameraSetId, new VideoSpec("video-summary-one", 1, 100, SHA_ONE, 1_000_000L,
				"HOSPITAL_REMOTE", "INGESTED", Instant.parse("2026-08-10T01:00:00Z"), WorkflowState.UNLABELED));
		insertVideo(this.projectId, cameraSetId, new VideoSpec("video-summary-two", 3, 200, SHA_TWO, 2_000_000L,
				"HOSPITAL_REMOTE", "INGESTED", Instant.parse("2026-08-10T02:00:00Z"), WorkflowState.PENDING_REVIEW));
		insertVideo(this.projectId, cameraSetId, new VideoSpec("video-summary-three", 5, 300, SHA_THREE, 3_000_000L,
				"PLATFORM_LOCAL", "INGESTED", Instant.parse("2026-08-10T03:00:00Z"), WorkflowState.PENDING_CLEANING));
		insertVideo(this.projectId, cameraSetId, new VideoSpec("video-summary-four", 1, 400, SHA_FOUR, 4_000_000L,
				"PLATFORM_LOCAL", "INGEST_FAILED", Instant.parse("2026-08-09T04:00:00Z"), WorkflowState.USABLE));
		insertVideo(this.otherProjectId, null,
				new VideoSpec("video-summary-other-project", 2, 900, "1".repeat(64), 9_000_000L, "HOSPITAL_REMOTE",
						"INGESTED", Instant.parse("2026-08-10T05:00:00Z"), WorkflowState.UNLABELED));

		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/video-data/summary", this.projectId)
				.param("captured_from", "2026-08-10T00:00:00Z")
				.param("captured_before", "2026-08-11T00:00:00Z")
				.header("Authorization", "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total_videos").value(3))
			.andExpect(jsonPath("$.total_duration").value(6_000_000))
			.andExpect(jsonPath("$.storage_bytes").value(600))
			.andExpect(jsonPath("$.counts_by_source.HOSPITAL_REMOTE").value(2))
			.andExpect(jsonPath("$.counts_by_source.PLATFORM_LOCAL").value(1))
			.andExpect(jsonPath("$.counts_by_camera.1").value(1))
			.andExpect(jsonPath("$.counts_by_camera.3").value(1))
			.andExpect(jsonPath("$.counts_by_camera.5").value(1))
			.andExpect(jsonPath("$.counts_by_camera.2").doesNotExist())
			.andExpect(jsonPath("$.counts_by_status.INGESTED").value(3))
			.andExpect(jsonPath("$.pending_annotation").value(1))
			.andExpect(jsonPath("$.pending_review").value(1))
			.andExpect(jsonPath("$.pending_cleaning").value(1))
			.andExpect(jsonPath("$.usable_count").value(0));

		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/video-data/summary", this.projectId)
				.param("source", "PLATFORM_LOCAL")
				.header("Authorization", "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total_videos").value(2))
			.andExpect(jsonPath("$.total_duration").value(7_000_000))
			.andExpect(jsonPath("$.storage_bytes").value(700))
			.andExpect(jsonPath("$.counts_by_source.PLATFORM_LOCAL").value(2))
			.andExpect(jsonPath("$.counts_by_camera.1").value(1))
			.andExpect(jsonPath("$.counts_by_camera.5").value(1))
			.andExpect(jsonPath("$.counts_by_status.INGESTED").value(1))
			.andExpect(jsonPath("$.counts_by_status.INGEST_FAILED").value(1))
			.andExpect(jsonPath("$.pending_annotation").value(0))
			.andExpect(jsonPath("$.pending_review").value(0))
			.andExpect(jsonPath("$.pending_cleaning").value(1))
			.andExpect(jsonPath("$.usable_count").value(0));

		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/video-data/summary", this.projectId).param("camera_number", "3")
				.header("Authorization", "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total_videos").value(1))
			.andExpect(jsonPath("$.total_duration").value(2_000_000))
			.andExpect(jsonPath("$.storage_bytes").value(200))
			.andExpect(jsonPath("$.counts_by_source.HOSPITAL_REMOTE").value(1))
			.andExpect(jsonPath("$.counts_by_camera.3").value(1))
			.andExpect(jsonPath("$.counts_by_status.INGESTED").value(1))
			.andExpect(jsonPath("$.pending_annotation").value(0))
			.andExpect(jsonPath("$.pending_review").value(1))
			.andExpect(jsonPath("$.pending_cleaning").value(0))
			.andExpect(jsonPath("$.usable_count").value(0));
	}

	private UUID insertCameraConfiguration(UUID projectId) {
		var now = Instant.parse("2026-08-10T00:00:00Z");
		var cameraSetId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO camera_set (
				    camera_set_id, project_id, camera_set_key, configuration_version, layout_version,
				    request_fingerprint, created_at)
				VALUES (?, ?, ?, 1, 'summary-layout-v1', ?, ?)
				""", cameraSetId, projectId, "summary-set-" + UUID.randomUUID(), "1".repeat(64), timestamp(now));
		for (var cameraIndex = 0; cameraIndex < 5; cameraIndex++) {
			var channelId = UUID.randomUUID();
			this.jdbcTemplate.update("""
					INSERT INTO camera_channel (camera_channel_id, project_id, provisioning_key, created_at)
					VALUES (?, ?, ?, ?)
					""", channelId, projectId, "summary-channel-" + UUID.randomUUID(), timestamp(now));
			this.jdbcTemplate.update("""
					INSERT INTO camera_set_member (
					    camera_set_member_id, camera_set_id, camera_channel_id, camera_index,
					    camera_role, mount_position, enabled, created_at)
					VALUES (?, ?, ?, ?, ?, ?, TRUE, ?)
					""", UUID.randomUUID(), cameraSetId, channelId, cameraIndex, "CAMERA_" + (cameraIndex + 1),
					"summary-mount-" + (cameraIndex + 1), timestamp(now));
		}
		return cameraSetId;
	}

	private void insertVideo(UUID projectId, UUID cameraSetId, VideoSpec video) {
		var now = video.createdAt();
		var batchId = UUID.randomUUID();
		var uploadFileId = UUID.randomUUID();
		var clipAssetId = UUID.randomUUID();
		var clientFileId = UUID.randomUUID();
		var mediaJobId = UUID.randomUUID();
		var mediaReportId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO upload_batch (
				    batch_id, project_id, idempotency_key, request_fingerprint, device_id, upload_source_type,
				    external_manifest_id, initiated_by_actor_id, file_count, total_size_bytes,
				    manifest_sha256, status, expires_at, created_at, updated_at, version,
				    verified_manifest_sha256, completed_at, terminated_at, termination_reason_code)
				VALUES (?, ?, ?, ?, 'summary-device', ?, NULL, 'summary-test', 1, ?, ?, 'COMPLETED',
				        ?, ?, ?, 0, ?, ?, NULL, NULL)
				""", batchId, projectId, "summary-batch-" + UUID.randomUUID(), video.sha256(), video.source(),
				video.sizeBytes(), video.sha256(), timestamp(now.plusSeconds(3600)), timestamp(now), timestamp(now),
				video.sha256(), timestamp(now));
		this.jdbcTemplate.update("""
				INSERT INTO upload_file (
				    upload_file_id, batch_id, client_file_id, file_name, idempotency_key, request_fingerprint,
				    file_kind, content_type, size_bytes, sha256, part_size_bytes, total_parts, storage_bucket,
				    object_key, storage_upload_id, multipart_created_at, completion_attempt_id,
				    completion_started_at, completion_lease_expires_at, completion_finished_at, verified_sha256,
				    completed_storage_etag, completion_error_code, status, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, ?, ?, 'VIDEO', 'video/mp4', ?, ?, 8388608, 1, 'summary-bucket', ?, ?, ?,
				        NULL, ?, NULL, ?, ?, 'summary-etag', NULL, 'UPLOADED', ?, ?, 0)
				""", uploadFileId, batchId, clientFileId, video.name() + ".mp4", "summary-file-" + UUID.randomUUID(),
				video.sha256(), video.sizeBytes(), video.sha256(), "summary/" + clipAssetId,
				"summary-upload-" + uploadFileId, timestamp(now), timestamp(now), timestamp(now.plusSeconds(1)),
				video.sha256(), timestamp(now), timestamp(now));
		this.jdbcTemplate.update("""
				INSERT INTO clip_asset (
				    clip_asset_id, project_id, batch_id, source_upload_file_id, client_file_id, content_type,
				    size_bytes, sha256, storage_bucket, object_key, storage_etag, asset_status, created_at,
				    updated_at, version, deleted_at, deleted_by, delete_reason)
				VALUES (?, ?, ?, ?, ?, 'video/mp4', ?, ?, 'summary-bucket', ?, 'summary-etag', ?, ?, ?, 0,
				        NULL, NULL, NULL)
				""", clipAssetId, projectId, batchId, uploadFileId, clientFileId, video.sizeBytes(), video.sha256(),
				"summary/" + clipAssetId, video.status(), timestamp(now), timestamp(now));
		this.jdbcTemplate.update(
				"""
						INSERT INTO media_job (
						    media_job_id, project_id, clip_asset_id, job_type, input_digest, input_size_bytes,
						    content_type, probe_profile_key, recipe_digest, status, idempotency_key,
						    request_fingerprint, created_at, updated_at, version)
						VALUES (?, ?, ?, 'MEDIA_PROBE', ?, ?, 'video/mp4', 'media-probe-baseline-v1', ?, 'SUCCEEDED', ?, ?, ?, ?, 0)
						""",
				mediaJobId, projectId, clipAssetId, video.sha256(), video.sizeBytes(), RECIPE_DIGEST,
				"summary-media-job-" + UUID.randomUUID(), video.sha256(), timestamp(now), timestamp(now));
		this.jdbcTemplate.update("""
				INSERT INTO media_report (
				    media_report_id, media_job_id, project_id, clip_asset_id, report_schema_version,
				    input_digest, recipe_digest, software_digest, container_format, duration_microseconds,
				    video_stream_count, primary_video_stream_index, video_codec, width, height,
				    time_base_numerator, time_base_denominator, variable_frame_rate, audio_stream_count,
				    decode_verified, input_digest_verified, warning_codes, created_at)
				VALUES (?, ?, ?, ?, 'media-report.v1', ?, ?, ?, 'mp4', ?, 1, 0, 'h264', 1920, 1080,
				        1, 1000, FALSE, 0, TRUE, TRUE, '[]'::jsonb, ?)
				""", mediaReportId, mediaJobId, projectId, clipAssetId, video.sha256(), RECIPE_DIGEST, SOFTWARE_DIGEST,
				video.durationMicroseconds(), timestamp(now));
		if (cameraSetId != null) {
			insertCameraStream(projectId, cameraSetId, batchId, clipAssetId, clientFileId, video.cameraNumber(), now);
		}
		insertWorkflow(projectId, clipAssetId, video, mediaReportId);
	}

	private void insertCameraStream(UUID projectId, UUID cameraSetId, UUID batchId, UUID clipAssetId, UUID clientFileId,
			int cameraNumber, Instant now) {
		var captureSessionId = UUID.randomUUID();
		var snapshotId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO capture_session (
				    capture_session_id, project_id, batch_id, device_id, camera_set_id, metadata_upload_file_id,
				    metadata_schema_version, metadata_sha256, expected_camera_count, received_camera_count,
				    sync_group_id, device_time_utc, device_timezone, server_receive_time, assembly_status,
				    quality_status, created_at, updated_at, version)
				VALUES (?, ?, ?, 'summary-device', ?, NULL, NULL, NULL, 5, 1, ?, NULL, NULL, ?, 'COMPLETE',
				        'PENDING', ?, ?, 0)
				""", captureSessionId, projectId, batchId, cameraSetId, "summary-sync-" + captureSessionId,
				timestamp(now), timestamp(now), timestamp(now));
		this.jdbcTemplate.update("""
				INSERT INTO camera_set_snapshot (
				    camera_set_snapshot_id, capture_session_id, source_camera_set_id, camera_set_key,
				    configuration_version, layout_version, expected_camera_count, created_at)
				SELECT ?, ?, camera_set_id, camera_set_key, configuration_version, layout_version, 5, ?
				FROM camera_set WHERE camera_set_id = ?
				""", snapshotId, captureSessionId, timestamp(now), cameraSetId);
		// The snapshot is immutable evidence; copy all five fixed camera members before
		// binding one stream.
		this.jdbcTemplate.update("""
				INSERT INTO camera_set_snapshot_member (
				    camera_set_snapshot_member_id, camera_set_snapshot_id, camera_channel_id, camera_index,
				    camera_role, mount_position, enabled, created_at)
				SELECT gen_random_uuid(), ?, camera_channel_id, camera_index, camera_role, mount_position, enabled, ?
				FROM camera_set_member WHERE camera_set_id = ? ORDER BY camera_index
				""", snapshotId, timestamp(now), cameraSetId);
		var selectedMemberId = this.jdbcTemplate.queryForObject("""
				SELECT camera_set_snapshot_member_id
				FROM camera_set_snapshot_member
				WHERE camera_set_snapshot_id = ? AND camera_index = ?
				""", UUID.class, snapshotId, cameraNumber - 1);
		var channelId = this.jdbcTemplate.queryForObject("""
				SELECT camera_channel_id FROM camera_set_snapshot_member
				WHERE camera_set_snapshot_member_id = ?
				""", UUID.class, selectedMemberId);
		this.jdbcTemplate.update("""
				INSERT INTO camera_stream (
				    camera_stream_id, capture_session_id, camera_set_snapshot_member_id, camera_channel_id,
				    camera_index, clip_asset_id, client_file_id, record_start_utc, time_offset_ms,
				    declared_sync_status, stream_status, quality_status, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 'PASS', 'INGESTED', 'PENDING', ?, ?, 0)
				""", UUID.randomUUID(), captureSessionId, selectedMemberId, channelId, cameraNumber - 1, clipAssetId,
				clientFileId, timestamp(now), timestamp(now), timestamp(now));
	}

	private void insertWorkflow(UUID projectId, UUID clipAssetId, VideoSpec video, UUID mediaReportId) {
		if (video.workflowState() == WorkflowState.UNLABELED) {
			return;
		}
		var now = video.createdAt();
		var taskId = UUID.randomUUID();
		var taskStatus = video.workflowState() == WorkflowState.PENDING_REVIEW ? "SUBMITTED" : "COMPLETED";
		this.jdbcTemplate.update("""
				INSERT INTO annotation_task (
				    annotation_task_id, project_id, clip_asset_id, assignee_actor_id, created_by,
				    idempotency_key, task_status, created_at, updated_at, version, label_schema_id,
				    label_schema_digest)
				VALUES (?, ?, ?, 'summary-reviewer', 'summary-test', ?, ?, ?, ?, 0, ?, ?)
				""", taskId, projectId, clipAssetId, "summary-task-" + UUID.randomUUID(), taskStatus, timestamp(now),
				timestamp(now), LABEL_SCHEMA_UUID, LABEL_SCHEMA_DIGEST);
		if (video.workflowState() == WorkflowState.PENDING_REVIEW) {
			return;
		}
		var revisionId = UUID.randomUUID();
		var documentSha = "9".repeat(64);
		this.jdbcTemplate.update("""
				INSERT INTO annotation_revision (
				    annotation_revision_id, project_id, annotation_task_id, clip_asset_id, revision_number,
				    annotation_schema_version, annotation_document, document_sha256, submitted_by,
				    idempotency_key, submitted_at, label_schema_id, label_schema_digest)
				VALUES (?, ?, ?, ?, 1, 'annotation-document.v1', '{"segments": []}'::jsonb, ?,
				        'summary-reviewer', ?, ?, ?, ?)
				""", revisionId, projectId, taskId, clipAssetId, documentSha, "summary-revision-" + UUID.randomUUID(),
				timestamp(now), LABEL_SCHEMA_UUID, LABEL_SCHEMA_DIGEST);
		var reviewId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO annotation_review (
				    annotation_review_id, project_id, annotation_revision_id, decision, review_note,
				    reviewed_by, idempotency_key, reviewed_at)
				VALUES (?, ?, ?, 'APPROVED', NULL, 'summary-reviewer', ?, ?)
				""", reviewId, projectId, revisionId, "summary-review-" + UUID.randomUUID(), timestamp(now));
		this.jdbcTemplate.update("""
				INSERT INTO quality_review (
				    quality_review_id, project_id, clip_asset_id, decision, reason_code,
				    reviewed_by, reviewed_at, version)
				VALUES (?, ?, ?, 'QUALIFIED', 'MEETS_REQUIREMENTS', 'summary-reviewer', ?, 0)
				""", UUID.randomUUID(), projectId, clipAssetId, timestamp(now));
		if (video.workflowState() != WorkflowState.USABLE) {
			return;
		}
		var cleaningRunId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO cleaning_run (
				    cleaning_run_id, project_id, clip_asset_id, annotation_revision_id, annotation_review_id,
				    review_decision, cleaning_rule_set_id, rule_set_digest, input_fingerprint, asset_sha256,
				    document_sha256, label_schema_id, label_schema_digest, quality_review_version,
				    media_report_id, media_input_digest, media_recipe_digest, verified_duration_microseconds,
				    trigger_type, run_status, triggered_by, started_at, completed_at)
				SELECT ?, ?, ?, ?, ?, 'APPROVED', ?, ?, ?, asset.sha256, ?, ?, ?, 0, ?, asset.sha256, ?,
				       ?, 'ANNOTATION_APPROVAL', 'SUCCEEDED', 'summary-test', ?, ?
				FROM clip_asset asset WHERE asset.clip_asset_id = ?
				""", cleaningRunId, projectId, clipAssetId, revisionId, reviewId, CLEANING_RULE_SET_UUID,
				CLEANING_RULE_SET_DIGEST, "8".repeat(64), documentShaForCleaning(), LABEL_SCHEMA_UUID,
				LABEL_SCHEMA_DIGEST, mediaReportId, RECIPE_DIGEST, video.durationMicroseconds(), timestamp(now),
				timestamp(now), clipAssetId);
		this.jdbcTemplate.update("""
				INSERT INTO cleaning_result (
				    cleaning_result_id, project_id, cleaning_run_id, annotation_revision_id, outcome,
				    reason_codes, executed_checks, evaluated_at)
				VALUES (?, ?, ?, ?, 'PASS', '[ ]'::jsonb, '["ANNOTATION_APPROVED"]'::jsonb, ?)
				""", UUID.randomUUID(), projectId, cleaningRunId, revisionId, timestamp(now));
	}

	private static String documentShaForCleaning() {
		return "9".repeat(64);
	}

	private static Timestamp timestamp(Instant instant) {
		return Timestamp.from(instant);
	}

	private void deleteProjectData(UUID projectId) {
		if (projectId == null) {
			return;
		}
		this.jdbcTemplate.update("DELETE FROM cleaning_result WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM cleaning_run WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM annotation_review WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM annotation_revision WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM annotation_task WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM camera_stream WHERE capture_session_id IN "
				+ "(SELECT capture_session_id FROM capture_session WHERE project_id = ?)", projectId);
		this.jdbcTemplate.update("DELETE FROM camera_set_snapshot_member WHERE camera_set_snapshot_id IN "
				+ "(SELECT snapshot.camera_set_snapshot_id FROM camera_set_snapshot snapshot "
				+ "JOIN capture_session session ON session.capture_session_id = snapshot.capture_session_id "
				+ "WHERE session.project_id = ?)", projectId);
		this.jdbcTemplate.update("DELETE FROM camera_set_snapshot WHERE capture_session_id IN "
				+ "(SELECT capture_session_id FROM capture_session WHERE project_id = ?)", projectId);
		this.jdbcTemplate.update("DELETE FROM capture_session WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM media_report WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM media_job WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM quality_review WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM clip_asset WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM upload_part WHERE upload_file_id IN "
				+ "(SELECT upload_file_id FROM upload_file WHERE batch_id IN "
				+ "(SELECT batch_id FROM upload_batch WHERE project_id = ?))", projectId);
		this.jdbcTemplate.update("DELETE FROM upload_file WHERE batch_id IN "
				+ "(SELECT batch_id FROM upload_batch WHERE project_id = ?)", projectId);
		this.jdbcTemplate.update("DELETE FROM upload_batch WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM camera_set_member WHERE camera_set_id IN "
				+ "(SELECT camera_set_id FROM camera_set WHERE project_id = ?)", projectId);
		this.jdbcTemplate.update("DELETE FROM camera_set WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM camera_channel WHERE project_id = ?", projectId);
	}

	private record VideoSpec(String name, int cameraNumber, long sizeBytes, String sha256, long durationMicroseconds,
			String source, String status, Instant createdAt, WorkflowState workflowState) {
	}

	private enum WorkflowState {

		UNLABELED, PENDING_REVIEW, PENDING_CLEANING, USABLE

	}

}
