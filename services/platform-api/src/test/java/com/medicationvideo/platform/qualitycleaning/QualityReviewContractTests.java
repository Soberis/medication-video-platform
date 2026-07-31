package com.medicationvideo.platform.qualitycleaning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
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

import com.medicationvideo.platform.support.ProjectMembershipTestData;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QualityReviewContractTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000400");

	private static final UUID OTHER_PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000499");

	private static final String MEMBER_TOKEN = "synthetic-test-bearer-token-do-not-reuse";

	private static final String SHA256 = "a".repeat(64);

	private static final Instant BASE_TIME = Instant.parse("2026-07-22T01:00:00Z");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		clearState(PROJECT_ID);
		clearState(OTHER_PROJECT_ID);
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, PROJECT_ID);
	}

	@AfterEach
	void tearDown() {
		clearState(PROJECT_ID);
		clearState(OTHER_PROJECT_ID);
	}

	@Test
	void memberRecordsAndReadsAQualifiedConclusionWithAuditEvidence() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		var requestId = "quality-qualified-" + UUID.randomUUID();

		this.mockMvc.perform(get(path(), PROJECT_ID, clipAssetId).header("Authorization", "Bearer " + MEMBER_TOKEN))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.decision").value("UNREVIEWED"))
			.andExpect(jsonPath("$.version").value(0));

		this.mockMvc
			.perform(put(path(), PROJECT_ID, clipAssetId).header("Authorization", "Bearer " + MEMBER_TOKEN)
				.header("X-Request-Id", requestId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"decision":"QUALIFIED","reason_code":"MEETS_REQUIREMENTS","expected_version":0}
						"""))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.decision").value("QUALIFIED"))
			.andExpect(jsonPath("$.reason_code").value("MEETS_REQUIREMENTS"))
			.andExpect(jsonPath("$.reviewed_by").value(ProjectMembershipTestData.MEMBER_ACTOR))
			.andExpect(jsonPath("$.version").value(0));

		assertEquals(1, count("SELECT count(*) FROM quality_review WHERE clip_asset_id = ?", clipAssetId));
		assertEquals(1, count("""
				SELECT count(*) FROM audit_event
				WHERE request_id = ?
				  AND action = 'CLIP_ASSET_QUALITY_REVIEWED'
				  AND reason_code = 'MEETS_REQUIREMENTS'
				  AND authorization_reference = 'PROJECT_MEMBERSHIP_MEMBER'
				""", requestId));
	}

	@Test
	void rejectsInvalidReasonAndAssetsThatAreNotReady() throws Exception {
		var ingestedAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		var uploadedAssetId = insertClipAsset(PROJECT_ID, "UPLOADED", false);

		record(ingestedAssetId, "REJECTED", "MEETS_REQUIREMENTS", 0).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error_code").value("INVALID_QUALITY_REASON"));

		record(uploadedAssetId, "NEEDS_REVIEW", "UNCERTAIN_QUALITY", 0).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("QUALITY_REVIEW_NOT_READY"));

		assertEquals(0, count("SELECT count(*) FROM quality_review WHERE project_id = ?", PROJECT_ID));
	}

	@Test
	void sameConclusionIsIdempotentAndStaleRevisionIsRejected() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);

		record(clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS", 0).andExpect(status().isOk())
			.andExpect(jsonPath("$.version").value(0));
		record(clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS", 0).andExpect(status().isOk())
			.andExpect(jsonPath("$.version").value(0));
		record(clipAssetId, "REJECTED", "WRONG_SCENE", 0).andExpect(status().isOk())
			.andExpect(jsonPath("$.version").value(1));
		record(clipAssetId, "NEEDS_REVIEW", "UNCERTAIN_QUALITY", 0).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("QUALITY_REVIEW_CONFLICT"));

		assertEquals(2, count("""
				SELECT count(*) FROM audit_event
				WHERE object_type = 'QualityReview'
				  AND details_json ->> 'clip_asset_id' = ?
				""", clipAssetId.toString()));
	}

	@Test
	void deletedAndCrossProjectAssetsRemainHidden() throws Exception {
		var deletedAssetId = insertClipAsset(PROJECT_ID, "INGESTED", true);
		var otherAssetId = insertClipAsset(OTHER_PROJECT_ID, "INGESTED", false);

		this.mockMvc.perform(get(path(), PROJECT_ID, deletedAssetId).header("Authorization", "Bearer " + MEMBER_TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error_code").value("CLIP_ASSET_NOT_FOUND"));
		this.mockMvc.perform(get(path(), PROJECT_ID, otherAssetId).header("Authorization", "Bearer " + MEMBER_TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error_code").value("CLIP_ASSET_NOT_FOUND"));
	}

	private org.springframework.test.web.servlet.ResultActions record(UUID clipAssetId, String decision,
			String reasonCode, long expectedVersion) throws Exception {
		return this.mockMvc
			.perform(put(path(), PROJECT_ID, clipAssetId).header("Authorization", "Bearer " + MEMBER_TOKEN)
				.header("X-Request-Id", "quality-review-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"decision":"%s","reason_code":"%s","expected_version":%d}
						""".formatted(decision, reasonCode, expectedVersion)));
	}

	private UUID insertClipAsset(UUID projectId, String status, boolean deleted) {
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, projectId);
		var batchId = UUID.randomUUID();
		var uploadFileId = UUID.randomUUID();
		var clientFileId = UUID.randomUUID();
		var clipAssetId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO upload_batch (
				    batch_id, project_id, idempotency_key, request_fingerprint, device_id,
				    initiated_by_actor_id, file_count, total_size_bytes, manifest_sha256,
				    status, expires_at, created_at, updated_at, verified_manifest_sha256, completed_at)
				VALUES (?, ?, ?, ?, 'synthetic-quality-device', ?, 1, 1234, ?,
				        'COMPLETED', ?, ?, ?, ?, ?)
				""", batchId, projectId, "quality-batch-" + batchId, SHA256, ProjectMembershipTestData.ADMIN_ACTOR,
				SHA256, Timestamp.from(BASE_TIME.plusSeconds(3600)), Timestamp.from(BASE_TIME),
				Timestamp.from(BASE_TIME), SHA256, Timestamp.from(BASE_TIME));
		this.jdbcTemplate.update("""
				INSERT INTO upload_file (
				    upload_file_id, batch_id, client_file_id, idempotency_key, request_fingerprint,
				    file_kind, content_type, size_bytes, sha256, part_size_bytes, total_parts,
				    status, created_at, updated_at, storage_bucket, object_key, storage_upload_id,
				    multipart_created_at, completion_started_at, completion_finished_at,
				    verified_sha256, completed_storage_etag)
				VALUES (?, ?, ?, ?, ?, 'VIDEO', 'video/mp4', 1234, ?, 8388608, 1,
				        'UPLOADED', ?, ?, 'synthetic-bucket', ?, ?, ?, ?, ?, ?, 'synthetic-etag')
				""", uploadFileId, batchId, clientFileId, "quality-file-" + uploadFileId, SHA256, SHA256,
				Timestamp.from(BASE_TIME), Timestamp.from(BASE_TIME), "objects/" + uploadFileId,
				"multipart-" + uploadFileId, Timestamp.from(BASE_TIME), Timestamp.from(BASE_TIME),
				Timestamp.from(BASE_TIME), SHA256);
		this.jdbcTemplate.update("""
				INSERT INTO clip_asset (
				    clip_asset_id, project_id, batch_id, source_upload_file_id, client_file_id,
				    content_type, size_bytes, sha256, storage_bucket, object_key, storage_etag,
				    asset_status, deleted_at, deleted_by, delete_reason, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, ?, 'video/mp4', 1234, ?, 'synthetic-bucket', ?, 'synthetic-etag',
				        ?, ?, ?, ?, ?, ?, 0)
				""", clipAssetId, projectId, batchId, uploadFileId, clientFileId, SHA256, "objects/" + clipAssetId,
				status, deleted ? Timestamp.from(BASE_TIME) : null,
				deleted ? ProjectMembershipTestData.ADMIN_ACTOR : null, deleted ? "synthetic deletion" : null,
				Timestamp.from(BASE_TIME), Timestamp.from(BASE_TIME));
		return clipAssetId;
	}

	private int count(String sql, Object... arguments) {
		return this.jdbcTemplate.queryForObject(sql, Integer.class, arguments);
	}

	private void clearState(UUID projectId) {
		this.jdbcTemplate.update("DELETE FROM quality_review WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM clip_asset WHERE project_id = ?", projectId);
		this.jdbcTemplate.update(
				"DELETE FROM upload_file WHERE batch_id IN (SELECT batch_id FROM upload_batch WHERE project_id = ?)",
				projectId);
		this.jdbcTemplate.update("DELETE FROM upload_batch WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM membership WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM project WHERE project_id = ?", projectId);
	}

	private static String path() {
		return "/api/v1/projects/{projectId}/clip-assets/{clipAssetId}/quality-review";
	}

}
