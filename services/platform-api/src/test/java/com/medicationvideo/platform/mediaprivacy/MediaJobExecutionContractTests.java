package com.medicationvideo.platform.mediaprivacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
class MediaJobExecutionContractTests {

	private static final String USER_TOKEN = "synthetic-test-bearer-token-do-not-reuse";
	private static final String WORKER_TOKEN = "synthetic-media-worker-bearer-token-do-not-reuse";
	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final String SHA256 = "a".repeat(64);
	private static final String SOFTWARE_DIGEST =
			"sha256:70a21ff3417284610935fd3a530cabadb063660666df4e9cf12ea57e76cf78fc";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clearMediaState() {
		this.jdbcTemplate.update("DELETE FROM inbox_message");
		this.jdbcTemplate.update("DELETE FROM outbox_event");
		this.jdbcTemplate.update("DELETE FROM media_job_attempt");
		this.jdbcTemplate.update("DELETE FROM media_report");
		this.jdbcTemplate.update("DELETE FROM media_job");
	}

	@Test
	void acquiresInputAcceptsSuccessIdempotentlyAndExposesImmutableReport() throws Exception {
		var created = createQueuedJob();
		acquire(created)
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.job_id").value(created.jobId().toString()))
				.andExpect(jsonPath("$.attempt").value(1))
				.andExpect(jsonPath("$.input_url").isString())
				.andExpect(jsonPath("$.input_sha256").value(SHA256));

		assertEquals("RUNNING", scalar(
				"SELECT status FROM media_job WHERE media_job_id = ?", created.jobId()));
		assertEquals("RUNNING", scalar(
				"SELECT status FROM media_job_attempt WHERE media_job_id = ?", created.jobId()));
		assertEquals("INGESTING", scalar(
				"SELECT asset_status FROM clip_asset WHERE clip_asset_id = ?", created.clipAssetId()));

		var result = successResult(created, SOFTWARE_DIGEST);
		var firstAcceptedAt = JsonPath.<String>read(submitResult(created, result)
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.status").value("SUCCEEDED"))
				.andReturn().getResponse().getContentAsString(), "$.accepted_at");

		var replayAcceptedAt = JsonPath.<String>read(submitResult(created, result)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("SUCCEEDED"))
				.andReturn().getResponse().getContentAsString(), "$.accepted_at");
		assertEquals(firstAcceptedAt, replayAcceptedAt);
		assertEquals(1, count("SELECT count(*) FROM inbox_message"));
		assertEquals(1, count("SELECT count(*) FROM media_report"));
		assertEquals("SUCCEEDED", scalar(
				"SELECT status FROM media_job WHERE media_job_id = ?", created.jobId()));
		assertEquals("INGESTED", scalar(
				"SELECT asset_status FROM clip_asset WHERE clip_asset_id = ?", created.clipAssetId()));

		this.mockMvc.perform(get("/api/v1/media-jobs/{jobId}/report", created.jobId())
				.header("Authorization", "Bearer " + USER_TOKEN))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.media_job_id").value(created.jobId().toString()))
				.andExpect(jsonPath("$.clip_asset_id").value(created.clipAssetId().toString()))
				.andExpect(jsonPath("$.software_digest").value(SOFTWARE_DIGEST))
				.andExpect(jsonPath("$.duration_microseconds").value(2_000_000))
				.andExpect(jsonPath("$.time_base_numerator").value(1))
				.andExpect(jsonPath("$.time_base_denominator").value(15_360))
				.andExpect(jsonPath("$.decode_verified").value(true))
				.andExpect(jsonPath("$.input_digest_verified").value(true))
				.andExpect(jsonPath("$.has_b_frames").value(true))
				.andExpect(jsonPath("$.has_bframes").doesNotExist())
				.andExpect(jsonPath("$.warning_codes[0]").value("AUDIO_PRESENT"))
				.andExpect(jsonPath("$.input_url").doesNotExist())
				.andExpect(jsonPath("$.object_key").doesNotExist());
	}

	@Test
	void persistsStableFailureWithoutCreatingMediaReport() throws Exception {
		var created = createQueuedJob();
		acquire(created).andExpect(status().isOk());

		submitResult(created, failedResult(created))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"));

		assertEquals(0, count("SELECT count(*) FROM media_report"));
		assertEquals("FAILED", scalar(
				"SELECT status FROM media_job WHERE media_job_id = ?", created.jobId()));
		assertEquals("MEDIA_DECODE_FAILED", scalar(
				"SELECT error_code FROM media_job_attempt WHERE media_job_id = ?", created.jobId()));
		assertEquals("INGEST_FAILED", scalar(
				"SELECT asset_status FROM clip_asset WHERE clip_asset_id = ?", created.clipAssetId()));

		this.mockMvc.perform(get("/api/v1/media-jobs/{jobId}", created.jobId())
				.header("Authorization", "Bearer " + USER_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.current_attempt.status").value("FAILED"))
				.andExpect(jsonPath("$.current_attempt.error.error_class")
						.value("NON_RETRYABLE_INPUT"))
				.andExpect(jsonPath("$.current_attempt.error.error_code")
						.value("MEDIA_DECODE_FAILED"))
				.andExpect(jsonPath("$.current_attempt.error.retryable").value(false));

		this.mockMvc.perform(get("/api/v1/media-jobs/{jobId}/report", created.jobId())
				.header("Authorization", "Bearer " + USER_TOKEN))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error_code").value("MEDIA_REPORT_NOT_FOUND"));
	}

	@Test
	void rejectsUnapprovedWorkerSoftwareWithoutAcceptingResult() throws Exception {
		var created = createQueuedJob();
		acquire(created).andExpect(status().isOk());

		submitResult(created, successResult(created, "sha256:" + "f".repeat(64)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("MEDIA_WORKER_SOFTWARE_UNAPPROVED"));

		assertEquals(0, count("SELECT count(*) FROM inbox_message"));
		assertEquals(0, count("SELECT count(*) FROM media_report"));
		assertEquals("RUNNING", scalar(
				"SELECT status FROM media_job WHERE media_job_id = ?", created.jobId()));
	}

	@Test
	void rejectsInvalidLeaseAndWorkerIdentityWithoutChangingJob() throws Exception {
		var created = createQueuedJob();

		this.mockMvc.perform(post(
				"/api/v1/internal/media-jobs/{jobId}/attempts/1/lease", created.jobId())
				.header("Authorization", "Bearer " + USER_TOKEN)
				.header("X-Media-Lease-Token", created.leaseToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"worker_id\":\"media-worker-test\"}"))
				.andExpect(status().isUnauthorized());

		this.mockMvc.perform(post(
				"/api/v1/internal/media-jobs/{jobId}/attempts/1/lease", created.jobId())
				.header("Authorization", "Bearer " + WORKER_TOKEN)
				.header("X-Media-Lease-Token", "Z".repeat(43))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"worker_id\":\"media-worker-test\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("MEDIA_JOB_LEASE_INVALID"));

		assertEquals("QUEUED", scalar(
				"SELECT status FROM media_job WHERE media_job_id = ?", created.jobId()));
		assertEquals("UPLOADED", scalar(
				"SELECT asset_status FROM clip_asset WHERE clip_asset_id = ?", created.clipAssetId()));
	}

	@Test
	void rejectsResultMessageIdReuseWithDifferentContent() throws Exception {
		var created = createQueuedJob();
		acquire(created).andExpect(status().isOk());
		var result = successResult(created, SOFTWARE_DIGEST);
		submitResult(created, result).andExpect(status().isOk());

		var modified = result.replace("\"duration_microseconds\":2000000",
				"\"duration_microseconds\":2000001");
		submitResult(created, modified)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("INBOX_MESSAGE_REUSED"));
		assertEquals(1, count("SELECT count(*) FROM media_report"));
	}

	@Test
	void rejectsUnknownResultFieldsInsteadOfSilentlyIgnoringContractDrift() throws Exception {
		var created = createQueuedJob();
		acquire(created).andExpect(status().isOk());
		var resultWithUnknownField = successResult(created, SOFTWARE_DIGEST)
				.replace("\"error\":null", "\"unexpected_field\":true,\n  \"error\":null");

		submitResult(created, resultWithUnknownField)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error_code").value("INVALID_REQUEST"));

		assertEquals(0, count("SELECT count(*) FROM inbox_message"));
		assertEquals(0, count("SELECT count(*) FROM media_report"));
		assertEquals("RUNNING", scalar(
				"SELECT status FROM media_job WHERE media_job_id = ?", created.jobId()));
	}

	@Test
	void rejectsSuccessfulReportWithoutCompletedDigestAndDecodeVerification() throws Exception {
		var created = createQueuedJob();
		acquire(created).andExpect(status().isOk());
		var unverifiedResult = successResult(created, SOFTWARE_DIGEST)
				.replace("\"decode_verified\":true", "\"decode_verified\":false")
				.replace("\"input_digest_verified\":true", "\"input_digest_verified\":false");

		submitResult(created, unverifiedResult)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error_code").value("INVALID_REQUEST"));

		assertEquals(0, count("SELECT count(*) FROM inbox_message"));
		assertEquals(0, count("SELECT count(*) FROM media_report"));
		assertEquals("RUNNING", scalar(
				"SELECT status FROM media_job WHERE media_job_id = ?", created.jobId()));
	}

	private org.springframework.test.web.servlet.ResultActions acquire(CreatedJob created)
			throws Exception {
		return this.mockMvc.perform(post(
				"/api/v1/internal/media-jobs/{jobId}/attempts/1/lease", created.jobId())
				.header("Authorization", "Bearer " + WORKER_TOKEN)
				.header("X-Media-Lease-Token", created.leaseToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"worker_id\":\"media-worker-test\"}"));
	}

	private org.springframework.test.web.servlet.ResultActions submitResult(
			CreatedJob created, String result) throws Exception {
		return this.mockMvc.perform(put(
				"/api/v1/internal/media-jobs/{jobId}/attempts/1/result", created.jobId())
				.header("Authorization", "Bearer " + WORKER_TOKEN)
				.header("X-Media-Lease-Token", created.leaseToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(result));
	}

	private CreatedJob createQueuedJob() throws Exception {
		var clipAssetId = insertUploadedClipAsset();
		var response = this.mockMvc.perform(post("/api/v1/media-jobs")
				.header("Authorization", "Bearer " + USER_TOKEN)
				.header("Idempotency-Key", "media-execution-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "clip_asset_id": "%s",
						  "job_type": "MEDIA_PROBE"
						}
						""".formatted(clipAssetId)))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.current_attempt.status").value("QUEUED"))
				.andReturn().getResponse().getContentAsString();
		var jobId = UUID.fromString(JsonPath.read(response, "$.media_job_id"));
		var leaseToken = this.jdbcTemplate.queryForObject("""
				SELECT payload_json ->> 'lease_token'
				FROM outbox_event
				WHERE aggregate_id = ?
				""", String.class, jobId);
		var messageId = this.jdbcTemplate.queryForObject("""
				SELECT message_id FROM media_job_attempt
				WHERE media_job_id = ? AND attempt = 1
				""", UUID.class, jobId);
		return new CreatedJob(jobId, clipAssetId, messageId, leaseToken);
	}

	private UUID insertUploadedClipAsset() {
		var batchId = UUID.randomUUID();
		var uploadFileId = UUID.randomUUID();
		var clientFileId = UUID.randomUUID();
		var clipAssetId = UUID.randomUUID();
		var createdAt = Instant.now().minusSeconds(60);
		this.jdbcTemplate.update("""
				INSERT INTO upload_batch (
				    batch_id, project_id, idempotency_key, request_fingerprint, device_id,
				    file_count, total_size_bytes, manifest_sha256, status, expires_at,
				    created_at, updated_at, verified_manifest_sha256, completed_at)
				VALUES (?, ?, ?, ?, 'synthetic-device', 1, 1234, ?, 'COMPLETED', ?, ?, ?, ?, ?)
				""",
				batchId, PROJECT_ID, "execution-batch-" + batchId, SHA256, SHA256,
				Timestamp.from(createdAt.plusSeconds(3600)), Timestamp.from(createdAt),
				Timestamp.from(createdAt), SHA256, Timestamp.from(createdAt));
		this.jdbcTemplate.update("""
				INSERT INTO upload_file (
				    upload_file_id, batch_id, client_file_id, idempotency_key, request_fingerprint,
				    file_kind, content_type, size_bytes, sha256, part_size_bytes, total_parts,
				    status, created_at, updated_at, storage_bucket, object_key, storage_upload_id,
				    multipart_created_at, completion_started_at, completion_finished_at,
				    verified_sha256, completed_storage_etag)
				VALUES (?, ?, ?, ?, ?, 'VIDEO', 'video/mp4', 1234, ?, 8388608, 1, 'UPLOADED',
				        ?, ?, 'synthetic-bucket', ?, ?, ?, ?, ?, ?, 'synthetic-etag')
				""",
				uploadFileId, batchId, clientFileId, "execution-file-" + uploadFileId, SHA256,
				SHA256, Timestamp.from(createdAt), Timestamp.from(createdAt),
				"objects/" + uploadFileId, "multipart-" + uploadFileId,
				Timestamp.from(createdAt), Timestamp.from(createdAt), Timestamp.from(createdAt), SHA256);
		this.jdbcTemplate.update("""
				INSERT INTO clip_asset (
				    clip_asset_id, project_id, batch_id, source_upload_file_id, client_file_id,
				    content_type, size_bytes, sha256, storage_bucket, object_key, storage_etag,
				    asset_status, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'video/mp4', 1234, ?, 'synthetic-bucket', ?,
				        'synthetic-etag', 'UPLOADED', ?, ?)
				""",
				clipAssetId, PROJECT_ID, batchId, uploadFileId, clientFileId, SHA256,
				"objects/" + clipAssetId, Timestamp.from(createdAt), Timestamp.from(createdAt));
		return clipAssetId;
	}

	private String successResult(CreatedJob created, String softwareDigest) {
		var startedAt = Instant.now().minusSeconds(2);
		var completedAt = Instant.now().minusSeconds(1);
		return """
				{
				  "schema_version":"media-probe-result.v1",
				  "message_id":"%s",
				  "job_id":"%s",
				  "attempt":1,
				  "lease_token":"%s",
				  "status":"SUCCEEDED",
				  "input_sha256":"%s",
				  "recipe_digest":"%s",
				  "software_digest":"%s",
				  "started_at":"%s",
				  "completed_at":"%s",
				  "report":{
				    "container_format":"mov",
				    "duration_microseconds":2000000,
				    "container_start_time_microseconds":0,
				    "video_stream_count":1,
				    "primary_video_stream_index":0,
				    "video_codec":"h264",
				    "video_profile":"High",
				    "width":1280,
				    "height":720,
				    "pixel_format":"yuv420p",
				    "color_space":"bt709",
				    "primary_video_bitrate_bps":2000000,
				    "has_b_frames":true,
				    "stream_start_time_microseconds":0,
				    "time_base_numerator":1,
				    "time_base_denominator":15360,
				    "first_pts":0,
				    "last_pts":30208,
				    "nominal_frame_rate_numerator":30,
				    "nominal_frame_rate_denominator":1,
				    "average_frame_rate_numerator":30,
				    "average_frame_rate_denominator":1,
				    "frame_count":60,
				    "variable_frame_rate":false,
				    "audio_stream_count":1,
				    "sample_aspect_ratio_numerator":1,
				    "sample_aspect_ratio_denominator":1,
				    "decode_verified":true,
				    "input_digest_verified":true,
				    "warning_codes":["AUDIO_PRESENT"]
				  },
				  "error":null
				}
				""".formatted(
				created.messageId(), created.jobId(), created.leaseToken(), SHA256,
				MediaProbeProfile.RECIPE_DIGEST, softwareDigest, startedAt, completedAt);
	}

	private String failedResult(CreatedJob created) {
		var startedAt = Instant.now().minusSeconds(2);
		var completedAt = Instant.now().minusSeconds(1);
		return """
				{
				  "schema_version":"media-probe-result.v1",
				  "message_id":"%s",
				  "job_id":"%s",
				  "attempt":1,
				  "lease_token":"%s",
				  "status":"FAILED",
				  "input_sha256":"%s",
				  "recipe_digest":"%s",
				  "software_digest":"%s",
				  "started_at":"%s",
				  "completed_at":"%s",
				  "report":null,
				  "error":{
				    "error_class":"NON_RETRYABLE_INPUT",
				    "error_code":"MEDIA_DECODE_FAILED",
				    "safe_message":"Synthetic video failed complete decode verification",
				    "retryable":false
				  }
				}
				""".formatted(
				created.messageId(), created.jobId(), created.leaseToken(), SHA256,
				MediaProbeProfile.RECIPE_DIGEST, SOFTWARE_DIGEST, startedAt, completedAt);
	}

	private int count(String sql) {
		return this.jdbcTemplate.queryForObject(sql, Integer.class);
	}

	private String scalar(String sql, UUID id) {
		return this.jdbcTemplate.queryForObject(sql, String.class, id);
	}

	private record CreatedJob(UUID jobId, UUID clipAssetId, UUID messageId, String leaseToken) {
	}
}
