package com.medicationvideo.platform.aicandidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.medicationvideo.platform.support.ProjectMembershipTestData;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiEvaluationContractTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000650");

	private static final String MEMBER_TOKEN = "synthetic-test-bearer-token-do-not-reuse";

	private static final String MODEL_SHA256 = "b".repeat(64);

	private static final String SAMPLE_SHA256 = "c".repeat(64);

	private static final String PROFILE_SHA256 = "3a3be8035ec8a9756a32f43b007a4bb2cc2e2c0468d744d248f19c692fd90626";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		clearEvaluations();
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, PROJECT_ID);
	}

	@AfterEach
	void tearDown() {
		clearEvaluations();
	}

	@Test
	void persistsPendingMetricsWithTheRequiredBindingsAndAudit() throws Exception {
		var body = request(pendingMetrics());
		var response = this.mockMvc
			.perform(post("/api/v1/projects/{projectId}/ai/evaluations", PROJECT_ID)
				.header("Authorization", "Bearer " + MEMBER_TOKEN)
				.header("X-Request-Id", "ai-evaluation-pending-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isCreated())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.project_id").value(PROJECT_ID.toString()))
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.model_sha256").value(MODEL_SHA256))
			.andExpect(jsonPath("$.profile_sha256").value(PROFILE_SHA256))
			.andExpect(jsonPath("$.metrics.length()").value(7))
			.andExpect(jsonPath("$.metrics[0].value").value(nullValue()))
			.andReturn()
			.getResponse()
			.getContentAsString();
		var evaluationId = UUID.fromString(JsonPath.read(response, "$.evaluation_id"));

		assertEquals(1,
				this.jdbcTemplate.queryForObject(
						"SELECT count(*) FROM ai_evaluation WHERE project_id = ? AND ai_evaluation_id = ?",
						Integer.class, PROJECT_ID, evaluationId));
		assertEquals(7, this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM ai_evaluation_metric WHERE ai_evaluation_id = ?", Integer.class, evaluationId));
		assertEquals(7, this.jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM ai_evaluation_metric
				WHERE ai_evaluation_id = ? AND metric_status = 'PENDING' AND metric_value IS NULL
				""", Integer.class, evaluationId));
		assertEquals(1, this.jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM audit_event
				WHERE project_id = ? AND object_type = 'AIEvaluation' AND object_id = ?
				  AND action = 'AI_EVALUATION_RECORDED'
				""", Integer.class, PROJECT_ID, evaluationId.toString()));
		assertEquals("PENDING", this.jdbcTemplate.queryForObject("""
				SELECT details_json ->> 'evaluation_status'
				FROM audit_event
				WHERE project_id = ? AND object_type = 'AIEvaluation' AND object_id = ?
				""", String.class, PROJECT_ID, evaluationId.toString()));
	}

	@Test
	void rejectsPendingMetricWithZeroValueInsteadOfTreatingItAsEvidence() throws Exception {
		var body = request(pendingMetrics().replaceFirst("\"value\":null", "\"value\":0"));

		this.mockMvc
			.perform(post("/api/v1/projects/{projectId}/ai/evaluations", PROJECT_ID)
				.header("Authorization", "Bearer " + MEMBER_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isBadRequest());
	}

	@Test
	void rejectsAnEvaluationWithoutAStableSampleBinding() throws Exception {
		var body = request(pendingMetrics()).replace("  \"sample_version\": \"authorized-sample-v1\",\n", "")
			.replace("\"sample_sha256\": \"" + SAMPLE_SHA256 + "\",", "");

		this.mockMvc
			.perform(post("/api/v1/projects/{projectId}/ai/evaluations", PROJECT_ID)
				.header("Authorization", "Bearer " + MEMBER_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isBadRequest());
	}

	private String request(String metrics) {
		return """
				{
				  "sample_version": "authorized-sample-v1",
				  "sample_sha256": "%s",
				  "model_sha256": "%s",
				  "profile_sha256": "%s",
				  "parameter_adjustment_note": "BASELINE_NO_ADJUSTMENT_PENDING_AUTHORIZED_SAMPLE",
				  "metrics": %s
				}
				""".formatted(SAMPLE_SHA256, MODEL_SHA256, PROFILE_SHA256, metrics);
	}

	private static String pendingMetrics() {
		return """
				[
				  {"metric_key":"HAND_FRAME_RECALL","status":"PENDING","value":null},
				  {"metric_key":"NO_HAND_FRAME_FALSE_POSITIVE_RATE","status":"PENDING","value":null},
				  {"metric_key":"LANDMARK_21_VALID_RATE","status":"PENDING","value":null},
				  {"metric_key":"HANDEDNESS_ACCURACY","status":"PENDING","value":null},
				  {"metric_key":"FAST_MOTION_OCCLUSION_USEFULNESS","status":"PENDING","value":null},
				  {"metric_key":"OVERLAY_TIME_ERROR","status":"PENDING","value":null},
				  {"metric_key":"PROCESSING_TIME_PER_MINUTE","status":"PENDING","value":null}
				]
				""";
	}

	private void clearEvaluations() {
		this.jdbcTemplate.update("DELETE FROM ai_evaluation_metric WHERE ai_evaluation_id IN "
				+ "(SELECT ai_evaluation_id FROM ai_evaluation WHERE project_id = ?)", PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM ai_evaluation WHERE project_id = ?", PROJECT_ID);
	}

}
