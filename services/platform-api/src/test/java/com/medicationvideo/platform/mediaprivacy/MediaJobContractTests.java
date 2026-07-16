package com.medicationvideo.platform.mediaprivacy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
		this.jdbcTemplate.update("DELETE FROM media_report");
		this.jdbcTemplate.update("DELETE FROM media_job");
	}

	@Test
	void createsRequestedJobAndReplaysIdempotently() throws Exception {
		var clipAssetId = insertUploadedClipAsset(UUID.fromString(PROJECT_ID), "video/mp4",
				"UPLOADED", Instant.parse("2026-07-15T01:00:00Z"));
		var body = mediaProbeBody(clipAssetId);

		var first = mockMvc.perform(post("/api/v1/media-jobs")
				.header("Authorization", "Bearer " + TOKEN)
				.header("Idempotency-Key", IDEMPOTENCY_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(header().exists("Location"))
				.andExpect(jsonPath("$.status").value("REQUESTED"))
				.andExpect(jsonPath("$.job_type").value("MEDIA_PROBE"))
				.andExpect(jsonPath("$.input_digest").value(SHA256))
				.andExpect(jsonPath("$.input_size_bytes").value(1234))
				.andExpect(jsonPath("$.content_type").value("video/mp4"))
				.andExpect(jsonPath("$.project_id").value(PROJECT_ID))
				.andReturn()
				.getResponse()
				.getContentAsString();

		var firstJobId = readField(first, "media_job_id");

		mockMvc.perform(post("/api/v1/media-jobs")
				.header("Authorization", "Bearer " + TOKEN)
				.header("Idempotency-Key", IDEMPOTENCY_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.media_job_id").value(firstJobId))
				.andExpect(jsonPath("$.status").value("REQUESTED"));
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
				.andExpect(status().isCreated());

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
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString();
		var firstJobId = readField(first, "media_job_id");

		mockMvc.perform(post("/api/v1/media-jobs")
				.header("Authorization", "Bearer " + TOKEN)
				.header("Idempotency-Key", "idem-second-key-12345678901")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.media_job_id").value(firstJobId))
				.andExpect(jsonPath("$.status").value("REQUESTED"));
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
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString();
		var jobId = readField(createResponse, "media_job_id");

		mockMvc.perform(get("/api/v1/media-jobs/" + jobId)
				.header("Authorization", "Bearer " + TOKEN))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.media_job_id").value(jobId))
				.andExpect(jsonPath("$.status").value("REQUESTED"))
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
				    file_count, total_size_bytes, manifest_sha256, status, expires_at,
				    created_at, updated_at, verified_manifest_sha256, completed_at)
				VALUES (?, ?, ?, ?, 'synthetic-device', 1, 1234, ?, 'COMPLETED', ?, ?, ?, ?, ?)
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
