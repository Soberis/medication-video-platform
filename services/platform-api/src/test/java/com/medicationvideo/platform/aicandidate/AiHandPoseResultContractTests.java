package com.medicationvideo.platform.aicandidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiHandPoseResultContractTests {

	private static final String AI_TOKEN = "synthetic-ai-worker-bearer-token-do-not-reuse";

	private static final String MODEL_SHA256 = "b".repeat(64);

	private static final String INPUT_SHA256 = "a".repeat(64);

	private UUID lastInsertedClipAssetId;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private MockMvc mockMvc;

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

	private static String landmarksJson() {
		return IntStream.range(0, 21)
			.mapToObj(index -> "{\"x\":" + (0.1 + index * 0.001) + ",\"y\":0.2,\"z\":-0.01}")
			.collect(Collectors.joining(","));
	}

	private void insertRunningJob(UUID jobId, UUID attemptId, UUID messageId, String leaseHash, Instant now) {
		var projectId = UUID.randomUUID();
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

}
