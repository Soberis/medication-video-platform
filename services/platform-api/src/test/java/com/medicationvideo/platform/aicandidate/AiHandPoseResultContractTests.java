package com.medicationvideo.platform.aicandidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.util.UUID;

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

	private void insertRunningJob(UUID jobId, UUID attemptId, UUID messageId, String leaseHash, Instant now) {
		this.jdbcTemplate.update(
				"""
						INSERT INTO ai_job (ai_job_id, project_id, clip_asset_id, algorithm_key, algorithm_version,
						 model_sha256, input_sha256, profile_sha256, input_size_bytes, content_type, sampling_fps, status, idempotency_key,
						 request_fingerprint, requested_by, created_at, updated_at, version)
						 VALUES (?, ?, ?, 'MEDIAPIPE_HAND_LANDMARKER', 'v1', ?, ?, ?, 100, 'video/mp4', 10, 'RUNNING', ?, ?, ?, ?, ?, 0)
						 """,
				jobId, UUID.randomUUID(), UUID.randomUUID(), MODEL_SHA256, INPUT_SHA256, HandPoseProfile.PROFILE_SHA256,
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
