package com.medicationvideo.platform.annotationreview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.medicationvideo.platform.iamproject.SystemActors;
import com.medicationvideo.platform.support.ProjectMembershipTestData;
import com.medicationvideo.platform.support.VerifiedMediaTestData;

/**
 * Behaviour-level contract for V1.36 fully-automatic optimistic approval.
 *
 * <p>
 * These tests drive the real service and assert the persisted outcome: a capture group
 * with provenance-valid hand detection and action candidates on every present camera is
 * assigned, submitted and approved as {@code Revision 1} under the {@code AUTO_SYSTEM}
 * actor without any human touch; failures, missing evidence, idempotency and the human
 * re-edit boundary are all verified against the database rather than against mock
 * interactions.
 */
@SpringBootTest
@ActiveProfiles("test")
class AutomaticOptimisticApprovalServiceTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000740");

	private static final String SHA256 = "a".repeat(64);

	private static final String MODEL_SHA256 = "b".repeat(64);

	private static final String PROFILE_SHA256 = "c".repeat(64);

	private static final String RESULT_SHA256 = "d".repeat(64);

	private static final String LEASE_HASH = "e".repeat(64);

	private static final Instant BASE_TIME = Instant.parse("2026-08-01T01:00:00Z");

	@Autowired
	private AutomaticOptimisticApprovalService service;

	@Autowired
	private AnnotationTaskService annotationTaskService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		clearState();
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, PROJECT_ID);
	}

	@AfterEach
	void tearDown() {
		clearState();
	}

	@Test
	void autoApprovesSingleCameraGroupWhenHandAndActionEvidenceBothSucceed() {
		var group = createGroup(1, 1, "COMPLETE", 1);
		insertHandJob(group.clipAssetIds().getFirst(), "SUCCEEDED", "HAND_DETECTED", "SUCCEEDED", "HAND_DETECTED",
				List.of(new CandidateSpec(520, 0, "LEFT", "0.9100", 1)));
		insertActionJob(group.captureGroupId(), group.clipAssetIds().getFirst(), "SUCCEEDED");

		this.service.adoptCaptureGroupIfEligible(PROJECT_ID, group.captureGroupId());

		var taskId = findTaskId(group.captureGroupId());
		assertNotNull(taskId, "dual-qualified group must be assigned automatically");
		assertTaskRow(taskId, "COMPLETED", SystemActors.AUTO_SYSTEM, SystemActors.AUTO_SYSTEM);
		assertRevisionCount(taskId, 1);
		assertEquals(Integer.valueOf(1), this.jdbcTemplate.queryForObject(
				"SELECT revision_number FROM annotation_revision WHERE annotation_task_id = ?", Integer.class, taskId));
		assertEquals("APPROVED", approvedDecision(taskId));
		assertEquals(SystemActors.AUTO_SYSTEM, approvedBy(taskId));
	}

	@Test
	void autoApprovesEveryPresentCameraAndReviewsAllOfThem() {
		var group = createGroup(2, 2, "COMPLETE", 2);
		for (var index = 0; index < 2; index += 1) {
			insertHandJob(group.clipAssetIds().get(index), "SUCCEEDED", "HAND_DETECTED", "SUCCEEDED", "HAND_DETECTED",
					List.of(new CandidateSpec(520, 0, "LEFT", "0.9000", index + 1)));
			insertActionJob(group.captureGroupId(), group.clipAssetIds().get(index), "SUCCEEDED");
		}

		this.service.adoptCaptureGroupIfEligible(PROJECT_ID, group.captureGroupId());

		var taskId = findTaskId(group.captureGroupId());
		assertNotNull(taskId);
		assertTaskRow(taskId, "COMPLETED", SystemActors.AUTO_SYSTEM, SystemActors.AUTO_SYSTEM);
		assertRevisionCount(taskId, 1);
		var reviewedCameras = this.jdbcTemplate.queryForObject(
				"SELECT reviewed_camera_numbers::text FROM annotation_review WHERE annotation_revision_id IN "
						+ "(SELECT annotation_revision_id FROM annotation_revision WHERE annotation_task_id = ?)",
				String.class, taskId);
		assertNotNull(reviewedCameras);
		assertTrue(reviewedCameras.contains("1") && reviewedCameras.contains("2"),
				"all present cameras must be reviewed, got " + reviewedCameras);
	}

	@Test
	void leavesGroupUntouchedWhenHandDetectionFails() {
		var group = createGroup(1, 1, "COMPLETE", 1);
		insertHandJob(group.clipAssetIds().getFirst(), "FAILED", "NO_HAND_DETECTED", "FAILED", "NO_HAND_DETECTED",
				List.of());
		insertActionJob(group.captureGroupId(), group.clipAssetIds().getFirst(), "SUCCEEDED");

		this.service.adoptCaptureGroupIfEligible(PROJECT_ID, group.captureGroupId());

		assertNull(findTaskId(group.captureGroupId()), "failed hand detection must not auto-adopt");
		assertRevisionCountForGroup(group.captureGroupId(), 0);
	}

	@Test
	void leavesGroupUntouchedWhenActionCandidateMissing() {
		var group = createGroup(1, 1, "COMPLETE", 1);
		insertHandJob(group.clipAssetIds().getFirst(), "SUCCEEDED", "HAND_DETECTED", "SUCCEEDED", "HAND_DETECTED",
				List.of(new CandidateSpec(520, 0, "LEFT", "0.9100", 1)));

		this.service.adoptCaptureGroupIfEligible(PROJECT_ID, group.captureGroupId());

		assertNull(findTaskId(group.captureGroupId()), "missing action candidates must not auto-adopt");
		assertRevisionCountForGroup(group.captureGroupId(), 0);
	}

	@Test
	void repeatedTriggersAndReconcileProduceOnlyOneRevision() {
		var group = createGroup(1, 1, "COMPLETE", 1);
		insertHandJob(group.clipAssetIds().getFirst(), "SUCCEEDED", "HAND_DETECTED", "SUCCEEDED", "HAND_DETECTED",
				List.of(new CandidateSpec(520, 0, "LEFT", "0.9100", 1)));
		insertActionJob(group.captureGroupId(), group.clipAssetIds().getFirst(), "SUCCEEDED");

		this.service.adoptCaptureGroupIfEligible(PROJECT_ID, group.captureGroupId());
		this.service.adoptCaptureGroupIfEligible(PROJECT_ID, group.captureGroupId());
		this.service.reconcile();

		var taskId = findTaskId(group.captureGroupId());
		assertNotNull(taskId);
		assertTaskRow(taskId, "COMPLETED", SystemActors.AUTO_SYSTEM, SystemActors.AUTO_SYSTEM);
		assertRevisionCount(taskId, 1);
	}

	@Test
	void doesNotTakeOverATaskCreatedByAHuman() {
		var group = createGroup(1, 1, "COMPLETE", 1);
		insertHandJob(group.clipAssetIds().getFirst(), "SUCCEEDED", "HAND_DETECTED", "SUCCEEDED", "HAND_DETECTED",
				List.of(new CandidateSpec(520, 0, "LEFT", "0.9100", 1)));
		insertActionJob(group.captureGroupId(), group.clipAssetIds().getFirst(), "SUCCEEDED");

		// A human member starts the task themselves before any automation runs.
		this.annotationTaskService.assignForHumanLabel(PROJECT_ID, group.captureGroupId(),
				ProjectMembershipTestData.MEMBER_ACTOR, ProjectMembershipTestData.MEMBER_ACTOR,
				"human-assign-" + group.captureGroupId(), "human-request", "human-trace");

		this.service.adoptCaptureGroupIfEligible(PROJECT_ID, group.captureGroupId());

		var taskId = findTaskId(group.captureGroupId());
		assertNotNull(taskId);
		assertTaskRow(taskId, "ASSIGNED", ProjectMembershipTestData.MEMBER_ACTOR,
				ProjectMembershipTestData.MEMBER_ACTOR);
		assertRevisionCount(taskId, 0);
	}

	@Test
	void doesNotOverwriteAfterHumanReopensAnAutoApprovedTaskForReEdit() {
		var group = createGroup(1, 1, "COMPLETE", 1);
		insertHandJob(group.clipAssetIds().getFirst(), "SUCCEEDED", "HAND_DETECTED", "SUCCEEDED", "HAND_DETECTED",
				List.of(new CandidateSpec(520, 0, "LEFT", "0.9100", 1)));
		insertActionJob(group.captureGroupId(), group.clipAssetIds().getFirst(), "SUCCEEDED");

		this.service.adoptCaptureGroupIfEligible(PROJECT_ID, group.captureGroupId());
		var taskId = findTaskId(group.captureGroupId());
		assertNotNull(taskId);
		assertTaskRow(taskId, "COMPLETED", SystemActors.AUTO_SYSTEM, SystemActors.AUTO_SYSTEM);

		// A human reviewer reopens the approved task for one-click re-edit.
		this.annotationTaskService.reopenTask(PROJECT_ID, taskId, ProjectMembershipTestData.ADMIN_ACTOR, true,
				"human-reopen-" + taskId, "human-reopen-request", "human-trace");

		// The completion events keep firing; the system must never reclaim the human
		// task.
		this.service.adoptCaptureGroupIfEligible(PROJECT_ID, group.captureGroupId());
		this.service.reconcile();

		assertTaskRow(taskId, "ASSIGNED", SystemActors.AUTO_SYSTEM, SystemActors.AUTO_SYSTEM);
		assertRevisionCount(taskId, 1);
		assertEquals(Integer.valueOf(1),
				this.jdbcTemplate.queryForObject(
						"SELECT max(revision_number) FROM annotation_revision WHERE annotation_task_id = ?",
						Integer.class, taskId));
	}

	// ---------------------------------------------------------------------
	// Assertions
	// ---------------------------------------------------------------------

	private UUID findTaskId(UUID captureGroupId) {
		var ids = this.jdbcTemplate.queryForList(
				"SELECT annotation_task_id FROM annotation_task WHERE project_id = ? AND capture_group_id = ?",
				UUID.class, PROJECT_ID, captureGroupId);
		return ids.isEmpty() ? null : ids.getFirst();
	}

	private void assertTaskRow(UUID taskId, String expectedStatus, String expectedCreatedBy, String expectedAssignee) {
		var row = this.jdbcTemplate.queryForMap(
				"SELECT task_status, created_by, assignee_actor_id FROM annotation_task WHERE annotation_task_id = ?",
				taskId);
		assertEquals(expectedStatus, (String) row.get("task_status"));
		assertEquals(expectedCreatedBy, (String) row.get("created_by"));
		assertEquals(expectedAssignee, (String) row.get("assignee_actor_id"));
	}

	private void assertRevisionCount(UUID taskId, int expected) {
		assertEquals(Integer.valueOf(expected), this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM annotation_revision WHERE annotation_task_id = ?", Integer.class, taskId));
	}

	private void assertRevisionCountForGroup(UUID captureGroupId, int expected) {
		assertEquals(Integer.valueOf(expected),
				this.jdbcTemplate.queryForObject(
						"SELECT count(*) FROM annotation_revision WHERE project_id = ? AND capture_group_id = ?",
						Integer.class, PROJECT_ID, captureGroupId));
	}

	private String approvedDecision(UUID taskId) {
		return this.jdbcTemplate.queryForObject(
				"SELECT decision::text FROM annotation_review WHERE annotation_revision_id IN "
						+ "(SELECT annotation_revision_id FROM annotation_revision WHERE annotation_task_id = ?)",
				String.class, taskId);
	}

	private String approvedBy(UUID taskId) {
		return this.jdbcTemplate.queryForObject(
				"SELECT reviewed_by FROM annotation_review WHERE annotation_revision_id IN "
						+ "(SELECT annotation_revision_id FROM annotation_revision WHERE annotation_task_id = ?)",
				String.class, taskId);
	}

	// ---------------------------------------------------------------------
	// Synthetic data fixtures
	// ---------------------------------------------------------------------

	private record SchemaSnapshot(UUID id, String digest) {
	}

	private SchemaSnapshot currentSchema() {
		return this.jdbcTemplate.queryForObject(
				"SELECT label_schema_id, schema_digest FROM label_schema WHERE status = 'ACTIVE' ORDER BY created_at DESC LIMIT 1",
				(rs, rowNum) -> new SchemaSnapshot(rs.getObject("label_schema_id", UUID.class),
						rs.getString("schema_digest")));
	}

	private void insertActionJob(UUID captureGroupId, UUID clipAssetId, String status) {
		var schema = currentSchema();
		var jobId = UUID.randomUUID();
		var now = Timestamp.from(BASE_TIME.plusSeconds(20 + Math.floorMod(jobId.hashCode(), 1000)));
		this.jdbcTemplate.update("""
				INSERT INTO ai_job (
				    ai_job_id, project_id, clip_asset_id, algorithm_key, algorithm_version,
				    model_sha256, input_sha256, profile_sha256, input_size_bytes, content_type, sampling_fps,
				    status, idempotency_key, request_fingerprint, requested_by, created_at, updated_at, version,
				    hand_result, annotation_task_id, label_schema_id, label_schema_digest, allowed_labels)
				VALUES (?, ?, ?, 'QWEN3_5_ACTION', 'v1', ?, ?, ?, 1000, 'video/mp4', 1, ?, ?, ?, 'synthetic', ?, ?, 0,
				        'UNKNOWN', NULL, ?, ?, CAST(? AS JSONB))
				""", jobId, PROJECT_ID, clipAssetId, MODEL_SHA256, SHA256, PROFILE_SHA256, status, key("action-job"),
				SHA256, now, now, schema.id(), schema.digest(), "[\"acquire_item\"]");
		this.jdbcTemplate.update("""
				INSERT INTO ai_job_attempt (
				    ai_job_attempt_id, ai_job_id, attempt, message_id, lease_token_hash,
				    status, worker_id, lease_until, updated_at, created_at)
				VALUES (?, ?, 1, ?, ?, ?, 'synthetic-action-worker', ?, ?, ?)
				""", UUID.randomUUID(), jobId, UUID.randomUUID(), LEASE_HASH, status, now, now, now);
		if ("SUCCEEDED".equals(status)) {
			this.jdbcTemplate.update("""
					INSERT INTO action_candidate (
					    action_candidate_id, ai_job_id, project_id, capture_group_id, clip_asset_id,
					    annotation_task_id, label_schema_id, label_schema_digest,
					    start_ms, end_ms, action_label, confidence, source_window, stitched_windows, created_at)
					VALUES (?, ?, ?, ?, ?, NULL, ?, ?, 100, 900, 'acquire_item', 0.9, 1, '[]'::JSONB, ?)
					""", UUID.randomUUID(), jobId, PROJECT_ID, captureGroupId, clipAssetId, schema.id(),
					schema.digest(), now);
		}
	}

	private void insertHandJob(UUID clipAssetId, String jobStatus, String jobHandResult, String attemptStatus,
			String attemptHandResult, List<CandidateSpec> candidates) {
		var jobId = UUID.randomUUID();
		var attemptId = UUID.randomUUID();
		var messageId = UUID.randomUUID();
		var now = Timestamp.from(BASE_TIME.plusSeconds(Math.floorMod(jobId.hashCode(), 0x7fff)));
		this.jdbcTemplate.update(
				"""
						INSERT INTO ai_job (ai_job_id, project_id, clip_asset_id, algorithm_key, algorithm_version,
						 model_sha256, input_sha256, profile_sha256, input_size_bytes, content_type, sampling_fps, status,
						 idempotency_key, request_fingerprint, requested_by, created_at, updated_at, version, hand_result)
						VALUES (?, ?, ?, 'MEDIAPIPE_HAND_LANDMARKER', 'v1', ?, ?, ?, 100, 'video/mp4', 10, ?, ?, ?, ?, ?, ?, 0, ?)
						""",
				jobId, PROJECT_ID, clipAssetId, MODEL_SHA256, SHA256, PROFILE_SHA256, jobStatus, key("ai-job"), SHA256,
				"synthetic", now, now, jobHandResult);

		var successful = "SUCCEEDED".equals(attemptStatus);
		var detectedFrames = candidates.stream().map(CandidateSpec::frameTimestampMs).distinct().count();
		var totalFrames = successful ? 5L : null;
		var inferenceFrames = successful ? 5L : null;
		var framesWithHand = successful && "HAND_DETECTED".equals(attemptHandResult) ? detectedFrames : 0L;
		Long framesWithoutHand = successful ? inferenceFrames - framesWithHand : null;
		Boolean analysisComplete = successful ? Boolean.TRUE : Boolean.FALSE;
		var startedAt = successful ? now : now;
		var completedAt = successful ? Timestamp.from(now.toInstant().plusSeconds(1)) : now;
		var failureReason = "FAILED".equals(attemptStatus) ? "SYNTHETIC_FAILURE" : null;
		this.jdbcTemplate.update("""
				INSERT INTO ai_job_attempt (
				    ai_job_attempt_id, ai_job_id, attempt, message_id, lease_token_hash, status,
				    worker_id, lease_until, updated_at, created_at, analysis_complete, hand_result,
				    started_at, completed_at, total_video_frames, inference_frame_count, frames_with_hand,
				    frames_without_hand, left_hand_detection_count, right_hand_detection_count,
				    invalid_landmark_count, processing_duration_ms, failure_reason, retryable)
				VALUES (?, ?, 1, ?, ?, ?, 'synthetic-ai-worker', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", attemptId, jobId, messageId, LEASE_HASH, attemptStatus,
				Timestamp.from(now.toInstant().plusSeconds(30)), now, now, analysisComplete, attemptHandResult,
				startedAt, completedAt, totalFrames, inferenceFrames, framesWithHand, framesWithoutHand,
				"HAND_DETECTED".equals(attemptHandResult) ? detectedFrames : 0L, 0L, successful ? 0L : 1L,
				successful ? 17L : null, failureReason, "FAILED".equals(attemptStatus));

		if (successful) {
			this.jdbcTemplate.update(
					"INSERT INTO ai_job_result_inbox (message_id, ai_job_id, attempt, result_sha256, received_at) VALUES (?, ?, 1, ?, ?)",
					messageId, jobId, RESULT_SHA256, Timestamp.from(now.toInstant().plusSeconds(2)));
		}
		for (var candidate : candidates) {
			this.jdbcTemplate.update("""
					INSERT INTO candidate_annotation (
					    candidate_annotation_id, ai_job_id, project_id, clip_asset_id, frame_timestamp_ms,
					    hand_index, handedness, handedness_score, bounding_box, landmarks,
					    image_width, image_height, valid_landmark_count, created_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), CAST(? AS JSONB), 1280, 720, 21, ?)
					""", UUID.randomUUID(), jobId, PROJECT_ID, clipAssetId, candidate.frameTimestampMs(),
					candidate.handIndex(), candidate.handedness(), new BigDecimal(candidate.score()),
					"{\"x\":0.1,\"y\":0.2,\"width\":0.2,\"height\":0.3}", landmarksJson(candidate.seed()), now);
		}
	}

	private GroupFixture createGroup(int expectedViewCount, int boundViewCount, String assemblyStatus,
			int qualifiedViewCount) {
		var now = Timestamp.from(BASE_TIME.plusSeconds(Math.floorMod(UUID.randomUUID().hashCode(), 0x7fff)));
		var batchId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO upload_batch (
				    batch_id, project_id, idempotency_key, request_fingerprint, device_id,
				    initiated_by_actor_id, file_count, total_size_bytes, manifest_sha256, status, expires_at,
				    created_at, updated_at, verified_manifest_sha256, completed_at)
				VALUES (?, ?, ?, ?, 'synthetic-group-device', ?, ?, ?, ?, 'COMPLETED', ?, ?, ?, ?, ?)
				""", batchId, PROJECT_ID, key("batch"), SHA256, ProjectMembershipTestData.ADMIN_ACTOR, boundViewCount,
				boundViewCount * 1234L, SHA256, Timestamp.from(now.toInstant().plusSeconds(3600)), now, now, SHA256,
				now);
		this.jdbcTemplate.update("UPDATE upload_batch SET upload_source_type = 'PLATFORM_LOCAL' WHERE batch_id = ?",
				batchId);

		var clipAssetIds = new ArrayList<UUID>();
		var clientFileIds = new ArrayList<UUID>();
		for (var index = 0; index < boundViewCount; index += 1) {
			var uploadFileId = UUID.randomUUID();
			var clientFileId = UUID.randomUUID();
			var clipAssetId = UUID.randomUUID();
			this.jdbcTemplate.update("""
					INSERT INTO upload_file (
					    upload_file_id, batch_id, client_file_id, idempotency_key, request_fingerprint,
					    file_kind, content_type, size_bytes, sha256, part_size_bytes, total_parts,
					    status, created_at, updated_at, storage_bucket, object_key, storage_upload_id,
					    multipart_created_at, completion_started_at, completion_finished_at,
					    verified_sha256, completed_storage_etag)
					VALUES (?, ?, ?, ?, ?, 'VIDEO', 'video/mp4', 1234, ?, 8388608, 1,
					        'UPLOADED', ?, ?, 'synthetic-bucket', ?, ?, ?, ?, ?, ?, 'synthetic-etag')
					""", uploadFileId, batchId, clientFileId, key("file"), SHA256, SHA256, now, now,
					"objects/" + uploadFileId, "multipart-" + uploadFileId, now, now, now, SHA256);
			this.jdbcTemplate.update("""
					INSERT INTO clip_asset (
					    clip_asset_id, project_id, batch_id, source_upload_file_id, client_file_id,
					    content_type, size_bytes, sha256, storage_bucket, object_key, storage_etag,
					    asset_status, deleted_at, deleted_by, delete_reason, created_at, updated_at, version)
					VALUES (?, ?, ?, ?, ?, 'video/mp4', 1234, ?, 'synthetic-bucket', ?, 'synthetic-etag',
					        'INGESTED', NULL, NULL, NULL, ?, ?, 0)
					""", clipAssetId, PROJECT_ID, batchId, uploadFileId, clientFileId, SHA256, "objects/" + clipAssetId,
					now, now);
			VerifiedMediaTestData.insert(this.jdbcTemplate, PROJECT_ID, clipAssetId, SHA256, 10_000_000);
			clipAssetIds.add(clipAssetId);
			clientFileIds.add(clientFileId);
		}

		var cameraSetId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO camera_set (
				    camera_set_id, project_id, camera_set_key, configuration_version,
				    layout_version, request_fingerprint, created_at)
				VALUES (?, ?, ?, 1, 'group-test-v1', ?, ?)
				""", cameraSetId, PROJECT_ID, key("camera-set"), SHA256, now);
		var channelIds = new ArrayList<UUID>();
		for (var index = 0; index < expectedViewCount; index += 1) {
			var channelId = UUID.randomUUID();
			channelIds.add(channelId);
			this.jdbcTemplate.update(
					"INSERT INTO camera_channel (camera_channel_id, project_id, provisioning_key, created_at) VALUES (?, ?, ?, ?)",
					channelId, PROJECT_ID, key("channel"), now);
		}

		var captureGroupId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO capture_session (
				    capture_session_id, project_id, batch_id, device_id, camera_set_id,
				    metadata_upload_file_id, metadata_schema_version, metadata_sha256,
				    expected_camera_count, received_camera_count, sync_group_id,
				    device_time_utc, device_timezone, server_receive_time,
				    assembly_status, quality_status, capture_group_workflow, created_at, updated_at)
				VALUES (?, ?, ?, 'synthetic-group-device', ?, NULL, 'platform-local-upload-v1', NULL,
				        ?, ?, NULL, NULL, NULL, ?, ?, 'PASS', true, ?, ?)
				""", captureGroupId, PROJECT_ID, batchId, cameraSetId, expectedViewCount, boundViewCount, now,
				assemblyStatus, now, now);
		var snapshotId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO camera_set_snapshot (
				    camera_set_snapshot_id, capture_session_id, source_camera_set_id,
				    camera_set_key, configuration_version, layout_version,
				    expected_camera_count, created_at)
				VALUES (?, ?, ?, ?, 1, 'group-test-v1', ?, ?)
				""", snapshotId, captureGroupId, cameraSetId, "snapshot-" + cameraSetId, expectedViewCount, now);
		var cameraStreamIds = new ArrayList<UUID>();
		for (var index = 0; index < expectedViewCount; index += 1) {
			var snapshotMemberId = UUID.randomUUID();
			this.jdbcTemplate.update("""
					INSERT INTO camera_set_snapshot_member (
					    camera_set_snapshot_member_id, camera_set_snapshot_id, camera_channel_id,
					    camera_index, camera_role, mount_position, enabled, enabled_for_hand_detection, created_at)
					VALUES (?, ?, ?, ?, ?, ?, true, true, ?)
					""", snapshotMemberId, snapshotId, channelIds.get(index), index, index == 0 ? "OVERVIEW" : "HANDS",
					"front-" + (index + 1), now);
			if (index < boundViewCount) {
				var cameraStreamId = UUID.randomUUID();
				cameraStreamIds.add(cameraStreamId);
				this.jdbcTemplate.update("""
						INSERT INTO camera_stream (
						    camera_stream_id, capture_session_id, camera_set_snapshot_member_id,
						    camera_channel_id, camera_index, clip_asset_id, client_file_id,
						    record_start_utc, time_offset_ms, declared_sync_status,
						    stream_status, quality_status, created_at, updated_at)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 'PASS', 'INGESTED', 'PASS', ?, ?)
						""", cameraStreamId, captureGroupId, snapshotMemberId, channelIds.get(index), index,
						clipAssetIds.get(index), clientFileIds.get(index), now, now, now);
				var decision = index < qualifiedViewCount ? "QUALIFIED" : "REJECTED";
				var reason = index < qualifiedViewCount ? "MEETS_REQUIREMENTS" : "WRONG_SCENE";
				this.jdbcTemplate.update("""
						INSERT INTO quality_review (
						    quality_review_id, project_id, clip_asset_id, decision, reason_code,
						    reviewed_by, reviewed_at, version)
						VALUES (?, ?, ?, ?, ?, ?, ?, 0)
						""", UUID.randomUUID(), PROJECT_ID, clipAssetIds.get(index), decision, reason,
						ProjectMembershipTestData.MEMBER_ACTOR, now);
			}
		}
		this.jdbcTemplate.update(
				"UPDATE capture_session SET receipt_state = 'COMPLETE', receipt_finalized_at = ?, updated_at = ? WHERE capture_session_id = ?",
				now, now, captureGroupId);
		return new GroupFixture(captureGroupId, clipAssetIds, cameraStreamIds);
	}

	private void clearState() {
		this.jdbcTemplate.update("DELETE FROM action_candidate WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM candidate_annotation WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update(
				"DELETE FROM ai_job_result_inbox WHERE ai_job_id IN (SELECT ai_job_id FROM ai_job WHERE project_id = ?)",
				PROJECT_ID);
		this.jdbcTemplate.update(
				"DELETE FROM ai_job_attempt WHERE ai_job_id IN (SELECT ai_job_id FROM ai_job WHERE project_id = ?)",
				PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM ai_job WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM capture_group_cleaning WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM annotation_review WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM annotation_revision WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.execute(
				"ALTER TABLE annotation_task_view_history DISABLE TRIGGER trg_annotation_task_view_history_append_only");
		try {
			this.jdbcTemplate.update("DELETE FROM annotation_task_view_history WHERE project_id = ?", PROJECT_ID);
		}
		finally {
			this.jdbcTemplate.execute(
					"ALTER TABLE annotation_task_view_history ENABLE TRIGGER trg_annotation_task_view_history_append_only");
		}
		this.jdbcTemplate.update("DELETE FROM annotation_task_view WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM annotation_task WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM capture_group_quality_review WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM quality_review WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update(
				"DELETE FROM camera_stream WHERE capture_session_id IN (SELECT capture_session_id FROM capture_session WHERE project_id = ?)",
				PROJECT_ID);
		this.jdbcTemplate.update(
				"DELETE FROM camera_set_snapshot_member WHERE camera_set_snapshot_id IN (SELECT camera_set_snapshot_id FROM camera_set_snapshot WHERE capture_session_id IN (SELECT capture_session_id FROM capture_session WHERE project_id = ?))",
				PROJECT_ID);
		this.jdbcTemplate.update(
				"DELETE FROM camera_set_snapshot WHERE capture_session_id IN (SELECT capture_session_id FROM capture_session WHERE project_id = ?)",
				PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM capture_session WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update(
				"DELETE FROM camera_set_member WHERE camera_set_id IN (SELECT camera_set_id FROM camera_set WHERE project_id = ?)",
				PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM camera_set WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM camera_channel WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM media_report WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM media_job WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM clip_asset WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update(
				"DELETE FROM upload_file WHERE batch_id IN (SELECT batch_id FROM upload_batch WHERE project_id = ?)",
				PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM upload_batch WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM membership WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM project WHERE project_id = ?", PROJECT_ID);
	}

	private static String landmarksJson(int seed) {
		return IntStream.range(0, 21)
			.mapToObj(index -> "{\"x\":" + decimal(100 + seed + index) + ",\"y\":" + decimal(200 + seed + index * 2)
					+ ",\"z\":-0.01}")
			.collect(Collectors.joining(",", "[", "]"));
	}

	private static String decimal(int thousandths) {
		return BigDecimal.valueOf(thousandths, 3).toPlainString();
	}

	private static String key(String prefix) {
		return prefix + "-" + UUID.randomUUID();
	}

	private record GroupFixture(UUID captureGroupId, List<UUID> clipAssetIds, List<UUID> cameraStreamIds) {

		private GroupFixture {
			clipAssetIds = List.copyOf(clipAssetIds);
			cameraStreamIds = List.copyOf(cameraStreamIds);
		}
	}

	private record CandidateSpec(long frameTimestampMs, int handIndex, String handedness, String score, int seed) {
	}

}
