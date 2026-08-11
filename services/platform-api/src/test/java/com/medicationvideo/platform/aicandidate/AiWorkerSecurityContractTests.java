package com.medicationvideo.platform.aicandidate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiWorkerSecurityContractTests {

	private static final String AI_TOKEN = "synthetic-ai-worker-bearer-token-do-not-reuse";

	private static final String MEDIA_TOKEN = "synthetic-media-worker-bearer-token-do-not-reuse";

	private static final String EXPORT_TOKEN = "synthetic-export-worker-bearer-token-do-not-reuse";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void acceptsTheAiWorkerIdentityBeforeEvaluatingTheJob() throws Exception {
		this.mockMvc
			.perform(put(resultPath()).header("Authorization", "Bearer " + AI_TOKEN)
				.header("X-AI-Lease-Token", "A".repeat(43))
				.contentType(MediaType.APPLICATION_JSON)
				.content(validFailureResult()))
			.andExpect(status().isNotFound());
	}

	@Test
	void rejectsMissingAndWrongWorkerIdentities() throws Exception {
		this.mockMvc
			.perform(put(resultPath()).header("X-AI-Lease-Token", "A".repeat(43))
				.contentType(MediaType.APPLICATION_JSON)
				.content(validFailureResult()))
			.andExpect(status().isUnauthorized());

		this.mockMvc
			.perform(
					put(resultPath()).header("Authorization", "Bearer wrong-ai-worker-token-that-is-long-enough-123456")
						.header("X-AI-Lease-Token", "A".repeat(43))
						.contentType(MediaType.APPLICATION_JSON)
						.content(validFailureResult()))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsOtherWorkerIdentitiesFromTheAiEndpoint() throws Exception {
		for (var token : new String[] { MEDIA_TOKEN, EXPORT_TOKEN }) {
			this.mockMvc
				.perform(put(resultPath()).header("Authorization", "Bearer " + token)
					.header("X-AI-Lease-Token", "A".repeat(43))
					.contentType(MediaType.APPLICATION_JSON)
					.content(validFailureResult()))
				.andExpect(status().isUnauthorized());
		}
	}

	private static String resultPath() {
		return "/api/v1/internal/ai-jobs/00000000-0000-0000-0000-000000000099/attempts/1/result";
	}

	private static String validFailureResult() {
		var timestamp = Instant.now().toString();
		return """
				{"schema_version":"ai-hand-pose-result.v2","message_id":"00000000-0000-0000-0000-000000000091",
				"job_id":"00000000-0000-0000-0000-000000000099","attempt":1,"lease_token":"%s",
				"input_sha256":"%s","model_sha256":"%s","profile_sha256":"%s","started_at":"%s",
				"completed_at":"%s","outcome":"FAILED","analysis_complete":false,"hand_result":"UNKNOWN",
				"total_video_frames":0,"inference_frame_count":0,"frames_with_hand":0,"frames_without_hand":0,
				"left_hand_detection_count":0,"right_hand_detection_count":0,"invalid_landmark_count":0,
				"processing_duration_ms":0,"failure_reason":"synthetic","candidates":[]}
				""".formatted("A".repeat(43), "a".repeat(64), "b".repeat(64), HandPoseProfile.PROFILE_SHA256, timestamp,
				timestamp);
	}

}
