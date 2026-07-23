package com.medicationvideo.platform.mediaprivacy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
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
class MediaJobContractTests {

	private static final String TOKEN = "synthetic-test-bearer-token-do-not-reuse";
	private static final String PROJECT_ID = "00000000-0000-0000-0000-000000000001";
	private static final String SHA256 = "a".repeat(64);
	private static final String IDEMPOTENCY_KEY = "idem-media-job-00123456789012";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clearState() {
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, UUID.fromString(PROJECT_ID));
		this.jdbcTemplate.update("DELETE FROM outbox_event");
		this.jdbcTemplate.update("DELETE FROM media_job_attempt");
		this.jdbcTemplate.update("DELETE FROM media_report");
		this.jdbcTemplate.update("DELETE FROM media_job");
	}

	@Test
	void projectMemberCanExplicitlyQueueTheMediaProbeForAnUploadedAsset() throws Exception {
		var clipAssetId = insertUploadedClipAsset(UUID.fromString(PROJECT_ID), "video/mp4",
				"UPLOADED", Instant.parse("2026-07-23T12:00:00Z"));

		this.mockMvc.perform(post(
					"/api/v1/projects/{projectId}/clip-assets/{clipAssetId}/media-probe-jobs",
					PROJECT_ID,
					clipAssetId)
				.header("Authorization", "Bearer " + TOKEN)
				.header("Idempotency-Key", "manual-media-probe-001234567890"))
				.andExpect(status().isAccepted())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.project_id").value(PROJECT_ID))
				.andExpect(jsonPath("$.clip_asset_id").value(clipAssetId.toString()))
				.andExpect(jsonPath("$.job_type").value("MEDIA_PROBE"))
				.andExpect(jsonPath("$.status").value("QUEUED"));

		assertEquals(1, this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM media_job WHERE project_id = ? AND clip_asset_id = ?",
				Integer.class,
				UUID.fromString(PROJECT_ID),
				clipAssetId));
	}

	@Test
	void queuesJobAttemptAndOutboxAtomicallyAndReplaysIdempotently() throws Exception {
		var clipAssetId = insertUploadedClipAsset(UUID.fromString(PROJECT_ID), "video/mp4",
				"UPLOADED", Instant.parse("2026-07-15T01:00:00Z"));
		var body = mediaProbeBody(clipAssetId);

		var first = mockMvc.perform(post("/api/v1/media-jobs")
				.header("Authorization", "Bearer " + TOKEN)
				.header("Idempotency-Key", IDEMPOTENCY_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isAccepted())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(header().exists("Location"))
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.job_type").value("MEDIA_PROBE"))
				.andExpect(jsonPath("$.input_digest").value(SHA256))
				.andExpect(jsonPath("$.input_size_bytes").value(1234))
				.andExpect(jsonPath("$.content_type").value("video/mp4"))
				.andExpect(jsonPath("$.probe_profile_key").value("media-probe-baseline-v1"))
				.andExpect(jsonPath("$.recipe_digest").value(MediaProbeProfile.RECIPE_DIGEST))
				.andExpect(jsonPath("$.project_id").value(PROJECT_ID))
				.andReturn()
				.getResponse()
				.getContentAsString();

		var firstJobId = readField(first, "media_job_id");
		assertEquals(1, this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM media_job_attempt WHERE media_job_id = ? AND attempt = 1 AND status = 'QUEUED'",
				Integer.class,
				UUID.fromString(firstJobId)));
		assertEquals(1, this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND published_at IS NULL",
				Integer.class,
				UUID.fromString(firstJobId)));
		assertEquals(0, this.jdbcTemplate.queryForObject("""
				SELECT count(*) FROM outbox_event
				WHERE aggregate_id = ?
				  AND (jsonb_exists(payload_json, 'object_key')
				       OR jsonb_exists(payload_json, 'upload_url')
				       OR jsonb_exists(payload_json, 'storage_bucket'))
				""", Integer.class, UUID.fromString(firstJobId)));

		mockMvc.perform(post("/api/v1/media-jobs")
				.header("Authorization", "Bearer " + TOKEN)
				.header("Idempotency-Key", IDEMPOTENCY_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.media_job_id").value(firstJobId))
				.andExpect(jsonPath("$.status").value("QUEUED"));
		assertEquals(1, this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM media_job_attempt WHERE media_job_id = ?",
				Integer.class,
				UUID.fromString(firstJobId)));
		assertEquals(1, this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM outbox_event WHERE aggregate_id = ?",
				Integer.class,
				UUID.fromString(firstJobId)));
	}

	@Test
	void rejectsIdempotencyKeyReuseWithDifferentBody() throws Exception {
		var clipAssetA = insertUploadedClipAsset(UUID.fromString(PROJECT_ID), "video/mp4",
				"UPLOADED", Instant.parse("2026-07-15T01:00:00Z"));
		var clipAssetB = insertUploadedClipAsset(UUID.fromString(PROJECT_ID), "video/mp4",
				"UPLOADED", Instant.parse("2026-07-15T01:01:00Z"));

		mockMvc.perform(post("/api/v1/media-jobs")
				.header("Authorization", "Bearer " + TOKEN)
				.header("Idempotency-Key", IDEMPOTENCY_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content(mediaProbeBody(clipAssetA)))
				.andExpect(status().isAccepted());

		mockMvc.perform(post("/api/v1/media-jobs")
				.header("Authorization", "Bearer " + TOKEN)
				.header("Idempotency-Key", IDEMPOTENCY_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content(mediaProbeBody(clipAssetB)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("IDEMPOTENCY_KEY_REUSED"));
	}

	@Test
	void returnsExistingJobForSameNaturalKeyWithDifferentIdempotencyKey() throws Exception {
		var clipAssetId = insertUploadedClipAsset(UUID.fromString(PROJECT_ID), "video/mp4",
				"UPLOADED", Instant.parse("2026-07-15T01:00:00Z"));
		var body = mediaProbeBody(clipAssetId);

		var first = mockMvc.perform(post("/api/v1/media-jobs")
				.header("Authorization", "Bearer " + TOKEN)
				.header("Idempotency-Key", "idem-first-key-123456789012")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isAccepted())
				.andReturn()
				.getResponse()
				.getContentAsString();
		var firstJobId = readField(first, "media_job_id");

		mockMvc.perform(post("/api/v1/media-jobs")
				.header("Authorization", "Bearer " + TOKEN)
				.header("Idempotency-Key", "idem-second-key-12345678901")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.media_job_id").value(firstJobId))
				.andExpect(jsonPath("$.status").value("QUEUED"));
	}

	@Test
	void returnsNotFoundForUnknownClipAsset() throws Exception {
		mockMvc.perform(post("/api/v1/media-jobs")
				.header("Authorization", "Bearer " + TOKEN)
				.header("Idempotency-Key", IDEMPOTENCY_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content(mediaProbeBody(UUID.randomUUID())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error_code").value("CLIP_ASSET_NOT_FOUND"));
		assertEquals(0, this.jdbcTemplate.queryForObject("SELECT count(*) FROM media_job", Integer.class));
		assertEquals(0, this.jdbcTemplate.queryForObject("SELECT count(*) FROM media_job_attempt", Integer.class));
		assertEquals(0, this.jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_event", Integer.class));
	}

	@Test
	void rejectsNonProbeableClipAssetState() throws Exception {
		var clipAssetId = insertUploadedClipAsset(UUID.fromString(PROJECT_ID), "video/mp4",
				"INGESTED", Instant.parse("2026-07-15T01:00:00Z"));

		mockMvc.perform(post("/api/v1/media-jobs")
				.header("Authorization", "Bearer " + TOKEN)
				.header("Idempotency-Key", IDEMPOTENCY_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content(mediaProbeBody(clipAssetId)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("CLIP_ASSET_NOT_PROBEABLE"));
	}

	@Test
	void rejectsNonVideoContentType() throws Exception {
		var clipAssetId = insertUploadedClipAsset(UUID.fromString(PROJECT_ID), "image/png",
				"UPLOADED", Instant.parse("2026-07-15T01:00:00Z"));

		mockMvc.perform(post("/api/v1/media-jobs")
				.header("Authorization", "Bearer " + TOKEN)
				.header("Idempotency-Key", IDEMPOTENCY_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content(mediaProbeBody(clipAssetId)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("CLIP_ASSET_NOT_PROBEABLE"));
	}

	@Test
	void getReturnsPersistedJob() throws Exception {
		var clipAssetId = insertUploadedClipAsset(UUID.fromString(PROJECT_ID), "video/mp4",
				"UPLOADED", Instant.parse("2026-07-15T01:00:00Z"));
		var createResponse = mockMvc.perform(post("/api/v1/media-jobs")
				.header("Authorization", "Bearer " + TOKEN)
				.header("Idempotency-Key", IDEMPOTENCY_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content(mediaProbeBody(clipAssetId)))
				.andExpect(status().isAccepted())
				.andReturn()
				.getResponse()
				.getContentAsString();
		var jobId = readField(createResponse, "media_job_id");

		mockMvc.perform(get("/api/v1/media-jobs/" + jobId)
				.header("Authorization", "Bearer " + TOKEN))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.media_job_id").value(jobId))
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.job_type").value("MEDIA_PROBE"));
	}

	@Test
	void getReturnsNotFoundForUnknownJobId() throws Exception {
		mockMvc.perform(get("/api/v1/media-jobs/" + UUID.randomUUID())
				.header("Authorization", "Bearer " + TOKEN))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error_code").value("MEDIA_JOB_NOT_FOUND"));
	}

	@Test
	void rejectsMissingIdempotencyKey() throws Exception {
		var clipAssetId = insertUploadedClipAsset(UUID.fromString(PROJECT_ID), "video/mp4",
				"UPLOADED", Instant.parse("2026-07-15T01:00:00Z"));

		mockMvc.perform(post("/api/v1/media-jobs")
				.header("Authorization", "Bearer " + TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content(mediaProbeBody(clipAssetId)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void migrationFreezesRecipeIdentityAndScientificMediaReportFields() {
		var recipeColumns = this.jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM information_schema.columns
				WHERE table_schema = 'public'
				  AND table_name = 'media_job'
				  AND column_name IN ('probe_profile_key', 'recipe_digest')
				""", Integer.class);
		assertEquals(2, recipeColumns);

		var reportColumns = this.jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM information_schema.columns
				WHERE table_schema = 'public'
				  AND table_name = 'media_report'
				  AND column_name IN (
				      'report_schema_version', 'input_digest', 'recipe_digest', 'software_digest',
				      'video_stream_count', 'primary_video_stream_index', 'pixel_format',
				      'time_base_numerator', 'time_base_denominator', 'first_pts', 'last_pts',
				      'nominal_frame_rate_numerator', 'nominal_frame_rate_denominator',
				      'average_frame_rate_numerator', 'average_frame_rate_denominator',
				      'frame_count', 'variable_frame_rate', 'decode_verified',
				      'input_digest_verified', 'warning_codes')
				""", Integer.class);
		assertEquals(20, reportColumns);

		var dispatchTables = this.jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM information_schema.tables
				WHERE table_schema = 'public'
				  AND table_name IN ('media_job_attempt', 'outbox_event')
				""", Integer.class);
		assertEquals(2, dispatchTables);
	}

	private static String mediaProbeBody(UUID clipAssetId) {
		return """
				{
				  "clip_asset_id": "%s",
				  "job_type": "MEDIA_PROBE"
				}
				""".formatted(clipAssetId);
	}

	private static String readField(String json, String field) {
		return (String) JsonPath.read(json, "$." + field);
	}

	private UUID insertUploadedClipAsset(UUID projectId, String contentType, String status,
			Instant createdAt) {
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
				VALUES (?, ?, ?, ?, 'synthetic-device', 'synthetic-test-actor',
				        1, 1234, ?, 'COMPLETED', ?, ?, ?, ?, ?)
				""",
				batchId, projectId, "mediajob-batch-" + batchId, SHA256, SHA256,
				Timestamp.from(createdAt.plusSeconds(3600)), Timestamp.from(createdAt),
				Timestamp.from(createdAt), SHA256, Timestamp.from(createdAt));
		this.jdbcTemplate.update("""
				INSERT INTO upload_file (
				    upload_file_id, batch_id, client_file_id, idempotency_key, request_fingerprint,
				    file_kind, content_type, size_bytes, sha256, part_size_bytes, total_parts,
				    status, created_at, updated_at, storage_bucket, object_key, storage_upload_id,
				    multipart_created_at, completion_started_at, completion_finished_at,
				    verified_sha256, completed_storage_etag)
				VALUES (?, ?, ?, ?, ?, 'VIDEO', ?, 1234, ?, 8388608, 1, 'UPLOADED', ?, ?,
				        'synthetic-bucket', ?, ?, ?, ?, ?, ?, 'synthetic-etag')
				""",
				uploadFileId, batchId, clientFileId, "mediajob-file-" + uploadFileId, SHA256,
				contentType, SHA256, Timestamp.from(createdAt), Timestamp.from(createdAt),
				"objects/" + uploadFileId, "multipart-" + uploadFileId, Timestamp.from(createdAt),
				Timestamp.from(createdAt), Timestamp.from(createdAt), SHA256);
		this.jdbcTemplate.update("""
				INSERT INTO clip_asset (
				    clip_asset_id, project_id, batch_id, source_upload_file_id, client_file_id,
				    content_type, size_bytes, sha256, storage_bucket, object_key, storage_etag,
				    asset_status, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, 1234, ?, 'synthetic-bucket', ?, 'synthetic-etag',
				        ?, ?, ?)
				""",
				clipAssetId, projectId, batchId, uploadFileId, clientFileId, contentType, SHA256,
				"objects/" + clipAssetId, status, Timestamp.from(createdAt),
				Timestamp.from(createdAt));
		return clipAssetId;
	}
}
