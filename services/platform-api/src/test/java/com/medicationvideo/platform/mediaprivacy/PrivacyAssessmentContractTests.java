package com.medicationvideo.platform.mediaprivacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PrivacyAssessmentContractTests {

	private static final String USER_TOKEN = "synthetic-test-bearer-token-do-not-reuse";
	private static final String ADMIN_TOKEN = "synthetic-test-admin-bearer-token-do-not-reuse";
	private static final UUID PROJECT_ID =
			UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final String SHA256 = "c".repeat(64);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clearAssessments() {
		this.jdbcTemplate.update("DELETE FROM privacy_finding");
		this.jdbcTemplate.update("DELETE FROM privacy_assessment");
	}

	@Test
	void unassessedIngestedAssetIsExplicitlyUnknownAndBlocked() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED");

		this.mockMvc.perform(get("/api/v1/clip-assets/{clipAssetId}/privacy-status", clipAssetId)
					.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.clip_asset_id").value(clipAssetId.toString()))
				.andExpect(jsonPath("$.input_digest").value(SHA256))
				.andExpect(jsonPath("$.risk_level").value("UNKNOWN"))
				.andExpect(jsonPath("$.privacy_release_status").value("BLOCKED"))
				.andExpect(jsonPath("$.required_action").doesNotExist())
				.andExpect(jsonPath("$.current_assessment").doesNotExist());
	}

	@Test
	void recordsNoObservedRiskWithoutGrantingReleaseAndAuditsDecision() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED");
		var response = assess(
				ADMIN_TOKEN,
				"privacy-none-idem-0001",
				"privacy-request-none-0001",
				noneBody(clipAssetId, null))
				.andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(header().exists("Location"))
				.andExpect(jsonPath("$.assessment_version").value(1))
				.andExpect(jsonPath("$.risk_level").value("NONE"))
				.andExpect(jsonPath("$.required_action").value("RELEASE_REVIEW_REQUIRED"))
				.andExpect(jsonPath("$.privacy_release_status").value("BLOCKED"))
				.andExpect(jsonPath("$.finding_types").isEmpty())
				.andExpect(jsonPath("$.policy_key").value("privacy-assessment-policy-v1"))
				.andExpect(jsonPath("$.policy_digest").value(PrivacyAssessmentPolicy.POLICY_DIGEST))
				.andReturn()
				.getResponse()
				.getContentAsString();
		var assessmentId = UUID.fromString(JsonPath.read(response, "$.privacy_assessment_id"));

		this.mockMvc.perform(get("/api/v1/privacy-assessments/{assessmentId}", assessmentId)
					.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.privacy_assessment_id").value(assessmentId.toString()))
				.andExpect(jsonPath("$.assessed_by").value("local-synthetic-admin"));
		assertEquals(1, this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_event WHERE object_type = 'PrivacyAssessment' AND object_id = ?",
				Integer.class,
				assessmentId.toString()));
	}

	@Test
	void recordsConfirmedFindingsIdempotentlyAndExposesCurrentStatus() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED");
		var body = confirmedBody(clipAssetId, null);
		var first = assess(
				ADMIN_TOKEN,
				"privacy-confirmed-idem-0001",
				"privacy-request-confirmed-0001",
				body)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.risk_level").value("CONFIRMED"))
				.andExpect(jsonPath("$.required_action").value("DEIDENTIFICATION_REQUIRED"))
				.andExpect(jsonPath("$.finding_types[0]").value("FACE"))
				.andExpect(jsonPath("$.finding_types[1]").value("SCREEN"))
				.andReturn()
				.getResponse()
				.getContentAsString();
		var assessmentId = JsonPath.read(first, "$.privacy_assessment_id").toString();

		assess(
				ADMIN_TOKEN,
				"privacy-confirmed-idem-0001",
				"privacy-request-confirmed-0002",
				body)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.privacy_assessment_id").value(assessmentId));

		this.mockMvc.perform(get("/api/v1/clip-assets/{clipAssetId}/privacy-status", clipAssetId)
					.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.risk_level").value("CONFIRMED"))
				.andExpect(jsonPath("$.privacy_release_status").value("BLOCKED"))
				.andExpect(jsonPath("$.current_assessment.privacy_assessment_id")
						.value(assessmentId));
		assertEquals(1, this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM privacy_assessment WHERE clip_asset_id = ?",
				Integer.class,
				clipAssetId));
	}

	@Test
	void requiresAdministratorAndProjectScopedAsset() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED");
		assess(
				USER_TOKEN,
				"privacy-uploader-idem-0001",
				"privacy-request-uploader-0001",
				noneBody(clipAssetId, null))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("PROJECT_ACCESS_DENIED"));

		var otherProjectAsset = insertClipAsset(UUID.randomUUID(), "INGESTED");
		assess(
				ADMIN_TOKEN,
				"privacy-project-idem-00001",
				"privacy-request-project-0001",
				noneBody(otherProjectAsset, null))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error_code").value("CLIP_ASSET_NOT_FOUND"));
	}

	@Test
	void rejectsInconsistentDecisionWithStableError() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED");
		var invalid = """
				{
				  "clip_asset_id": "%s",
				  "risk_level": "NONE",
				  "required_action": "DEIDENTIFICATION_REQUIRED",
				  "reason_code": "IDENTIFIABLE_INFORMATION_OBSERVED",
				  "finding_types": ["FACE"]
				}
				""".formatted(clipAssetId);

		assess(
				ADMIN_TOKEN,
				"privacy-invalid-idem-0001",
				"privacy-request-invalid-0001",
				invalid)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error_code")
						.value("INVALID_PRIVACY_ASSESSMENT_DECISION"));
		assertEquals(0, this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM privacy_assessment", Integer.class));
	}

	@Test
	void onlyAssessesSuccessfullyIngestedAssets() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "UPLOADED");

		assess(
				ADMIN_TOKEN,
				"privacy-state-idem-000001",
				"privacy-request-state-0001",
				noneBody(clipAssetId, null))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("CLIP_ASSET_NOT_ASSESSABLE"));
	}

	@Test
	void reassessmentMustExplicitlySupersedeCurrentVersion() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED");
		var first = assess(
				ADMIN_TOKEN,
				"privacy-first-idem-000001",
				"privacy-request-first-0001",
				confirmedBody(clipAssetId, null))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString();
		var firstId = UUID.fromString(JsonPath.read(first, "$.privacy_assessment_id"));

		assess(
				ADMIN_TOKEN,
				"privacy-missing-super-0001",
				"privacy-request-missing-super",
				noneBody(clipAssetId, null))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code")
						.value("PRIVACY_ASSESSMENT_SUPERSESSION_REQUIRED"));

		assess(
				ADMIN_TOKEN,
				"privacy-stale-super-00001",
				"privacy-request-stale-super",
				noneBody(clipAssetId, UUID.randomUUID()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("PRIVACY_ASSESSMENT_STALE"));

		assess(
				ADMIN_TOKEN,
				"privacy-second-idem-00001",
				"privacy-request-second-0001",
				noneBody(clipAssetId, firstId))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.assessment_version").value(2))
				.andExpect(jsonPath("$.supersedes_assessment_id").value(firstId.toString()))
				.andExpect(jsonPath("$.risk_level").value("NONE"))
				.andExpect(jsonPath("$.privacy_release_status").value("BLOCKED"));
	}

	private org.springframework.test.web.servlet.ResultActions assess(
			String token,
			String idempotencyKey,
			String requestId,
			String body) throws Exception {
		return this.mockMvc.perform(post("/api/v1/privacy-assessments")
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", requestId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private static String noneBody(UUID clipAssetId, UUID supersedesAssessmentId) {
		return """
				{
				  "clip_asset_id": "%s",
				  %s
				  "risk_level": "NONE",
				  "required_action": "RELEASE_REVIEW_REQUIRED",
				  "reason_code": "NO_IDENTIFIABLE_INFORMATION_OBSERVED",
				  "finding_types": []
				}
				""".formatted(
				clipAssetId,
				supersedesAssessmentId == null
						? ""
						: "\"supersedes_assessment_id\": \""
								+ supersedesAssessmentId + "\",\n  ");
	}

	private static String confirmedBody(UUID clipAssetId, UUID supersedesAssessmentId) {
		return """
				{
				  "clip_asset_id": "%s",
				  %s
				  "risk_level": "CONFIRMED",
				  "required_action": "DEIDENTIFICATION_REQUIRED",
				  "reason_code": "IDENTIFIABLE_INFORMATION_OBSERVED",
				  "finding_types": ["SCREEN", "FACE"]
				}
				""".formatted(
				clipAssetId,
				supersedesAssessmentId == null
						? ""
						: "\"supersedes_assessment_id\": \""
								+ supersedesAssessmentId + "\",\n  ");
	}

	private UUID insertClipAsset(UUID projectId, String status) {
		var now = Instant.parse("2026-07-17T01:00:00Z");
		var batchId = UUID.randomUUID();
		var uploadFileId = UUID.randomUUID();
		var clientFileId = UUID.randomUUID();
		var clipAssetId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO upload_batch (
				    batch_id, project_id, idempotency_key, request_fingerprint, device_id,
				    initiated_by_actor_id,
				    file_count, total_size_bytes, manifest_sha256, status, expires_at,
				    created_at, updated_at, verified_manifest_sha256, completed_at)
				VALUES (?, ?, ?, ?, 'synthetic-privacy-device', 'synthetic-test-actor',
				        1, 1234, ?, 'COMPLETED', ?, ?, ?, ?, ?)
				""",
				batchId, projectId, "privacy-batch-" + batchId, SHA256, SHA256,
				Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now),
				SHA256, Timestamp.from(now));
		this.jdbcTemplate.update("""
				INSERT INTO upload_file (
				    upload_file_id, batch_id, client_file_id, idempotency_key, request_fingerprint,
				    file_kind, content_type, size_bytes, sha256, part_size_bytes, total_parts,
				    status, created_at, updated_at, storage_bucket, object_key, storage_upload_id,
				    multipart_created_at, completion_started_at, completion_finished_at,
				    verified_sha256, completed_storage_etag)
				VALUES (?, ?, ?, ?, ?, 'VIDEO', 'video/mp4', 1234, ?, 8388608, 1,
				        'UPLOADED', ?, ?, 'synthetic-bucket', ?, ?, ?, ?, ?, ?, 'synthetic-etag')
				""",
				uploadFileId, batchId, clientFileId, "privacy-file-" + uploadFileId, SHA256,
				SHA256, Timestamp.from(now), Timestamp.from(now), "objects/" + uploadFileId,
				"multipart-" + uploadFileId, Timestamp.from(now), Timestamp.from(now),
				Timestamp.from(now), SHA256);
		this.jdbcTemplate.update("""
				INSERT INTO clip_asset (
				    clip_asset_id, project_id, batch_id, source_upload_file_id, client_file_id,
				    content_type, size_bytes, sha256, storage_bucket, object_key, storage_etag,
				    asset_status, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'video/mp4', 1234, ?, 'synthetic-bucket', ?,
				        'synthetic-etag', ?, ?, ?)
				""",
				clipAssetId, projectId, batchId, uploadFileId, clientFileId, SHA256,
				"objects/" + clipAssetId, status, Timestamp.from(now), Timestamp.from(now));
		return clipAssetId;
	}
}
