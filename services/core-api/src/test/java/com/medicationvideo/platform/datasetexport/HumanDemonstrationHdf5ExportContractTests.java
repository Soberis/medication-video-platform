package com.medicationvideo.platform.datasetexport;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import io.jhdf.HdfFile;

import com.jayway.jsonpath.JsonPath;
import com.medicationvideo.platform.support.ProjectMembershipTestData;
import com.medicationvideo.platform.support.VerifiedMediaTestData;

@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@ActiveProfiles("test")
class HumanDemonstrationHdf5ExportContractTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000740");

	private static final UUID OTHER_PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000741");

	private static final String MEMBER_TOKEN = "synthetic-test-bearer-token-do-not-reuse";

	private static final String ADMIN_TOKEN = "synthetic-test-admin-bearer-token-do-not-reuse";

	private static final String SHA256 = "a".repeat(64);

	private static final String MODEL_SHA256 = "b".repeat(64);

	private static final String PROFILE_SHA256 = "c".repeat(64);

	private static final String RESULT_SHA256 = "d".repeat(64);

	private static final String LEASE_HASH = "e".repeat(64);

	private static final Instant BASE_TIME = Instant.parse("2026-08-01T01:00:00Z");

	@Autowired
	private MockMvc mockMvc;

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
	void exportsApprovedHumanLabelsAndCurrentMediapipeFactsAsReadableHdf5() throws Exception {
		var group = createGroup(1, 1, "COMPLETE", 1);
		var ai = insertAiJob(group.clipAssetIds().getFirst(), "SUCCEEDED", "HAND_DETECTED", "SUCCEEDED",
				"HAND_DETECTED", List.of(new CandidateSpec(520, 0, "LEFT", "0.9100", 1)));
		var revisionId = approveRevision(group, 900, "hdf5-export");

		var bytes = this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/human-demonstrations/hdf5/v1/download", PROJECT_ID)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(header().string("Content-Disposition",
					startsWith("attachment; filename=\"human_demonstration_hdf5_v1_project_")))
			.andReturn()
			.getResponse()
			.getContentAsByteArray();
		try (var file = HdfFile.fromBytes(bytes)) {
			assertEquals("human-demonstration-hdf5.v1", file.getAttribute("schema_version").getData());
			assertFalse(file.getChildren().containsKey("states"));
			assertFalse(file.getChildren().containsKey("actions"));
			var episode = file.getByPath("/data/demo_000000");
			assertEquals(group.captureGroupId().toString(), episode.getAttribute("capture_group_id").getData());
			assertEquals(revisionId.toString(), episode.getAttribute("annotation_revision_id").getData());
			assertEquals(SHA256, episode.getAttribute("primary_clip_asset_sha256").getData());
			assertEquals("acquire_item",
					((String[]) file.getDatasetByPath("/data/demo_000000/annotations/label").getData())[0]);
			assertEquals(900L,
					((long[]) file.getDatasetByPath("/data/demo_000000/annotations/segment_end_ms").getData())[0]);
			var candidates = "/data/demo_000000/views/camera_01/candidates/";
			assertEquals(ai.jobId().toString(),
					file.getByPath("/data/demo_000000/views/camera_01").getAttribute("ai_job_id").getData());
			assertEquals(520L, ((long[]) file.getDatasetByPath(candidates + "frame_timestamp_ms").getData())[0]);
			var landmarks = (float[][][]) file.getDatasetByPath(candidates + "landmarks").getData();
			assertEquals(1, landmarks.length);
			assertEquals(21, landmarks[0].length);
			assertEquals(3, landmarks[0][0].length);
		}
	}

	@Test
	void exportsOnlyTheCaptureGroupsSelectedFromTheVideoList() throws Exception {
		var first = createGroup(1, 1, "COMPLETE", 1);
		var second = createGroup(1, 1, "COMPLETE", 1);
		insertAiJob(first.clipAssetIds().getFirst(), "SUCCEEDED", "HAND_DETECTED", "SUCCEEDED", "HAND_DETECTED",
				List.of(new CandidateSpec(100, 0, "LEFT", "0.9", 1)));
		insertAiJob(second.clipAssetIds().getFirst(), "SUCCEEDED", "HAND_DETECTED", "SUCCEEDED", "HAND_DETECTED",
				List.of(new CandidateSpec(200, 0, "RIGHT", "0.9", 2)));
		approveRevision(first, 800, "first-selected-hdf5");
		approveRevision(second, 800, "second-selected-hdf5");

		var bytes = this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/human-demonstrations/hdf5/v1/download", PROJECT_ID)
				.param("capture_group_id", second.captureGroupId().toString())
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Disposition",
					startsWith("attachment; filename=\"human_demonstration_hdf5_v1_event_")))
			.andReturn()
			.getResponse()
			.getContentAsByteArray();
		try (var file = HdfFile.fromBytes(bytes)) {
			assertEquals(1, ((io.jhdf.api.Group) file.getByPath("/data")).getChildren().size());
			assertEquals(second.captureGroupId().toString(),
					file.getByPath("/data/demo_000000").getAttribute("capture_group_id").getData());
		}
	}

	@Test
	void exportsApprovedHumanLabelsAndDualHandTensorInV2Endpoint() throws Exception {
		var group = createGroup(1, 1, "COMPLETE", 1);
		insertAiJob(group.clipAssetIds().getFirst(), "SUCCEEDED", "HAND_DETECTED", "SUCCEEDED", "HAND_DETECTED",
				List.of(new CandidateSpec(520, 0, "LEFT", "0.9100", 1)));
		approveRevision(group, 900, "v2-hdf5-export");

		var bytes = this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/human-demonstrations/hdf5/v2/download", PROJECT_ID)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(header().string("Content-Disposition",
					startsWith("attachment; filename=\"human_demonstration_hdf5_v2_project_")))
			.andReturn()
			.getResponse()
			.getContentAsByteArray();
		try (var file = HdfFile.fromBytes(bytes)) {
			assertEquals("human-demonstration-hdf5.v2", file.getAttribute("schema_version").getData());
			assertEquals(false, file.getAttribute("contains_raw_candidates").getData());
			var episode = (io.jhdf.api.Group) file.getByPath("/data/demo_000000");
			assertFalse(episode.getChildren().containsKey("views"));
			var samples = file.getByPath("/data/demo_000000/samples");
			assertEquals("dual-hand-tensor.v2", samples.getAttribute("layout").getData());
			assertEquals(1280, samples.getAttribute("image_width").getData());
			assertEquals(720, samples.getAttribute("image_height").getData());
			assertEquals(1, samples.getAttribute("step_count").getData());

			var landmarks = (float[][][][]) file.getDatasetByPath("/data/demo_000000/samples/landmarks").getData();
			assertEquals(1, landmarks.length);
			assertEquals(2, landmarks[0].length);
			assertEquals(21, landmarks[0][0].length);
			assertEquals(3, landmarks[0][0][0].length);

			var handMask = (int[][]) file.getDatasetByPath("/data/demo_000000/samples/hand_mask").getData();
			assertEquals(1, handMask[0][0]); // LEFT hand present
			assertEquals(0, handMask[0][1]); // RIGHT hand absent
		}
	}

	private UUID approveRevision(GroupFixture group, int endMs, String prefix) throws Exception {
		recordGroup(group, "QUALIFIED", prefix + "-quality").andExpect(status().isOk());
		this.mockMvc
			.perform(post("/api/v1/projects/{projectId}/capture-groups/{captureGroupId}/annotation-tasks", PROJECT_ID,
					group.captureGroupId())
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", key(prefix + "-assignment"))
				.header("X-Request-Id", key(prefix + "-assignment-request"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"assignee_actor_id\":\"%s\"}".formatted(ProjectMembershipTestData.MEMBER_ACTOR)))
			.andExpect(status().isCreated());
		var taskId = taskId(group);
		selectPrimary(taskId, group.clipAssetIds().getFirst()).andExpect(status().isOk());
		var revision = submit(taskId, prefix + "-submit", endMs).andExpect(status().isCreated()).andReturn();
		var revisionId = UUID
			.fromString(JsonPath.read(revision.getResponse().getContentAsString(), "$.annotation_revision_id"));
		this.mockMvc.perform(
				post("/api/v1/projects/{projectId}/annotation-revisions/{revisionId}/review", PROJECT_ID, revisionId)
					.header("Authorization", "Bearer " + ADMIN_TOKEN)
					.header("Idempotency-Key", key(prefix + "-review"))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"decision\":\"APPROVED\"}"))
			.andExpect(status().isCreated());
		return revisionId;
	}

	private ResultActions recordGroup(GroupFixture group, String result, String prefix) throws Exception {
		return this.mockMvc.perform(put("/api/v1/projects/{projectId}/capture-groups/{captureGroupId}/quality-review",
				PROJECT_ID, group.captureGroupId())
			.header("Authorization", "Bearer " + MEMBER_TOKEN)
			.header("Idempotency-Key", key(prefix))
			.header("X-Request-Id", key(prefix + "-request"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"result\":\"%s\",\"reason\":\"Synthetic test\",\"expected_revision\":0}".formatted(result)));
	}

	private ResultActions selectPrimary(UUID taskId, UUID clipAssetId) throws Exception {
		return this.mockMvc
			.perform(put("/api/v1/projects/{projectId}/annotation-tasks/{taskId}/primary-view", PROJECT_ID, taskId)
				.header("Authorization", "Bearer " + MEMBER_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"clip_asset_id\":\"%s\"}".formatted(clipAssetId)));
	}

	private ResultActions submit(UUID taskId, String prefix, int endMs) throws Exception {
		return this.mockMvc
			.perform(post("/api/v1/projects/{projectId}/annotation-tasks/{taskId}/revisions", PROJECT_ID, taskId)
				.header("Authorization", "Bearer " + MEMBER_TOKEN)
				.header("Idempotency-Key", key(prefix))
				.header("X-Request-Id", key(prefix + "-request"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(
						"{\"segments\":[{\"start_ms\":0,\"end_ms\":%d,\"label\":\"acquire_item\"}]}".formatted(endMs)));
	}

	private UUID taskId(GroupFixture group) {
		return this.jdbcTemplate.queryForObject(
				"SELECT annotation_task_id FROM annotation_task WHERE project_id = ? AND capture_group_id = ?",
				UUID.class, PROJECT_ID, group.captureGroupId());
	}

	private UUID candidateId(UUID jobId) {
		return this.jdbcTemplate.queryForObject(
				"SELECT candidate_annotation_id FROM candidate_annotation WHERE ai_job_id = ?", UUID.class, jobId);
	}

	private AiFixture insertAiJob(UUID clipAssetId, String jobStatus, String jobHandResult, String attemptStatus,
			String attemptHandResult, List<CandidateSpec> candidates) {
		var jobId = UUID.randomUUID();
		var attemptId = UUID.randomUUID();
		var messageId = UUID.randomUUID();
		var now = BASE_TIME.plusSeconds(UUID.randomUUID().hashCode() & 0x7fff);
		this.jdbcTemplate.update(
				"""
						INSERT INTO ai_job (ai_job_id, project_id, clip_asset_id, algorithm_key, algorithm_version,
						 model_sha256, input_sha256, profile_sha256, input_size_bytes, content_type, sampling_fps, status,
						 idempotency_key, request_fingerprint, requested_by, created_at, updated_at, version, hand_result)
						VALUES (?, ?, ?, 'MEDIAPIPE_HAND_LANDMARKER', 'v1', ?, ?, ?, 100, 'video/mp4', 10, ?, ?, ?, ?, ?, ?, 0, ?)
						""",
				jobId, PROJECT_ID, clipAssetId, MODEL_SHA256, SHA256, PROFILE_SHA256, jobStatus, key("ai-job"), SHA256,
				"synthetic", Timestamp.from(now), Timestamp.from(now), jobHandResult);

		var successful = "SUCCEEDED".equals(attemptStatus);
		var running = "RUNNING".equals(attemptStatus);
		var detectedFrames = candidates.stream().map(CandidateSpec::frameTimestampMs).distinct().count();
		var leftHands = candidates.stream().filter(candidate -> "LEFT".equals(candidate.handedness())).count();
		var rightHands = candidates.stream().filter(candidate -> "RIGHT".equals(candidate.handedness())).count();
		Boolean analysisComplete;
		if (successful) {
			analysisComplete = Boolean.TRUE;
		}
		else if (running) {
			analysisComplete = null;
		}
		else {
			analysisComplete = Boolean.FALSE;
		}
		var startedAt = successful || !running ? Timestamp.from(now) : null;
		var completedAt = successful || !running ? Timestamp.from(now.plusSeconds(1)) : null;
		var totalFrames = successful || !running ? 5L : null;
		var inferenceFrames = successful || !running ? 5L : null;
		var framesWithHand = successful && "HAND_DETECTED".equals(attemptHandResult) ? detectedFrames
				: successful ? 0L : 0L;
		Long framesWithoutHand;
		if (successful) {
			framesWithoutHand = inferenceFrames - framesWithHand;
		}
		else if (running) {
			framesWithoutHand = null;
		}
		else {
			framesWithoutHand = 0L;
		}
		var failureReason = "FAILED".equals(attemptStatus) ? "SYNTHETIC_FAILURE" : null;
		this.jdbcTemplate.update("""
				INSERT INTO ai_job_attempt (
				    ai_job_attempt_id, ai_job_id, attempt, message_id, lease_token_hash, status,
				    worker_id, lease_until, updated_at, created_at, analysis_complete, hand_result,
				    started_at, completed_at, total_video_frames, inference_frame_count, frames_with_hand,
				    frames_without_hand, left_hand_detection_count, right_hand_detection_count,
				    invalid_landmark_count, processing_duration_ms, failure_reason, retryable)
				VALUES (?, ?, 1, ?, ?, ?, 'synthetic-ai-worker', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", attemptId, jobId, messageId, LEASE_HASH, attemptStatus, Timestamp.from(now.plusSeconds(30)),
				Timestamp.from(now), Timestamp.from(now), analysisComplete, attemptHandResult, startedAt, completedAt,
				totalFrames, inferenceFrames, framesWithHand, framesWithoutHand,
				"HAND_DETECTED".equals(attemptHandResult) ? leftHands : 0L,
				"HAND_DETECTED".equals(attemptHandResult) ? rightHands : 0L, successful ? 0L : 1L,
				successful || !running ? 17L : null, failureReason, "FAILED".equals(attemptStatus));

		if (successful) {
			this.jdbcTemplate.update(
					"INSERT INTO ai_job_result_inbox (message_id, ai_job_id, attempt, result_sha256, received_at) VALUES (?, ?, 1, ?, ?)",
					messageId, jobId, RESULT_SHA256, Timestamp.from(now.plusSeconds(2)));
		}
		for (var candidate : candidates) {
			var candidateId = UUID.randomUUID();
			this.jdbcTemplate.update("""
					INSERT INTO candidate_annotation (
					    candidate_annotation_id, ai_job_id, project_id, clip_asset_id, frame_timestamp_ms,
					    hand_index, handedness, handedness_score, bounding_box, landmarks,
					    image_width, image_height, valid_landmark_count, created_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), CAST(? AS JSONB), 1280, 720, 21, ?)
					""", candidateId, jobId, PROJECT_ID, clipAssetId, candidate.frameTimestampMs(),
					candidate.handIndex(), candidate.handedness(), new BigDecimal(candidate.score()),
					"{\"x\":0.1,\"y\":0.2,\"width\":0.2,\"height\":0.3}", landmarksJson(candidate.seed()),
					Timestamp.from(now));
		}
		return new AiFixture(jobId, attemptId, messageId);
	}

	private GroupFixture createGroup(int expectedViewCount, int boundViewCount, String assemblyStatus,
			int qualifiedViewCount) {
		var now = BASE_TIME.plusSeconds(UUID.randomUUID().hashCode() & 0x7fff);
		var batchId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO upload_batch (
				    batch_id, project_id, idempotency_key, request_fingerprint, device_id,
				    initiated_by_actor_id, file_count, total_size_bytes, manifest_sha256, status, expires_at,
				    created_at, updated_at, verified_manifest_sha256, completed_at)
				VALUES (?, ?, ?, ?, 'synthetic-group-device', ?, ?, ?, ?, 'COMPLETED', ?, ?, ?, ?, ?)
				""", batchId, PROJECT_ID, key("batch"), SHA256, ProjectMembershipTestData.ADMIN_ACTOR, boundViewCount,
				boundViewCount * 1234L, SHA256, Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now),
				Timestamp.from(now), SHA256, Timestamp.from(now));
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
					""", uploadFileId, batchId, clientFileId, key("file"), SHA256, SHA256, Timestamp.from(now),
					Timestamp.from(now), "objects/" + uploadFileId, "multipart-" + uploadFileId, Timestamp.from(now),
					Timestamp.from(now), Timestamp.from(now), SHA256);
			this.jdbcTemplate.update("""
					INSERT INTO clip_asset (
					    clip_asset_id, project_id, batch_id, source_upload_file_id, client_file_id,
					    content_type, size_bytes, sha256, storage_bucket, object_key, storage_etag,
					    asset_status, deleted_at, deleted_by, delete_reason, created_at, updated_at, version)
					VALUES (?, ?, ?, ?, ?, 'video/mp4', 1234, ?, 'synthetic-bucket', ?, 'synthetic-etag',
					        'INGESTED', NULL, NULL, NULL, ?, ?, 0)
					""", clipAssetId, PROJECT_ID, batchId, uploadFileId, clientFileId, SHA256, "objects/" + clipAssetId,
					Timestamp.from(now), Timestamp.from(now));
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
				""", cameraSetId, PROJECT_ID, key("camera-set"), SHA256, Timestamp.from(now));
		var channelIds = new ArrayList<UUID>();
		for (var index = 0; index < expectedViewCount; index += 1) {
			var channelId = UUID.randomUUID();
			channelIds.add(channelId);
			this.jdbcTemplate.update(
					"INSERT INTO camera_channel (camera_channel_id, project_id, provisioning_key, created_at) VALUES (?, ?, ?, ?)",
					channelId, PROJECT_ID, key("channel"), Timestamp.from(now));
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
				""", captureGroupId, PROJECT_ID, batchId, cameraSetId, expectedViewCount, boundViewCount,
				Timestamp.from(now), assemblyStatus, Timestamp.from(now), Timestamp.from(now));
		var snapshotId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO camera_set_snapshot (
				    camera_set_snapshot_id, capture_session_id, source_camera_set_id,
				    camera_set_key, configuration_version, layout_version,
				    expected_camera_count, created_at)
				VALUES (?, ?, ?, ?, 1, 'group-test-v1', ?, ?)
				""", snapshotId, captureGroupId, cameraSetId, "snapshot-" + cameraSetId, expectedViewCount,
				Timestamp.from(now));
		var cameraStreamIds = new ArrayList<UUID>();
		for (var index = 0; index < expectedViewCount; index += 1) {
			var snapshotMemberId = UUID.randomUUID();
			this.jdbcTemplate.update("""
					INSERT INTO camera_set_snapshot_member (
					    camera_set_snapshot_member_id, camera_set_snapshot_id, camera_channel_id,
					    camera_index, camera_role, mount_position, enabled, enabled_for_hand_detection, created_at)
					VALUES (?, ?, ?, ?, ?, ?, true, true, ?)
					""", snapshotMemberId, snapshotId, channelIds.get(index), index, index == 0 ? "OVERVIEW" : "HANDS",
					"front-" + (index + 1), Timestamp.from(now));
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
						clipAssetIds.get(index), clientFileIds.get(index), Timestamp.from(now), Timestamp.from(now),
						Timestamp.from(now));
				var decision = index < qualifiedViewCount ? "QUALIFIED" : "REJECTED";
				var reason = index < qualifiedViewCount ? "MEETS_REQUIREMENTS" : "WRONG_SCENE";
				this.jdbcTemplate.update("""
						INSERT INTO quality_review (
						    quality_review_id, project_id, clip_asset_id, decision, reason_code,
						    reviewed_by, reviewed_at, version)
						VALUES (?, ?, ?, ?, ?, ?, ?, 0)
						""", UUID.randomUUID(), PROJECT_ID, clipAssetIds.get(index), decision, reason,
						ProjectMembershipTestData.MEMBER_ACTOR, Timestamp.from(now));
			}
		}
		this.jdbcTemplate.update(
				"UPDATE capture_session SET receipt_state = 'COMPLETE', receipt_finalized_at = ?, updated_at = ? WHERE capture_session_id = ?",
				Timestamp.from(now), Timestamp.from(now), captureGroupId);
		return new GroupFixture(captureGroupId, clipAssetIds, cameraStreamIds);
	}

	private void clearState() {
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

	private record AiFixture(UUID jobId, UUID attemptId, UUID messageId) {
	}

	private record CandidateSpec(long frameTimestampMs, int handIndex, String handedness, String score, int seed) {
	}

}
