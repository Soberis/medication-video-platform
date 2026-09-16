package com.medicationvideo.platform.aicandidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.medicationvideo.platform.support.AiHandPoseResultFixture;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiHandPoseResultContractTests {

	private static final String AI_TOKEN = "synthetic-ai-worker-bearer-token-do-not-reuse";

	private static final String MODEL_SHA256 = "b".repeat(64);

	private static final String INPUT_SHA256 = "a".repeat(64);

	private UUID lastInsertedClipAssetId;

	private UUID lastInsertedProjectId;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AiCandidateJobRepository jobRepository;

	@Autowired
	private AiCandidateJobAttemptRepository attemptRepository;

	@Test
	@org.springframework.transaction.annotation.Transactional
	void actionResultPersistsOptionalConfidenceAndContextWithoutSubmittingAnnotations() {
		var jobId = UUID.randomUUID();
		var messageId = UUID.randomUUID();
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var lease = "A".repeat(43);
		insertRunningJob(jobId, UUID.randomUUID(), messageId, AiLeaseCapabilityFactory.sha256(lease), now);
		var taskId = UUID.randomUUID();
		var schemaId = UUID.randomUUID();
		var groupId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				UPDATE ai_job SET algorithm_key = 'QWEN3_VL_ACTION', annotation_task_id = ?,
				label_schema_id = ?, label_schema_digest = ?, allowed_labels = '["draw_liquid"]'::jsonb
				WHERE ai_job_id = ?
				""", taskId, schemaId, "c".repeat(64), jobId);
		var assets = org.mockito.Mockito.mock(com.medicationvideo.platform.assetcapture.AssetCatalogQueryService.class);
		org.mockito.Mockito.when(assets.resolveCaptureGroupId(this.lastInsertedClipAssetId)).thenReturn(groupId);
		var service = new ActionCandidateJobService(this.jobRepository, this.attemptRepository, this.jdbcTemplate,
				new tools.jackson.databind.ObjectMapper(), null, assets, null, null, null, null, null);
		var revisionCount = this.jdbcTemplate.queryForObject("SELECT count(*) FROM annotation_revision", Long.class);
		var request = new ActionCandidateResultRequest(messageId, "synthetic-action-model", "synthetic-v1", 1, 19000L,
				1L, now, now,
				List.of(new ActionCandidateSegmentItem(0L, 100L, "draw_liquid", null, 1, List.of(1), "vial")));
		service.acceptResult(jobId, 1, lease, request, "synthetic-ai-worker");
		service.acceptResult(jobId, 1, lease, request, "synthetic-ai-worker");
		this.jobRepository.flush();
		var candidates = service.listByCaptureGroup(this.lastInsertedProjectId, groupId, this.lastInsertedClipAssetId);
		assertEquals(1, candidates.size());
		assertEquals(taskId, candidates.getFirst().annotationTaskId());
		org.junit.jupiter.api.Assertions.assertNull(candidates.getFirst().confidence());
		assertEquals("vial", candidates.getFirst().targetObject());
		assertEquals("synthetic-action-model", this.jdbcTemplate.queryForObject(
				"SELECT execution_metadata->>'model_name' FROM ai_job WHERE ai_job_id = ?", String.class, jobId));
		assertEquals(revisionCount,
				this.jdbcTemplate.queryForObject("SELECT count(*) FROM annotation_revision", Long.class));
		this.jdbcTemplate.update("DELETE FROM action_candidate WHERE ai_job_id = ?", jobId);
	}

	@BeforeEach
	void clearState() {
		this.jdbcTemplate.update("DELETE FROM candidate_annotation");
		this.jdbcTemplate.update("DELETE FROM ai_job_result_inbox");
		this.jdbcTemplate.update("DELETE FROM ai_job_attempt");
		this.jdbcTemplate.update("DELETE FROM ai_job");
		this.jdbcTemplate.update("DELETE FROM clip_asset WHERE object_key LIKE 'ai-results/%'");
		this.jdbcTemplate.update("DELETE FROM upload_file WHERE idempotency_key LIKE 'ai-result-file-%'");
		this.jdbcTemplate.update("DELETE FROM upload_batch WHERE idempotency_key LIKE 'ai-result-batch-%'");
	}

	@AfterEach
	void cleanUpState() {
		clearState();
	}

	@Test
	void latestHandPoseJobIgnoresANewerActionJobForTheSameClip() {
		var handJobId = UUID.randomUUID();
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		insertRunningJob(handJobId, UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), now);
		this.jdbcTemplate.update(
				"UPDATE ai_job SET status = 'SUCCEEDED', hand_result = 'HAND_DETECTED' WHERE ai_job_id = ?", handJobId);
		this.jobRepository.save(AiCandidateJob.queueAction(this.lastInsertedProjectId, this.lastInsertedClipAssetId,
				MODEL_SHA256, INPUT_SHA256, 100, "video/mp4", "action-query-key-0001", "c".repeat(64), "actor",
				now.plusSeconds(1)));

		var latestHandJob = this.jobRepository
			.findTopByProjectIdAndClipAssetIdAndAlgorithmKeyOrderByCreatedAtDesc(this.lastInsertedProjectId,
					this.lastInsertedClipAssetId, HandPoseProfile.ALGORITHM_KEY)
			.orElseThrow();

		assertEquals(handJobId, latestHandJob.getAiJobId());
		assertEquals(HandPoseResult.HAND_DETECTED, latestHandJob.getHandResult());
	}

	@Test
	void persistsAnExplicitNoHandSuccessAndItsRunSummaryWithoutCreatingFormalAnnotations() throws Exception {
		var jobId = UUID.randomUUID();
		var attempt = UUID.randomUUID();
		var messageId = UUID.randomUUID();
		var lease = "A".repeat(43);
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		insertRunningJob(jobId, attempt, messageId, AiLeaseCapabilityFactory.sha256(lease), now);

		var timestamp = now.toString();
		var result = """
				{"schema_version":"ai-hand-pose-result.v2","message_id":"%s","job_id":"%s","attempt":1,
				"lease_token":"%s","input_sha256":"%s","model_sha256":"%s","profile_sha256":"%s",
				"started_at":"%s","completed_at":"%s","outcome":"SUCCEEDED","analysis_complete":true,
				"hand_result":"NO_HAND_DETECTED","total_video_frames":6,"inference_frame_count":3,
				"frames_with_hand":0,"frames_without_hand":3,"left_hand_detection_count":0,
				"right_hand_detection_count":0,"invalid_landmark_count":0,"processing_duration_ms":17,
				"failure_reason":null,"candidates":[]}
				""".formatted(messageId, jobId, lease, INPUT_SHA256, MODEL_SHA256, HandPoseProfile.PROFILE_SHA256,
				timestamp, timestamp);

		this.mockMvc
			.perform(put("/api/v1/internal/ai-jobs/{jobId}/attempts/1/result", jobId)
				.header("Authorization", "Bearer " + AI_TOKEN)
				.header("X-AI-Lease-Token", lease)
				.contentType(MediaType.APPLICATION_JSON)
				.content(result))
			.andExpect(status().isNoContent());

		var jobState = this.jdbcTemplate.queryForMap("SELECT status, hand_result FROM ai_job WHERE ai_job_id = ?",
				jobId);
		var attemptState = this.jdbcTemplate.queryForMap("""
				SELECT status, analysis_complete, hand_result, total_video_frames, inference_frame_count,
				       frames_with_hand, frames_without_hand, processing_duration_ms
				FROM ai_job_attempt WHERE ai_job_attempt_id = ?
				""", attempt);
		org.junit.jupiter.api.Assertions.assertEquals("SUCCEEDED", jobState.get("status"));
		org.junit.jupiter.api.Assertions.assertEquals("NO_HAND_DETECTED", jobState.get("hand_result"));
		org.junit.jupiter.api.Assertions.assertEquals("SUCCEEDED", attemptState.get("status"));
		org.junit.jupiter.api.Assertions.assertEquals(true, attemptState.get("analysis_complete"));
		org.junit.jupiter.api.Assertions.assertEquals("NO_HAND_DETECTED", attemptState.get("hand_result"));
		org.junit.jupiter.api.Assertions.assertEquals(3L,
				((Number) attemptState.get("inference_frame_count")).longValue());
		org.junit.jupiter.api.Assertions.assertEquals(0, this.jdbcTemplate
			.queryForObject("SELECT count(*) FROM candidate_annotation WHERE ai_job_id = ?", Integer.class, jobId));
		assertEquals(0,
				this.jdbcTemplate.queryForObject("SELECT count(*) FROM annotation_revision WHERE clip_asset_id = ?",
						Integer.class, this.lastInsertedClipAssetId));
		assertNoFormalWorkflowWrites();
	}

	@Test
	void rejectsSuccessfulResultsThatContainNoDecodedOrSampledFrames() throws Exception {
		var jobId = UUID.randomUUID();
		var attempt = UUID.randomUUID();
		var messageId = UUID.randomUUID();
		var lease = "A".repeat(43);
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		insertRunningJob(jobId, attempt, messageId, AiLeaseCapabilityFactory.sha256(lease), now);
		var timestamp = now.toString();
		var result = """
				{"schema_version":"ai-hand-pose-result.v2","message_id":"%s","job_id":"%s","attempt":1,
				"lease_token":"%s","input_sha256":"%s","model_sha256":"%s","profile_sha256":"%s",
				"started_at":"%s","completed_at":"%s","outcome":"SUCCEEDED","analysis_complete":true,
				"hand_result":"NO_HAND_DETECTED","total_video_frames":0,"inference_frame_count":0,
				"frames_with_hand":0,"frames_without_hand":0,"left_hand_detection_count":0,
				"right_hand_detection_count":0,"invalid_landmark_count":0,"processing_duration_ms":0,
				"failure_reason":null,"candidates":[]}
				""".formatted(messageId, jobId, lease, INPUT_SHA256, MODEL_SHA256, HandPoseProfile.PROFILE_SHA256,
				timestamp, timestamp);

		this.mockMvc
			.perform(put("/api/v1/internal/ai-jobs/{jobId}/attempts/1/result", jobId)
				.header("Authorization", "Bearer " + AI_TOKEN)
				.header("X-AI-Lease-Token", lease)
				.contentType(MediaType.APPLICATION_JSON)
				.content(result))
			.andExpect(status().isBadRequest());

		assertEquals("RUNNING",
				this.jdbcTemplate.queryForObject("SELECT status FROM ai_job WHERE ai_job_id = ?", String.class, jobId));
	}

	@Test
	void persistsAnExplicitHandDetectedSuccessWithACompleteTwentyOnePointCandidate() throws Exception {
		var jobId = UUID.randomUUID();
		var attempt = UUID.randomUUID();
		var messageId = UUID.randomUUID();
		var lease = "A".repeat(43);
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		insertRunningJob(jobId, attempt, messageId, AiLeaseCapabilityFactory.sha256(lease), now);

		var timestamp = now.toString();
		var result = """
				{"schema_version":"ai-hand-pose-result.v2","message_id":"%s","job_id":"%s","attempt":1,
				"lease_token":"%s","input_sha256":"%s","model_sha256":"%s","profile_sha256":"%s",
				"started_at":"%s","completed_at":"%s","outcome":"SUCCEEDED","analysis_complete":true,
				"hand_result":"HAND_DETECTED","total_video_frames":6,"inference_frame_count":3,
				"frames_with_hand":1,"frames_without_hand":2,"left_hand_detection_count":1,
				"right_hand_detection_count":0,"invalid_landmark_count":0,"processing_duration_ms":17,
				"failure_reason":null,"candidates":[{"frame_timestamp_ms":0,"image_width":1280,
				"image_height":720,"hand_index":0,"handedness":"LEFT","handedness_score":0.91,
				"bounding_box":{"x":0.1,"y":0.2,"width":0.2,"height":0.3},"landmarks":[%s],
				"valid_landmark_count":21}]}
				""".formatted(messageId, jobId, lease, INPUT_SHA256, MODEL_SHA256, HandPoseProfile.PROFILE_SHA256,
				timestamp, timestamp, landmarksJson());

		this.mockMvc
			.perform(put("/api/v1/internal/ai-jobs/{jobId}/attempts/1/result", jobId)
				.header("Authorization", "Bearer " + AI_TOKEN)
				.header("X-AI-Lease-Token", lease)
				.contentType(MediaType.APPLICATION_JSON)
				.content(result))
			.andExpect(status().isNoContent());

		assertEquals("SUCCEEDED",
				this.jdbcTemplate.queryForObject("SELECT status FROM ai_job WHERE ai_job_id = ?", String.class, jobId));
		assertEquals("HAND_DETECTED", this.jdbcTemplate
			.queryForObject("SELECT hand_result FROM ai_job WHERE ai_job_id = ?", String.class, jobId));
		assertEquals(1, this.jdbcTemplate
			.queryForObject("SELECT count(*) FROM candidate_annotation WHERE ai_job_id = ?", Integer.class, jobId));
		assertEquals(21, this.jdbcTemplate.queryForObject(
				"SELECT valid_landmark_count FROM candidate_annotation WHERE ai_job_id = ?", Integer.class, jobId));
		assertEquals(0,
				this.jdbcTemplate.queryForObject("SELECT count(*) FROM annotation_revision WHERE clip_asset_id = ?",
						Integer.class, this.lastInsertedClipAssetId));
		assertNoFormalWorkflowWrites();
	}

	@Test
	void acceptsUnknownHandednessAsAuxiliaryCandidateMetadataWithoutCreatingFormalAnnotation() throws Exception {
		var jobId = UUID.randomUUID();
		var attempt = UUID.randomUUID();
		var messageId = UUID.randomUUID();
		var lease = "A".repeat(43);
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		insertRunningJob(jobId, attempt, messageId, AiLeaseCapabilityFactory.sha256(lease), now);

		var timestamp = now.toString();
		var result = """
				{"schema_version":"ai-hand-pose-result.v2","message_id":"%s","job_id":"%s","attempt":1,
				"lease_token":"%s","input_sha256":"%s","model_sha256":"%s","profile_sha256":"%s",
				"started_at":"%s","completed_at":"%s","outcome":"SUCCEEDED","analysis_complete":true,
				"hand_result":"HAND_DETECTED","total_video_frames":6,"inference_frame_count":3,
				"frames_with_hand":1,"frames_without_hand":2,"left_hand_detection_count":0,
				"right_hand_detection_count":0,"invalid_landmark_count":0,"processing_duration_ms":17,
				"failure_reason":null,"candidates":[{"frame_timestamp_ms":0,"image_width":1280,
				"image_height":720,"hand_index":0,"handedness":"UNKNOWN","handedness_score":0.91,
				"bounding_box":{"x":0.1,"y":0.2,"width":0.2,"height":0.3},"landmarks":[%s],
				"valid_landmark_count":21}]}
				""".formatted(messageId, jobId, lease, INPUT_SHA256, MODEL_SHA256, HandPoseProfile.PROFILE_SHA256,
				timestamp, timestamp, landmarksJson());

		this.mockMvc
			.perform(put("/api/v1/internal/ai-jobs/{jobId}/attempts/1/result", jobId)
				.header("Authorization", "Bearer " + AI_TOKEN)
				.header("X-AI-Lease-Token", lease)
				.contentType(MediaType.APPLICATION_JSON)
				.content(result))
			.andExpect(status().isNoContent());

		assertEquals("UNKNOWN", this.jdbcTemplate
			.queryForObject("SELECT handedness FROM candidate_annotation WHERE ai_job_id = ?", String.class, jobId));
		assertEquals(0,
				this.jdbcTemplate.queryForObject("SELECT count(*) FROM annotation_revision WHERE clip_asset_id = ?",
						Integer.class, this.lastInsertedClipAssetId));
	}

	@Test
	void persistsFailedResultsAsUnknownAndDoesNotInferNoHandFromAnEmptyCandidateList() throws Exception {
		var jobId = UUID.randomUUID();
		var attempt = UUID.randomUUID();
		var messageId = UUID.randomUUID();
		var lease = "A".repeat(43);
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		insertRunningJob(jobId, attempt, messageId, AiLeaseCapabilityFactory.sha256(lease), now);

		var timestamp = now.toString();
		var result = """
				{"schema_version":"ai-hand-pose-result.v2","message_id":"%s","job_id":"%s","attempt":1,
				"lease_token":"%s","input_sha256":"%s","model_sha256":"%s","profile_sha256":"%s",
				"started_at":"%s","completed_at":"%s","outcome":"FAILED","analysis_complete":false,
				"hand_result":"UNKNOWN","total_video_frames":6,"inference_frame_count":3,
				"frames_with_hand":0,"frames_without_hand":3,"left_hand_detection_count":0,
				"right_hand_detection_count":0,"invalid_landmark_count":2,"processing_duration_ms":17,
				"failure_reason":"AI_HAND_POSE_INVALID_OUTPUT","candidates":[]}
				""".formatted(messageId, jobId, lease, INPUT_SHA256, MODEL_SHA256, HandPoseProfile.PROFILE_SHA256,
				timestamp, timestamp);

		this.mockMvc
			.perform(put("/api/v1/internal/ai-jobs/{jobId}/attempts/1/result", jobId)
				.header("Authorization", "Bearer " + AI_TOKEN)
				.header("X-AI-Lease-Token", lease)
				.contentType(MediaType.APPLICATION_JSON)
				.content(result))
			.andExpect(status().isNoContent());

		assertEquals("FAILED",
				this.jdbcTemplate.queryForObject("SELECT status FROM ai_job WHERE ai_job_id = ?", String.class, jobId));
		assertEquals("UNKNOWN", this.jdbcTemplate.queryForObject("SELECT hand_result FROM ai_job WHERE ai_job_id = ?",
				String.class, jobId));
		assertEquals(true, this.jdbcTemplate.queryForObject(
				"SELECT retryable FROM ai_job_attempt WHERE ai_job_attempt_id = ?", Boolean.class, attempt));
		assertEquals(0, this.jdbcTemplate
			.queryForObject("SELECT count(*) FROM candidate_annotation WHERE ai_job_id = ?", Integer.class, jobId));
		assertNoFormalWorkflowWrites();
	}

	@Test
	void acceptsFailedResultsWithValidHandCountsAndInvalidFrameGaps() throws Exception {
		var jobId = UUID.randomUUID();
		var attempt = UUID.randomUUID();
		var messageId = UUID.randomUUID();
		var lease = "A".repeat(43);
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		insertRunningJob(jobId, attempt, messageId, AiLeaseCapabilityFactory.sha256(lease), now);

		var timestamp = now.toString();
		var result = """
				{"schema_version":"ai-hand-pose-result.v2","message_id":"%s","job_id":"%s","attempt":1,
				"lease_token":"%s","input_sha256":"%s","model_sha256":"%s","profile_sha256":"%s",
				"started_at":"%s","completed_at":"%s","outcome":"FAILED","analysis_complete":false,
				"hand_result":"UNKNOWN","total_video_frames":6,"inference_frame_count":3,
				"frames_with_hand":1,"frames_without_hand":1,"left_hand_detection_count":1,
				"right_hand_detection_count":0,"invalid_landmark_count":1,"processing_duration_ms":17,
				"failure_reason":"AI_HAND_POSE_INVALID_OUTPUT","candidates":[]}
				""".formatted(messageId, jobId, lease, INPUT_SHA256, MODEL_SHA256, HandPoseProfile.PROFILE_SHA256,
				timestamp, timestamp);

		this.mockMvc
			.perform(put("/api/v1/internal/ai-jobs/{jobId}/attempts/1/result", jobId)
				.header("Authorization", "Bearer " + AI_TOKEN)
				.header("X-AI-Lease-Token", lease)
				.contentType(MediaType.APPLICATION_JSON)
				.content(result))
			.andExpect(status().isNoContent());

		assertEquals("FAILED",
				this.jdbcTemplate.queryForObject("SELECT status FROM ai_job WHERE ai_job_id = ?", String.class, jobId));
	}

	@Test
	void rejectsFailedFrameCountOverflowInsteadOfAcceptingWrappedLongAddition() throws Exception {
		var jobId = UUID.randomUUID();
		var attempt = UUID.randomUUID();
		var messageId = UUID.randomUUID();
		var lease = "A".repeat(43);
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		insertRunningJob(jobId, attempt, messageId, AiLeaseCapabilityFactory.sha256(lease), now);

		var timestamp = now.toString();
		var result = """
				{"schema_version":"ai-hand-pose-result.v2","message_id":"%s","job_id":"%s","attempt":1,
				"lease_token":"%s","input_sha256":"%s","model_sha256":"%s","profile_sha256":"%s",
				"started_at":"%s","completed_at":"%s","outcome":"FAILED","analysis_complete":false,
				"hand_result":"UNKNOWN","total_video_frames":%d,"inference_frame_count":%d,
				"frames_with_hand":%d,"frames_without_hand":%d,"left_hand_detection_count":0,
				"right_hand_detection_count":0,"invalid_landmark_count":0,"processing_duration_ms":17,
				"failure_reason":"AI_HAND_POSE_INVALID_OUTPUT","candidates":[]}
				""".formatted(messageId, jobId, lease, INPUT_SHA256, MODEL_SHA256, HandPoseProfile.PROFILE_SHA256,
				timestamp, timestamp, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);

		this.mockMvc
			.perform(put("/api/v1/internal/ai-jobs/{jobId}/attempts/1/result", jobId)
				.header("Authorization", "Bearer " + AI_TOKEN)
				.header("X-AI-Lease-Token", lease)
				.contentType(MediaType.APPLICATION_JSON)
				.content(result))
			.andExpect(status().isBadRequest());

		assertEquals("RUNNING",
				this.jdbcTemplate.queryForObject("SELECT status FROM ai_job WHERE ai_job_id = ?", String.class, jobId));
	}

	@Test
	void rejectsFailedHandCountsAboveTwoHandsPerObservedFrame() throws Exception {
		var jobId = UUID.randomUUID();
		var attempt = UUID.randomUUID();
		var messageId = UUID.randomUUID();
		var lease = "A".repeat(43);
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		insertRunningJob(jobId, attempt, messageId, AiLeaseCapabilityFactory.sha256(lease), now);

		var timestamp = now.toString();
		var result = """
				{"schema_version":"ai-hand-pose-result.v2","message_id":"%s","job_id":"%s","attempt":1,
				"lease_token":"%s","input_sha256":"%s","model_sha256":"%s","profile_sha256":"%s",
				"started_at":"%s","completed_at":"%s","outcome":"FAILED","analysis_complete":false,
				"hand_result":"UNKNOWN","total_video_frames":2,"inference_frame_count":1,
				"frames_with_hand":1,"frames_without_hand":0,"left_hand_detection_count":2,
				"right_hand_detection_count":1,"invalid_landmark_count":0,"processing_duration_ms":17,
				"failure_reason":"AI_HAND_POSE_INVALID_OUTPUT","candidates":[]}
				""".formatted(messageId, jobId, lease, INPUT_SHA256, MODEL_SHA256, HandPoseProfile.PROFILE_SHA256,
				timestamp, timestamp);

		this.mockMvc
			.perform(put("/api/v1/internal/ai-jobs/{jobId}/attempts/1/result", jobId)
				.header("Authorization", "Bearer " + AI_TOKEN)
				.header("X-AI-Lease-Token", lease)
				.contentType(MediaType.APPLICATION_JSON)
				.content(result))
			.andExpect(status().isBadRequest());

		assertEquals("RUNNING",
				this.jdbcTemplate.queryForObject("SELECT status FROM ai_job WHERE ai_job_id = ?", String.class, jobId));
	}

	@Test
	void rejectsCandidateGeometryThatDoesNotDeclareTwentyOneValidLandmarks() throws Exception {
		var jobId = UUID.randomUUID();
		var attempt = UUID.randomUUID();
		var messageId = UUID.randomUUID();
		var lease = "A".repeat(43);
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		insertRunningJob(jobId, attempt, messageId, AiLeaseCapabilityFactory.sha256(lease), now);

		var timestamp = now.toString();
		var result = """
				{"schema_version":"ai-hand-pose-result.v2","message_id":"%s","job_id":"%s","attempt":1,
				"lease_token":"%s","input_sha256":"%s","model_sha256":"%s","profile_sha256":"%s",
				"started_at":"%s","completed_at":"%s","outcome":"SUCCEEDED","analysis_complete":true,
				"hand_result":"HAND_DETECTED","total_video_frames":6,"inference_frame_count":3,
				"frames_with_hand":1,"frames_without_hand":2,"left_hand_detection_count":1,
				"right_hand_detection_count":0,"invalid_landmark_count":0,"processing_duration_ms":17,
				"failure_reason":null,"candidates":[{"frame_timestamp_ms":0,"image_width":1280,
				"image_height":720,"hand_index":0,"handedness":"LEFT","handedness_score":0.91,
				"bounding_box":{"x":0.1,"y":0.2,"width":0.2,"height":0.3},"landmarks":[%s],
				"valid_landmark_count":20}]}
				""".formatted(messageId, jobId, lease, INPUT_SHA256, MODEL_SHA256, HandPoseProfile.PROFILE_SHA256,
				timestamp, timestamp, landmarksJson());

		this.mockMvc
			.perform(put("/api/v1/internal/ai-jobs/{jobId}/attempts/1/result", jobId)
				.header("Authorization", "Bearer " + AI_TOKEN)
				.header("X-AI-Lease-Token", lease)
				.contentType(MediaType.APPLICATION_JSON)
				.content(result))
			.andExpect(status().isBadRequest());

		assertEquals("RUNNING",
				this.jdbcTemplate.queryForObject("SELECT status FROM ai_job WHERE ai_job_id = ?", String.class, jobId));
		assertEquals(0, this.jdbcTemplate
			.queryForObject("SELECT count(*) FROM candidate_annotation WHERE ai_job_id = ?", Integer.class, jobId));
	}

	@Test
	void acceptsTwoDistinctHandsInOneSampledFrame() throws Exception {
		var jobId = UUID.randomUUID();
		var attempt = UUID.randomUUID();
		var messageId = UUID.randomUUID();
		var lease = "A".repeat(43);
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		insertRunningJob(jobId, attempt, messageId, AiLeaseCapabilityFactory.sha256(lease), now);

		var candidateTemplate = """
				{"frame_timestamp_ms":0,"image_width":1280,"image_height":720,"hand_index":%d,
				"handedness":"%s","handedness_score":0.91,
				"bounding_box":{"x":0.1,"y":0.2,"width":0.2,"height":0.3},"landmarks":[%s],
				"valid_landmark_count":21}
				""";
		var left = candidateTemplate.formatted(0, "LEFT", landmarksJson());
		var right = candidateTemplate.formatted(1, "RIGHT", landmarksJson());
		var timestamp = now.toString();
		var result = """
				{"schema_version":"ai-hand-pose-result.v2","message_id":"%s","job_id":"%s","attempt":1,
				"lease_token":"%s","input_sha256":"%s","model_sha256":"%s","profile_sha256":"%s",
				"started_at":"%s","completed_at":"%s","outcome":"SUCCEEDED","analysis_complete":true,
				"hand_result":"HAND_DETECTED","total_video_frames":6,"inference_frame_count":3,
				"frames_with_hand":1,"frames_without_hand":2,"left_hand_detection_count":1,
				"right_hand_detection_count":1,"invalid_landmark_count":0,"processing_duration_ms":17,
				"failure_reason":null,"candidates":[%s,%s]}
				""".formatted(messageId, jobId, lease, INPUT_SHA256, MODEL_SHA256, HandPoseProfile.PROFILE_SHA256,
				timestamp, timestamp, left, right);

		this.mockMvc
			.perform(put("/api/v1/internal/ai-jobs/{jobId}/attempts/1/result", jobId)
				.header("Authorization", "Bearer " + AI_TOKEN)
				.header("X-AI-Lease-Token", lease)
				.contentType(MediaType.APPLICATION_JSON)
				.content(result))
			.andExpect(status().isNoContent());

		assertEquals("SUCCEEDED",
				this.jdbcTemplate.queryForObject("SELECT status FROM ai_job WHERE ai_job_id = ?", String.class, jobId));
		assertEquals(2, this.jdbcTemplate
			.queryForObject("SELECT count(*) FROM candidate_annotation WHERE ai_job_id = ?", Integer.class, jobId));
	}

	@Test
	void persistsCompleteCandidatesAcrossThreeTimestampsAndBothHands() throws Exception {
		var jobId = UUID.randomUUID();
		var attempt = UUID.randomUUID();
		var messageId = UUID.randomUUID();
		var lease = "A".repeat(43);
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		insertRunningJob(jobId, attempt, messageId, AiLeaseCapabilityFactory.sha256(lease), now);

		var result = AiHandPoseResultFixture.handDetectedResult(messageId, jobId, 1, lease, INPUT_SHA256, MODEL_SHA256,
				now, now);
		submitResult(jobId, 1, lease, result);

		var rows = this.jdbcTemplate.queryForList("""
				SELECT frame_timestamp_ms, hand_index, handedness, handedness_score,
				       image_width, image_height, valid_landmark_count,
				       jsonb_array_length(landmarks) AS landmark_count,
				       landmarks -> 0 ->> 'x' AS first_landmark_x,
				       landmarks -> 0 ->> 'y' AS first_landmark_y,
				       landmarks -> 0 ->> 'z' AS first_landmark_z,
				       bounding_box ->> 'x' AS bounding_box_x,
				       bounding_box ->> 'y' AS bounding_box_y,
				       bounding_box ->> 'width' AS bounding_box_width,
				       bounding_box ->> 'height' AS bounding_box_height
				  FROM candidate_annotation
				 WHERE ai_job_id = ?
				 ORDER BY frame_timestamp_ms, hand_index
				""", jobId);

		assertEquals(4, rows.size());
		assertEquals(List.of(100L, 200L, 300L, 300L),
				rows.stream().map(row -> ((Number) row.get("frame_timestamp_ms")).longValue()).toList());
		assertEquals(List.of(0, 0, 0, 1),
				rows.stream().map(row -> ((Number) row.get("hand_index")).intValue()).toList());
		assertEquals(List.of("RIGHT", "LEFT", "LEFT", "RIGHT"),
				rows.stream().map(row -> (String) row.get("handedness")).toList());
		assertEquals(List.of(0.91, 0.92, 0.93, 0.94),
				rows.stream().map(row -> ((Number) row.get("handedness_score")).doubleValue()).toList());
		assertEquals(List.of(1280, 1280, 1280, 1280),
				rows.stream().map(row -> ((Number) row.get("image_width")).intValue()).toList());
		assertEquals(List.of(720, 720, 720, 720),
				rows.stream().map(row -> ((Number) row.get("image_height")).intValue()).toList());
		assertEquals(List.of(21, 21, 21, 21),
				rows.stream().map(row -> ((Number) row.get("valid_landmark_count")).intValue()).toList());
		assertEquals(List.of(21, 21, 21, 21),
				rows.stream().map(row -> ((Number) row.get("landmark_count")).intValue()).toList());
		assertEquals(List.of(0.1, 0.11, 0.12, 0.13),
				rows.stream().map(row -> Double.parseDouble((String) row.get("first_landmark_x"))).toList());
		assertEquals(List.of(0.2, 0.21, 0.22, 0.23),
				rows.stream().map(row -> Double.parseDouble((String) row.get("first_landmark_y"))).toList());
		assertEquals(List.of(-0.01, -0.011, -0.012, -0.013),
				rows.stream().map(row -> Double.parseDouble((String) row.get("first_landmark_z"))).toList());
		assertEquals(List.of(0.1, 0.11, 0.12, 0.13),
				rows.stream().map(row -> Double.parseDouble((String) row.get("bounding_box_x"))).toList());
		assertEquals(List.of(0.2, 0.21, 0.22, 0.23),
				rows.stream().map(row -> Double.parseDouble((String) row.get("bounding_box_y"))).toList());
		assertEquals(List.of(0.02, 0.02, 0.02, 0.02),
				rows.stream().map(row -> Double.parseDouble((String) row.get("bounding_box_width"))).toList());
		assertEquals(List.of(0.04, 0.04, 0.04, 0.04),
				rows.stream().map(row -> Double.parseDouble((String) row.get("bounding_box_height"))).toList());
		assertEquals(4, this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM candidate_annotation WHERE ai_job_id = ? AND project_id = ? AND clip_asset_id = ?",
				Integer.class, jobId, this.lastInsertedProjectId, this.lastInsertedClipAssetId));
		assertEquals(0, this.jdbcTemplate.queryForObject("""
				SELECT count(*)
				  FROM candidate_annotation candidate
				 CROSS JOIN LATERAL jsonb_array_elements(candidate.landmarks) point
				 WHERE candidate.ai_job_id = ?
				   AND NOT (jsonb_typeof(point) = 'object'
				            AND jsonb_exists(point, 'x') AND jsonb_exists(point, 'y')
				            AND jsonb_exists(point, 'z')
				            AND jsonb_typeof(point -> 'x') = 'number'
				            AND jsonb_typeof(point -> 'y') = 'number'
				            AND jsonb_typeof(point -> 'z') = 'number')
				""", Integer.class, jobId));
	}

	@Test
	void replayingTheSameResultDoesNotDuplicateLogicalCandidates() throws Exception {
		var jobId = UUID.randomUUID();
		var attempt = UUID.randomUUID();
		var messageId = UUID.randomUUID();
		var lease = "A".repeat(43);
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		insertRunningJob(jobId, attempt, messageId, AiLeaseCapabilityFactory.sha256(lease), now);
		var result = AiHandPoseResultFixture.handDetectedResult(messageId, jobId, 1, lease, INPUT_SHA256, MODEL_SHA256,
				now, now);

		submitResult(jobId, 1, lease, result);
		submitResult(jobId, 1, lease, result);

		assertEquals(4, this.jdbcTemplate
			.queryForObject("SELECT count(*) FROM candidate_annotation WHERE ai_job_id = ?", Integer.class, jobId));
		assertEquals(4, this.jdbcTemplate.queryForObject(
				"SELECT count(DISTINCT (frame_timestamp_ms, hand_index)) FROM candidate_annotation WHERE ai_job_id = ?",
				Integer.class, jobId));
		assertEquals(1, this.jdbcTemplate.queryForObject("SELECT count(*) FROM ai_job_result_inbox WHERE ai_job_id = ?",
				Integer.class, jobId));
	}

	@Test
	void failedAttemptRemainsDistinctWhenTheSecondAttemptPersistsCandidates() throws Exception {
		var jobId = UUID.randomUUID();
		var firstAttemptId = UUID.randomUUID();
		var firstMessageId = UUID.randomUUID();
		var firstLease = "A".repeat(43);
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		insertRunningJob(jobId, firstAttemptId, firstMessageId, AiLeaseCapabilityFactory.sha256(firstLease), now);

		var firstResult = AiHandPoseResultFixture.failedResult(firstMessageId, jobId, 1, firstLease, INPUT_SHA256,
				MODEL_SHA256, now, now, "AI_HAND_POSE_INVALID_OUTPUT");
		submitResult(jobId, 1, firstLease, firstResult);
		assertEquals("FAILED",
				this.jdbcTemplate.queryForObject("SELECT status FROM ai_job WHERE ai_job_id = ?", String.class, jobId));
		assertEquals("FAILED", this.jdbcTemplate.queryForObject(
				"SELECT status FROM ai_job_attempt WHERE ai_job_attempt_id = ?", String.class, firstAttemptId));
		assertEquals(0, this.jdbcTemplate
			.queryForObject("SELECT count(*) FROM candidate_annotation WHERE ai_job_id = ?", Integer.class, jobId));

		var secondAttemptId = UUID.randomUUID();
		var secondMessageId = UUID.randomUUID();
		var secondLease = "B".repeat(43);
		var retryAt = now.plusSeconds(1);
		this.jdbcTemplate.update(
				"UPDATE ai_job SET status = 'RUNNING', hand_result = 'UNKNOWN', updated_at = ? WHERE ai_job_id = ?",
				Timestamp.from(retryAt), jobId);
		insertRunningAttempt(jobId, 2, secondAttemptId, secondMessageId, AiLeaseCapabilityFactory.sha256(secondLease),
				retryAt);

		var secondResult = AiHandPoseResultFixture.handDetectedResult(secondMessageId, jobId, 2, secondLease,
				INPUT_SHA256, MODEL_SHA256, retryAt, retryAt);
		submitResult(jobId, 2, secondLease, secondResult);

		assertEquals("SUCCEEDED",
				this.jdbcTemplate.queryForObject("SELECT status FROM ai_job WHERE ai_job_id = ?", String.class, jobId));
		assertEquals("HAND_DETECTED", this.jdbcTemplate
			.queryForObject("SELECT hand_result FROM ai_job WHERE ai_job_id = ?", String.class, jobId));
		assertEquals("FAILED", this.jdbcTemplate.queryForObject(
				"SELECT status FROM ai_job_attempt WHERE ai_job_attempt_id = ?", String.class, firstAttemptId));
		assertEquals("SUCCEEDED", this.jdbcTemplate.queryForObject(
				"SELECT status FROM ai_job_attempt WHERE ai_job_attempt_id = ?", String.class, secondAttemptId));
		assertEquals(4, this.jdbcTemplate
			.queryForObject("SELECT count(*) FROM candidate_annotation WHERE ai_job_id = ?", Integer.class, jobId));
		var inboxRows = this.jdbcTemplate.queryForList(
				"SELECT attempt, message_id FROM ai_job_result_inbox WHERE ai_job_id = ? ORDER BY attempt", jobId);
		assertEquals(List.of(1, 2), inboxRows.stream().map(row -> ((Number) row.get("attempt")).intValue()).toList());
		assertEquals(List.of(firstMessageId, secondMessageId),
				inboxRows.stream().map(row -> (UUID) row.get("message_id")).toList());
	}

	@Test
	void rejectsDuplicateHandSlotsWithinTheSameSampledFrame() throws Exception {
		var jobId = UUID.randomUUID();
		var attempt = UUID.randomUUID();
		var messageId = UUID.randomUUID();
		var lease = "A".repeat(43);
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		insertRunningJob(jobId, attempt, messageId, AiLeaseCapabilityFactory.sha256(lease), now);

		var timestamp = now.toString();
		var candidate = """
				{"frame_timestamp_ms":0,"image_width":1280,"image_height":720,"hand_index":0,
				"handedness":"LEFT","handedness_score":0.91,
				"bounding_box":{"x":0.1,"y":0.2,"width":0.2,"height":0.3},"landmarks":[%s],
				"valid_landmark_count":21}
				""".formatted(landmarksJson());
		var result = """
				{"schema_version":"ai-hand-pose-result.v2","message_id":"%s","job_id":"%s","attempt":1,
				"lease_token":"%s","input_sha256":"%s","model_sha256":"%s","profile_sha256":"%s",
				"started_at":"%s","completed_at":"%s","outcome":"SUCCEEDED","analysis_complete":true,
				"hand_result":"HAND_DETECTED","total_video_frames":6,"inference_frame_count":3,
				"frames_with_hand":1,"frames_without_hand":2,"left_hand_detection_count":2,
				"right_hand_detection_count":0,"invalid_landmark_count":0,"processing_duration_ms":17,
				"failure_reason":null,"candidates":[%s,%s]}
				""".formatted(messageId, jobId, lease, INPUT_SHA256, MODEL_SHA256, HandPoseProfile.PROFILE_SHA256,
				timestamp, timestamp, candidate, candidate);

		this.mockMvc
			.perform(put("/api/v1/internal/ai-jobs/{jobId}/attempts/1/result", jobId)
				.header("Authorization", "Bearer " + AI_TOKEN)
				.header("X-AI-Lease-Token", lease)
				.contentType(MediaType.APPLICATION_JSON)
				.content(result))
			.andExpect(status().isBadRequest());

		assertEquals("RUNNING",
				this.jdbcTemplate.queryForObject("SELECT status FROM ai_job WHERE ai_job_id = ?", String.class, jobId));
		assertEquals(0, this.jdbcTemplate
			.queryForObject("SELECT count(*) FROM candidate_annotation WHERE ai_job_id = ?", Integer.class, jobId));
	}

	@Test
	void rejectsCandidateGeometryWithNonFiniteLandmarkCoordinates() throws Exception {
		var jobId = UUID.randomUUID();
		var attempt = UUID.randomUUID();
		var messageId = UUID.randomUUID();
		var lease = "A".repeat(43);
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		insertRunningJob(jobId, attempt, messageId, AiLeaseCapabilityFactory.sha256(lease), now);

		var timestamp = now.toString();
		var result = """
				{"schema_version":"ai-hand-pose-result.v2","message_id":"%s","job_id":"%s","attempt":1,
				"lease_token":"%s","input_sha256":"%s","model_sha256":"%s","profile_sha256":"%s",
				"started_at":"%s","completed_at":"%s","outcome":"SUCCEEDED","analysis_complete":true,
				"hand_result":"HAND_DETECTED","total_video_frames":6,"inference_frame_count":3,
				"frames_with_hand":1,"frames_without_hand":2,"left_hand_detection_count":1,
				"right_hand_detection_count":0,"invalid_landmark_count":0,"processing_duration_ms":17,
				"failure_reason":null,"candidates":[{"frame_timestamp_ms":0,"image_width":1280,
				"image_height":720,"hand_index":0,"handedness":"LEFT","handedness_score":0.91,
				"bounding_box":{"x":0.1,"y":0.2,"width":0.2,"height":0.3},"landmarks":[%s],
				"valid_landmark_count":21}]}
				""".formatted(messageId, jobId, lease, INPUT_SHA256, MODEL_SHA256, HandPoseProfile.PROFILE_SHA256,
				timestamp, timestamp, landmarksJson().replace("\"z\":-0.01", "\"z\":1e400"));

		this.mockMvc
			.perform(put("/api/v1/internal/ai-jobs/{jobId}/attempts/1/result", jobId)
				.header("Authorization", "Bearer " + AI_TOKEN)
				.header("X-AI-Lease-Token", lease)
				.contentType(MediaType.APPLICATION_JSON)
				.content(result))
			.andExpect(status().isBadRequest());

		assertEquals("RUNNING",
				this.jdbcTemplate.queryForObject("SELECT status FROM ai_job WHERE ai_job_id = ?", String.class, jobId));
		assertEquals(0, this.jdbcTemplate
			.queryForObject("SELECT count(*) FROM candidate_annotation WHERE ai_job_id = ?", Integer.class, jobId));
	}

	@Test
	void rejectsCandidateGeometryWithNonFiniteBoundingBoxValues() throws Exception {
		var jobId = UUID.randomUUID();
		var attempt = UUID.randomUUID();
		var messageId = UUID.randomUUID();
		var lease = "A".repeat(43);
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		insertRunningJob(jobId, attempt, messageId, AiLeaseCapabilityFactory.sha256(lease), now);

		var timestamp = now.toString();
		var result = """
				{"schema_version":"ai-hand-pose-result.v2","message_id":"%s","job_id":"%s","attempt":1,
				"lease_token":"%s","input_sha256":"%s","model_sha256":"%s","profile_sha256":"%s",
				"started_at":"%s","completed_at":"%s","outcome":"SUCCEEDED","analysis_complete":true,
				"hand_result":"HAND_DETECTED","total_video_frames":6,"inference_frame_count":3,
				"frames_with_hand":1,"frames_without_hand":2,"left_hand_detection_count":1,
				"right_hand_detection_count":0,"invalid_landmark_count":0,"processing_duration_ms":17,
				"failure_reason":null,"candidates":[{"frame_timestamp_ms":0,"image_width":1280,
				"image_height":720,"hand_index":0,"handedness":"LEFT","handedness_score":0.91,
				"bounding_box":{"x":0.1,"y":0.2,"width":1e400,"height":0.3},"landmarks":[%s],
				"valid_landmark_count":21}]}
				""".formatted(messageId, jobId, lease, INPUT_SHA256, MODEL_SHA256, HandPoseProfile.PROFILE_SHA256,
				timestamp, timestamp, landmarksJson());

		this.mockMvc
			.perform(put("/api/v1/internal/ai-jobs/{jobId}/attempts/1/result", jobId)
				.header("Authorization", "Bearer " + AI_TOKEN)
				.header("X-AI-Lease-Token", lease)
				.contentType(MediaType.APPLICATION_JSON)
				.content(result))
			.andExpect(status().isBadRequest());

		assertEquals("RUNNING",
				this.jdbcTemplate.queryForObject("SELECT status FROM ai_job WHERE ai_job_id = ?", String.class, jobId));
		assertEquals(0, this.jdbcTemplate
			.queryForObject("SELECT count(*) FROM candidate_annotation WHERE ai_job_id = ?", Integer.class, jobId));
	}

	@ParameterizedTest
	@CsvSource({ "frame_timestamp_ms,0", "hand_index,0", "handedness_score,0.91" })
	void rejectsCandidatesWhenRequiredScalarFieldsAreMissing(String field, String value) throws Exception {
		var jobId = UUID.randomUUID();
		var attempt = UUID.randomUUID();
		var messageId = UUID.randomUUID();
		var lease = "A".repeat(43);
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		insertRunningJob(jobId, attempt, messageId, AiLeaseCapabilityFactory.sha256(lease), now);

		var candidate = """
				{"frame_timestamp_ms":0,"image_width":1280,"image_height":720,"hand_index":0,
				"handedness":"LEFT","handedness_score":0.91,
				"bounding_box":{"x":0.1,"y":0.2,"width":0.2,"height":0.3},"landmarks":[%s],
				"valid_landmark_count":21}
				""".formatted(landmarksJson()).replace("\"" + field + "\":" + value + ",", "");
		var timestamp = now.toString();
		var result = """
				{"schema_version":"ai-hand-pose-result.v2","message_id":"%s","job_id":"%s","attempt":1,
				"lease_token":"%s","input_sha256":"%s","model_sha256":"%s","profile_sha256":"%s",
				"started_at":"%s","completed_at":"%s","outcome":"SUCCEEDED","analysis_complete":true,
				"hand_result":"HAND_DETECTED","total_video_frames":6,"inference_frame_count":3,
				"frames_with_hand":1,"frames_without_hand":2,"left_hand_detection_count":1,
				"right_hand_detection_count":0,"invalid_landmark_count":0,"processing_duration_ms":17,
				"failure_reason":null,"candidates":[%s]}
				""".formatted(messageId, jobId, lease, INPUT_SHA256, MODEL_SHA256, HandPoseProfile.PROFILE_SHA256,
				timestamp, timestamp, candidate);

		this.mockMvc
			.perform(put("/api/v1/internal/ai-jobs/{jobId}/attempts/1/result", jobId)
				.header("Authorization", "Bearer " + AI_TOKEN)
				.header("X-AI-Lease-Token", lease)
				.contentType(MediaType.APPLICATION_JSON)
				.content(result))
			.andExpect(status().isBadRequest());

		assertEquals("RUNNING",
				this.jdbcTemplate.queryForObject("SELECT status FROM ai_job WHERE ai_job_id = ?", String.class, jobId));
		assertEquals(0, this.jdbcTemplate
			.queryForObject("SELECT count(*) FROM candidate_annotation WHERE ai_job_id = ?", Integer.class, jobId));
	}

	private void submitResult(UUID jobId, int attempt, String lease, String result) throws Exception {
		this.mockMvc
			.perform(put("/api/v1/internal/ai-jobs/{jobId}/attempts/{attempt}/result", jobId, attempt)
				.header("Authorization", "Bearer " + AI_TOKEN)
				.header("X-AI-Lease-Token", lease)
				.contentType(MediaType.APPLICATION_JSON)
				.content(result))
			.andExpect(status().isNoContent());
	}

	private static String landmarksJson() {
		return IntStream.range(0, 21)
			.mapToObj(index -> "{\"x\":" + (0.1 + index * 0.001) + ",\"y\":0.2,\"z\":-0.01}")
			.collect(Collectors.joining(","));
	}

	private void assertNoFormalWorkflowWrites() {
		assertEquals(0, this.jdbcTemplate.queryForObject("SELECT count(*) FROM quality_review WHERE project_id = ?",
				Integer.class, this.lastInsertedProjectId));
		assertEquals(0,
				this.jdbcTemplate.queryForObject(
						"SELECT count(*) FROM capture_group_quality_review WHERE project_id = ?", Integer.class,
						this.lastInsertedProjectId));
		assertEquals(0,
				this.jdbcTemplate.queryForObject("SELECT count(*) FROM annotation_revision WHERE project_id = ?",
						Integer.class, this.lastInsertedProjectId));
		assertEquals(0, this.jdbcTemplate.queryForObject("SELECT count(*) FROM annotation_review WHERE project_id = ?",
				Integer.class, this.lastInsertedProjectId));
		assertEquals(0,
				this.jdbcTemplate.queryForObject("SELECT count(*) FROM capture_group_cleaning WHERE project_id = ?",
						Integer.class, this.lastInsertedProjectId));
	}

	private void insertRunningJob(UUID jobId, UUID attemptId, UUID messageId, String leaseHash, Instant now) {
		var projectId = UUID.randomUUID();
		this.lastInsertedProjectId = projectId;
		var clipAssetId = UUID.randomUUID();
		this.lastInsertedClipAssetId = clipAssetId;
		var batchId = UUID.randomUUID();
		var uploadFileId = UUID.randomUUID();
		var clientFileId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO upload_batch (
				    batch_id, project_id, idempotency_key, request_fingerprint, device_id,
				    initiated_by_actor_id, file_count, total_size_bytes, manifest_sha256,
				    status, expires_at, created_at, updated_at, verified_manifest_sha256, completed_at)
				VALUES (?, ?, ?, ?, 'synthetic-ai-result-device', 'synthetic', 1, 100, ?,
				        'COMPLETED', ?, ?, ?, ?, ?)
				""", batchId, projectId, "ai-result-batch-" + batchId, "c".repeat(64), INPUT_SHA256,
				Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now), INPUT_SHA256,
				Timestamp.from(now));
		this.jdbcTemplate.update("""
				INSERT INTO upload_file (
				    upload_file_id, batch_id, client_file_id, idempotency_key, request_fingerprint,
				    file_kind, content_type, size_bytes, sha256, part_size_bytes, total_parts,
				    status, created_at, updated_at, storage_bucket, object_key, storage_upload_id,
				    multipart_created_at, completion_started_at, completion_finished_at,
				    verified_sha256, completed_storage_etag)
				VALUES (?, ?, ?, ?, ?, 'VIDEO', 'video/mp4', 100, ?, 8388608, 1, 'UPLOADED',
				        ?, ?, 'synthetic-bucket', ?, ?, ?, ?, ?, ?, 'synthetic-etag')
				""", uploadFileId, batchId, clientFileId, "ai-result-file-" + uploadFileId, "c".repeat(64),
				INPUT_SHA256, Timestamp.from(now), Timestamp.from(now), "ai-results/" + uploadFileId,
				"multipart-" + uploadFileId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
				INPUT_SHA256);
		this.jdbcTemplate.update("""
				INSERT INTO clip_asset (
				    clip_asset_id, project_id, batch_id, source_upload_file_id, client_file_id,
				    content_type, size_bytes, sha256, storage_bucket, object_key, storage_etag,
				    asset_status, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'video/mp4', 100, ?, 'synthetic-bucket', ?,
				        'synthetic-etag', 'INGESTED', ?, ?)
				""", clipAssetId, projectId, batchId, uploadFileId, clientFileId, INPUT_SHA256,
				"ai-results/" + clipAssetId, Timestamp.from(now), Timestamp.from(now));
		this.jdbcTemplate.update(
				"""
						INSERT INTO ai_job (ai_job_id, project_id, clip_asset_id, algorithm_key, algorithm_version,
						 model_sha256, input_sha256, profile_sha256, input_size_bytes, content_type, sampling_fps, status, idempotency_key,
						 request_fingerprint, requested_by, created_at, updated_at, version)
						 VALUES (?, ?, ?, 'MEDIAPIPE_HAND_LANDMARKER', 'v1', ?, ?, ?, 100, 'video/mp4', 10, 'RUNNING', ?, ?, ?, ?, ?, 0)
						 """,
				jobId, projectId, clipAssetId, MODEL_SHA256, INPUT_SHA256, HandPoseProfile.PROFILE_SHA256,
				"idempotency-" + jobId.toString(), "c".repeat(64), "synthetic", Timestamp.from(now),
				Timestamp.from(now));
		this.jdbcTemplate.update("""
				INSERT INTO ai_job_attempt (ai_job_attempt_id, ai_job_id, attempt, message_id, lease_token_hash,
				 status, worker_id, lease_until, updated_at, created_at)
				VALUES (?, ?, 1, ?, ?, 'RUNNING', 'synthetic-ai-worker', ?, ?, ?)
				""", attemptId, jobId, messageId, leaseHash, Timestamp.from(now.plusSeconds(30)), Timestamp.from(now),
				Timestamp.from(now));
	}

	private void insertRunningAttempt(UUID jobId, int attemptNumber, UUID attemptId, UUID messageId, String leaseHash,
			Instant now) {
		this.jdbcTemplate.update("""
				INSERT INTO ai_job_attempt (ai_job_attempt_id, ai_job_id, attempt, message_id, lease_token_hash,
				 status, worker_id, lease_until, updated_at, created_at)
				VALUES (?, ?, ?, ?, ?, 'RUNNING', 'synthetic-ai-worker', ?, ?, ?)
				""", attemptId, jobId, attemptNumber, messageId, leaseHash, Timestamp.from(now.plusSeconds(30)),
				Timestamp.from(now), Timestamp.from(now));
	}

}
