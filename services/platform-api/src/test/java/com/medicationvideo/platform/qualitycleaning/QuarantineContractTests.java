package com.medicationvideo.platform.qualitycleaning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuarantineContractTests {

	private static final String USER_TOKEN = "synthetic-test-bearer-token-do-not-reuse";
	private static final String ADMIN_TOKEN = "synthetic-test-admin-bearer-token-do-not-reuse";
	private static final UUID PROJECT_ID =
			UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final String SHA256 = "d".repeat(64);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private QuarantineCommandService quarantineCommandService;

	@MockitoSpyBean
	private QuarantineRecordWriter quarantineRecordWriter;

	@BeforeEach
	void clearQuarantineEvidence() {
		this.jdbcTemplate.update("DELETE FROM quarantine_record");
		this.jdbcTemplate.update("DELETE FROM privacy_finding");
		this.jdbcTemplate.update("DELETE FROM privacy_assessment");
	}

	@Test
	void reportsNoneWithoutTreatingItAsPrivacyRelease() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED");

		this.mockMvc.perform(get("/api/v1/quarantines")
					.header("Authorization", "Bearer " + ADMIN_TOKEN)
					.queryParam("clip_asset_id", clipAssetId.toString()))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.clip_asset_id").value(clipAssetId.toString()))
				.andExpect(jsonPath("$.input_digest").value(SHA256))
				.andExpect(jsonPath("$.quarantine_status").value("NONE"))
				.andExpect(jsonPath("$.active_records").isEmpty());
	}

	@Test
	void activatesPrivacyQuarantineAtomicallyAndIdempotently() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED");
		var assessmentBody = quarantineBody(clipAssetId, null);
		var response = assess(
				"quarantine-privacy-idem-0001",
				"quarantine-request-0001",
				assessmentBody)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.required_action").value("QUARANTINE_REQUIRED"))
				.andExpect(jsonPath("$.privacy_release_status").value("BLOCKED"))
				.andReturn()
				.getResponse()
				.getContentAsString();
		var assessmentId = UUID.fromString(JsonPath.read(response, "$.privacy_assessment_id"));

		this.mockMvc.perform(get("/api/v1/quarantines")
					.header("Authorization", "Bearer " + ADMIN_TOKEN)
					.queryParam("clip_asset_id", clipAssetId.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.quarantine_status").value("ACTIVE"))
				.andExpect(jsonPath("$.active_records.length()").value(1))
				.andExpect(jsonPath("$.active_records[0].reason_code").value("PRIVACY_RISK"))
				.andExpect(jsonPath("$.active_records[0].source_type")
						.value("PRIVACY_ASSESSMENT"))
				.andExpect(jsonPath("$.active_records[0].source_id")
						.value(assessmentId.toString()))
				.andExpect(jsonPath("$.active_records[0].source_version").value(1))
				.andExpect(jsonPath("$.active_records[0].activated_by")
						.value("local-synthetic-admin"));

		assess(
				"quarantine-privacy-idem-0001",
				"quarantine-request-retry-0001",
				assessmentBody)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.privacy_assessment_id").value(assessmentId.toString()));

		assertEquals(1, this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM quarantine_record WHERE source_id = ?",
				Integer.class,
				assessmentId));
		assertEquals(1, this.jdbcTemplate.queryForObject(
				"""
				SELECT count(*) FROM audit_event
				WHERE action = 'QUARANTINE_ACTIVATED'
				  AND details_json ->> 'source_id' = ?
				""",
				Integer.class,
				assessmentId.toString()));
	}

	@Test
	void concurrentActivationOfTheSameSourceCreatesOneRecordAndAudit() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED");
		var assessmentId = UUID.randomUUID();
		insertPrivacyAssessmentSource(clipAssetId, assessmentId);
		var command = QuarantineActivationCommand.forPrivacyAssessment(
				PROJECT_ID,
				clipAssetId,
				SHA256,
				assessmentId,
				1,
				"synthetic-quarantine-concurrency",
				"quarantine-concurrency-request",
				null);
		var concurrency = 8;
		var insertReady = new CountDownLatch(concurrency);
		var insertOutcomes = new ConcurrentLinkedQueue<Boolean>();
		doAnswer(invocation -> {
			insertReady.countDown();
			if (!insertReady.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException(
						"All concurrent activations did not reach the insert boundary.");
			}
			var inserted = (boolean) invocation.callRealMethod();
			insertOutcomes.add(inserted);
			return inserted;
		}).when(this.quarantineRecordWriter)
				.insertIfAbsent(any(QuarantineRecord.class));
		var ready = new CountDownLatch(concurrency);
		var start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(concurrency);
		try {
			var futures = new ArrayList<java.util.concurrent.Future<Void>>();
			for (var index = 0; index < concurrency; index++) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					if (!start.await(10, TimeUnit.SECONDS)) {
						throw new IllegalStateException("Concurrent activation did not start in time.");
					}
					this.quarantineCommandService.activate(command);
					return null;
				}));
			}
			assertTrue(ready.await(10, TimeUnit.SECONDS));
			start.countDown();
			for (var future : futures) {
				future.get(20, TimeUnit.SECONDS);
			}
		}
		finally {
			executor.shutdownNow();
		}

		assertEquals(concurrency, insertOutcomes.size());
		assertEquals(1L, insertOutcomes.stream().filter(Boolean::booleanValue).count());
		assertEquals(
				concurrency - 1L,
				insertOutcomes.stream().filter(inserted -> !inserted).count());
		assertEquals(1, this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM quarantine_record WHERE source_id = ?",
				Integer.class,
				assessmentId));
		assertEquals(1, this.jdbcTemplate.queryForObject(
				"""
				SELECT count(*) FROM audit_event
				WHERE action = 'QUARANTINE_ACTIVATED'
				  AND details_json ->> 'source_id' = ?
				""",
				Integer.class,
				assessmentId.toString()));
	}

	@Test
	void reassessmentDoesNotSilentlyReleaseExistingQuarantine() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED");
		var first = assess(
				"quarantine-first-idem-00001",
				"quarantine-first-request-01",
				quarantineBody(clipAssetId, null))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString();
		var firstAssessmentId = UUID.fromString(JsonPath.read(first, "$.privacy_assessment_id"));

		assess(
				"quarantine-second-idem-0001",
				"quarantine-second-request-1",
				noneBody(clipAssetId, firstAssessmentId))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.risk_level").value("NONE"))
				.andExpect(jsonPath("$.privacy_release_status").value("BLOCKED"));

		this.mockMvc.perform(get("/api/v1/quarantines")
					.header("Authorization", "Bearer " + ADMIN_TOKEN)
					.queryParam("clip_asset_id", clipAssetId.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.quarantine_status").value("ACTIVE"))
				.andExpect(jsonPath("$.active_records.length()").value(1))
				.andExpect(jsonPath("$.active_records[0].source_id")
						.value(firstAssessmentId.toString()));
	}

	@Test
	void nonQuarantineAssessmentDoesNotCreateARecord() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED");

		assess(
				"quarantine-none-idem-000001",
				"quarantine-none-request-001",
				noneBody(clipAssetId, null))
				.andExpect(status().isCreated());

		this.mockMvc.perform(get("/api/v1/quarantines")
					.header("Authorization", "Bearer " + ADMIN_TOKEN)
					.queryParam("clip_asset_id", clipAssetId.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.quarantine_status").value("NONE"));
		assertEquals(0, this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM quarantine_record WHERE clip_asset_id = ?",
				Integer.class,
				clipAssetId));
	}

	@Test
	void enforcesAdministratorProjectScopeAndStableRequestErrors() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED");
		this.mockMvc.perform(get("/api/v1/quarantines")
					.header("Authorization", "Bearer " + USER_TOKEN)
					.queryParam("clip_asset_id", clipAssetId.toString()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("PROJECT_ACCESS_DENIED"));

		var otherProjectAsset = insertClipAsset(UUID.randomUUID(), "INGESTED");
		this.mockMvc.perform(get("/api/v1/quarantines")
					.header("Authorization", "Bearer " + ADMIN_TOKEN)
					.queryParam("clip_asset_id", otherProjectAsset.toString()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error_code").value("CLIP_ASSET_NOT_FOUND"));

		this.mockMvc.perform(get("/api/v1/quarantines")
					.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error_code").value("INVALID_REQUEST"));
		this.mockMvc.perform(get("/api/v1/quarantines")
					.header("Authorization", "Bearer " + ADMIN_TOKEN)
					.queryParam("clip_asset_id", "not-a-uuid"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error_code").value("INVALID_REQUEST"));
	}

	private org.springframework.test.web.servlet.ResultActions assess(
			String idempotencyKey,
			String requestId,
			String body) throws Exception {
		return this.mockMvc.perform(post("/api/v1/privacy-assessments")
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", requestId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private static String quarantineBody(UUID clipAssetId, UUID supersedesAssessmentId) {
		return """
				{
				  "clip_asset_id": "%s",
				  %s
				  "risk_level": "SUSPECTED",
				  "required_action": "QUARANTINE_REQUIRED",
				  "reason_code": "RISK_REQUIRES_ISOLATION",
				  "finding_types": ["FACE"]
				}
				""".formatted(
				clipAssetId,
				supersedesAssessmentId == null
						? ""
						: "\"supersedes_assessment_id\": \""
								+ supersedesAssessmentId + "\",\n  ");
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

	private void insertPrivacyAssessmentSource(UUID clipAssetId, UUID assessmentId) {
		var assessedAt = Timestamp.from(Instant.parse("2026-07-17T02:05:00Z"));
		this.jdbcTemplate.update("""
				INSERT INTO privacy_assessment (
				    privacy_assessment_id, project_id, clip_asset_id, input_digest,
				    assessment_version, assessment_method, policy_key, policy_digest,
				    risk_level, required_action, reason_code, assessed_by,
				    supersedes_assessment_id, idempotency_key, request_fingerprint,
				    assessed_at)
				VALUES (?, ?, ?, ?, 1, 'HUMAN_REVIEW', 'privacy-assessment-policy-v1', ?,
				        'SUSPECTED', 'QUARANTINE_REQUIRED', 'RISK_REQUIRES_ISOLATION',
				        'synthetic-quarantine-concurrency', NULL, ?, ?, ?)
				""",
				assessmentId,
				PROJECT_ID,
				clipAssetId,
				SHA256,
				"d5d18c436d401a223832f1c85b6ff076de01a9dddeecb879268ff89bc5719517",
				"quarantine-source-" + assessmentId,
				"e".repeat(64),
				assessedAt);
		this.jdbcTemplate.update(
				"INSERT INTO privacy_finding (privacy_assessment_id, finding_type) VALUES (?, 'FACE')",
				assessmentId);
	}

	private UUID insertClipAsset(UUID projectId, String statusValue) {
		var now = Instant.parse("2026-07-17T02:00:00Z");
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
				VALUES (?, ?, ?, ?, 'synthetic-quarantine-device', 'synthetic-test-actor',
				        1, 1234, ?, 'COMPLETED', ?, ?, ?, ?, ?)
				""",
				batchId, projectId, "quarantine-batch-" + batchId, SHA256, SHA256,
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
				uploadFileId, batchId, clientFileId, "quarantine-file-" + uploadFileId, SHA256,
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
				"objects/" + clipAssetId, statusValue, Timestamp.from(now), Timestamp.from(now));
		return clipAssetId;
	}
}
