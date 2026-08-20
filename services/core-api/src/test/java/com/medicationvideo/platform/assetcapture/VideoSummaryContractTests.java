package com.medicationvideo.platform.assetcapture;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.medicationvideo.platform.support.ProjectMembershipTestData;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VideoSummaryContractTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final String TOKEN = "synthetic-test-bearer-token-do-not-reuse";

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private MockMvc mockMvc;

	@BeforeEach
	void ensureProject() {
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, PROJECT_ID);
	}

	@Test
	void exposesTheBoundedVideoSummaryReadModelWithTheV118Defaults() throws Exception {
		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/videos", PROJECT_ID).header("Authorization", "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray())
			.andExpect(jsonPath("$.next_cursor").value(nullValue()));
	}

	@Test
	void exposesQualityDecisionFromTheExistingBoundedReadModel() throws Exception {
		var baseTime = Instant.parse("2026-08-13T02:00:00Z");
		var fixtures = List.of(new QualityFixture(insertVideoSummaryFixture(baseTime, null), "UNREVIEWED"),
				new QualityFixture(insertVideoSummaryFixture(baseTime.plusSeconds(10), "QUALIFIED"), "QUALIFIED"),
				new QualityFixture(insertVideoSummaryFixture(baseTime.plusSeconds(20), "REJECTED"), "REJECTED"),
				new QualityFixture(insertVideoSummaryFixture(baseTime.plusSeconds(30), "NEEDS_REVIEW"),
						"NEEDS_REVIEW"));
		try {
			for (var fixture : fixtures) {
				var video = fixture.video();
				this.mockMvc
					.perform(get("/api/v1/projects/{projectId}/videos", PROJECT_ID)
						.param("received_from", video.createdAt().minusSeconds(1).toString())
						.param("received_before", video.createdAt().plusSeconds(1).toString())
						.header("Authorization", "Bearer " + TOKEN))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.items.length()").value(1))
					.andExpect(jsonPath("$.items[0].video_id").value(video.clipAssetId().toString()))
					.andExpect(jsonPath("$.items[0].quality_decision").value(fixture.expectedDecision()));
			}
		}
		finally {
			for (var fixture : fixtures) {
				var video = fixture.video();
				this.jdbcTemplate.update("DELETE FROM quality_review WHERE clip_asset_id = ?", video.clipAssetId());
				this.jdbcTemplate.update("DELETE FROM clip_asset WHERE clip_asset_id = ?", video.clipAssetId());
				this.jdbcTemplate.update("DELETE FROM upload_file WHERE upload_file_id = ?", video.uploadFileId());
				this.jdbcTemplate.update("DELETE FROM upload_batch WHERE batch_id = ?", video.batchId());
			}
		}
	}

	@Test
	void exposesAnOriginalFileNameUpToTheUploadFileLimit() throws Exception {
		var createdAt = Instant.parse("2026-08-15T02:00:00Z");
		var video = insertVideoSummaryFixture(createdAt, null);
		var fileName = "clinical-video-" + "a".repeat(236) + ".mp4";
		try {
			this.jdbcTemplate.update("UPDATE upload_file SET file_name = ? WHERE upload_file_id = ?", fileName,
					video.uploadFileId());
			this.mockMvc
				.perform(get("/api/v1/projects/{projectId}/videos", PROJECT_ID)
					.param("received_from", createdAt.minusSeconds(1).toString())
					.param("received_before", createdAt.plusSeconds(1).toString())
					.header("Authorization", "Bearer " + TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].display_name").value(fileName));
		}
		finally {
			this.jdbcTemplate.update("DELETE FROM clip_asset WHERE clip_asset_id = ?", video.clipAssetId());
			this.jdbcTemplate.update("DELETE FROM upload_file WHERE upload_file_id = ?", video.uploadFileId());
			this.jdbcTemplate.update("DELETE FROM upload_batch WHERE batch_id = ?", video.batchId());
		}
	}

	@Test
	void rejectsWorkflowFilterValuesOutsideThePublishedContract() throws Exception {
		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/videos?hand_status=BOGUS", PROJECT_ID).header("Authorization",
					"Bearer " + TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error_code").value("INVALID_FILTER"));

		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/video-data/summary?cleaning_status=BOGUS", PROJECT_ID)
				.header("Authorization", "Bearer " + TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error_code").value("INVALID_FILTER"));
	}

	@Test
	void acceptsItsServerCursorOnTheNextPage() throws Exception {
		var batchId = UUID.randomUUID();
		var createdAt = Instant.parse("2026-08-12T02:00:00Z");
		var sha256 = "b".repeat(64);
		var clipAssetIds = new UUID[] { UUID.randomUUID(), UUID.randomUUID() };
		var uploadFileIds = new UUID[] { UUID.randomUUID(), UUID.randomUUID() };
		var clientFileIds = new UUID[] { UUID.randomUUID(), UUID.randomUUID() };
		try {
			this.jdbcTemplate.update("""
					INSERT INTO upload_batch (
					    batch_id, project_id, idempotency_key, request_fingerprint, device_id,
					    initiated_by_actor_id, file_count, total_size_bytes, manifest_sha256,
					    status, expires_at, created_at, updated_at, verified_manifest_sha256, completed_at)
					VALUES (?, ?, ?, ?, 'synthetic-video-cursor-device', 'local-synthetic-admin', 2, 2468, ?,
					        'COMPLETED', ?, ?, ?, ?, ?)
					""", batchId, PROJECT_ID, "video-summary-cursor-batch-" + batchId, sha256, sha256,
					Timestamp.from(createdAt.plusSeconds(3600)), Timestamp.from(createdAt), Timestamp.from(createdAt),
					sha256, Timestamp.from(createdAt));
			for (var index = 0; index < clipAssetIds.length; index++) {
				var itemTime = createdAt.plusSeconds(index);
				this.jdbcTemplate.update("""
						INSERT INTO upload_file (
						    upload_file_id, batch_id, client_file_id, idempotency_key, request_fingerprint,
						    file_kind, content_type, size_bytes, sha256, part_size_bytes, total_parts,
						    status, created_at, updated_at, storage_bucket, object_key, storage_upload_id,
						    multipart_created_at, completion_started_at, completion_finished_at,
						    verified_sha256, completed_storage_etag)
						VALUES (?, ?, ?, ?, ?, 'VIDEO', 'video/mp4', 1234, ?, 8388608, 1, 'UPLOADED',
						        ?, ?, 'synthetic-bucket', ?, ?, ?, ?, ?, ?, 'synthetic-etag')
						""", uploadFileIds[index], batchId, clientFileIds[index],
						"video-summary-cursor-file-" + uploadFileIds[index], sha256, sha256, Timestamp.from(itemTime),
						Timestamp.from(itemTime), "objects/" + uploadFileIds[index],
						"multipart-" + uploadFileIds[index], Timestamp.from(itemTime), Timestamp.from(itemTime),
						Timestamp.from(itemTime), sha256);
				this.jdbcTemplate.update("""
						INSERT INTO clip_asset (
						    clip_asset_id, project_id, batch_id, source_upload_file_id, client_file_id,
						    content_type, size_bytes, sha256, storage_bucket, object_key, storage_etag,
						    asset_status, created_at, updated_at)
						VALUES (?, ?, ?, ?, ?, 'video/mp4', 1234, ?, 'synthetic-bucket', ?,
						        'synthetic-etag', 'INGESTED', ?, ?)
						""", clipAssetIds[index], PROJECT_ID, batchId, uploadFileIds[index], clientFileIds[index],
						sha256, "objects/" + clipAssetIds[index], Timestamp.from(itemTime), Timestamp.from(itemTime));
			}

			var firstPage = this.mockMvc
				.perform(get("/api/v1/projects/{projectId}/videos?limit=1", PROJECT_ID).header("Authorization",
						"Bearer " + TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.next_cursor").isString())
				.andReturn();
			var cursor = com.jayway.jsonpath.JsonPath.read(firstPage.getResponse().getContentAsString(),
					"$.next_cursor");
			this.mockMvc
				.perform(get("/api/v1/projects/{projectId}/videos?limit=1&cursor={cursor}", PROJECT_ID, cursor)
					.header("Authorization", "Bearer " + TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].video_id").value(clipAssetIds[0].toString()))
				.andExpect(jsonPath("$.next_cursor").value(nullValue()));
			this.mockMvc
				.perform(get("/api/v1/projects/{projectId}/videos?received_from={from}&received_before={before}",
						PROJECT_ID, "2026-08-12T02:00:01Z", "2026-08-12T02:00:02Z")
					.header("Authorization", "Bearer " + TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].video_id").value(clipAssetIds[1].toString()));
		}
		finally {
			this.jdbcTemplate.update("DELETE FROM clip_asset WHERE batch_id = ?", batchId);
			this.jdbcTemplate.update("DELETE FROM upload_file WHERE batch_id = ?", batchId);
			this.jdbcTemplate.update("DELETE FROM upload_batch WHERE batch_id = ?", batchId);
		}
	}

	@Test
	void exposesPersistedRetryabilityAsFalseAfterTheConfiguredAttemptLimit() throws Exception {
		var batchId = UUID.randomUUID();
		var uploadFileId = UUID.randomUUID();
		var clientFileId = UUID.randomUUID();
		var clipAssetId = UUID.randomUUID();
		var jobId = UUID.randomUUID();
		var attemptId = UUID.randomUUID();
		var createdAt = Instant.parse("2026-08-11T02:00:00Z");
		var sha256 = "a".repeat(64);
		try {
			this.jdbcTemplate.update("""
					INSERT INTO upload_batch (
					    batch_id, project_id, idempotency_key, request_fingerprint, device_id,
					    initiated_by_actor_id, file_count, total_size_bytes, manifest_sha256,
					    status, expires_at, created_at, updated_at, verified_manifest_sha256, completed_at)
					VALUES (?, ?, ?, ?, 'synthetic-video-summary-device', 'local-synthetic-admin', 1, 1234, ?,
					        'COMPLETED', ?, ?, ?, ?, ?)
					""", batchId, PROJECT_ID, "video-summary-batch-" + batchId, sha256, sha256,
					Timestamp.from(createdAt.plusSeconds(3600)), Timestamp.from(createdAt), Timestamp.from(createdAt),
					sha256, Timestamp.from(createdAt));
			this.jdbcTemplate.update("""
					INSERT INTO upload_file (
					    upload_file_id, batch_id, client_file_id, idempotency_key, request_fingerprint,
					    file_kind, content_type, size_bytes, sha256, part_size_bytes, total_parts,
					    status, created_at, updated_at, storage_bucket, object_key, storage_upload_id,
					    multipart_created_at, completion_started_at, completion_finished_at,
					    verified_sha256, completed_storage_etag)
					VALUES (?, ?, ?, ?, ?, 'VIDEO', 'video/mp4', 1234, ?, 8388608, 1, 'UPLOADED',
					        ?, ?, 'synthetic-bucket', ?, ?, ?, ?, ?, ?, 'synthetic-etag')
					""", uploadFileId, batchId, clientFileId, "video-summary-file-" + uploadFileId, sha256, sha256,
					Timestamp.from(createdAt), Timestamp.from(createdAt), "objects/" + uploadFileId,
					"multipart-" + uploadFileId, Timestamp.from(createdAt), Timestamp.from(createdAt),
					Timestamp.from(createdAt), sha256);
			this.jdbcTemplate.update("""
					INSERT INTO clip_asset (
					    clip_asset_id, project_id, batch_id, source_upload_file_id, client_file_id,
					    content_type, size_bytes, sha256, storage_bucket, object_key, storage_etag,
					    asset_status, created_at, updated_at)
					VALUES (?, ?, ?, ?, ?, 'video/mp4', 1234, ?, 'synthetic-bucket', ?,
					        'synthetic-etag', 'INGESTED', ?, ?)
					""", clipAssetId, PROJECT_ID, batchId, uploadFileId, clientFileId, sha256, "objects/" + clipAssetId,
					Timestamp.from(createdAt), Timestamp.from(createdAt));
			this.jdbcTemplate.update("""
					INSERT INTO ai_job (
					    ai_job_id, project_id, clip_asset_id, algorithm_key, algorithm_version,
					    model_sha256, input_sha256, profile_sha256, input_size_bytes, content_type,
					    sampling_fps, status, hand_result, idempotency_key, request_fingerprint,
					    requested_by, created_at, updated_at, version)
					VALUES (?, ?, ?, 'MEDIAPIPE_HAND_LANDMARKER', 'v1', ?, ?, ?, 1234, 'video/mp4',
					        10, 'FAILED', 'UNKNOWN', ?, ?, 'local-synthetic-admin', ?, ?, 0)
					""", jobId, PROJECT_ID, clipAssetId, sha256, sha256,
					"3a3be8035ec8a9756a32f43b007a4bb2cc2e2c0468d744d248f19c692fd90626", "video-summary-job-" + jobId,
					sha256, Timestamp.from(createdAt), Timestamp.from(createdAt));
			this.jdbcTemplate.update("""
					INSERT INTO ai_job_attempt (
					    ai_job_attempt_id, ai_job_id, attempt, message_id, lease_token_hash, status,
					    worker_id, updated_at, created_at, analysis_complete, hand_result, started_at,
					    completed_at, total_video_frames, inference_frame_count, frames_with_hand,
					    frames_without_hand, left_hand_detection_count, right_hand_detection_count,
					        invalid_landmark_count, processing_duration_ms, failure_reason, retryable)
					VALUES (?, ?, 3, ?, ?, 'FAILED', 'synthetic-ai-worker', ?, ?, false, 'UNKNOWN',
					        ?, ?, 10, 1, 0, 1, 0, 0, 0, 17, 'AI_HAND_POSE_INFERENCE_FAILED', true)
					""", attemptId, jobId, UUID.randomUUID(), sha256, Timestamp.from(createdAt),
					Timestamp.from(createdAt), Timestamp.from(createdAt), Timestamp.from(createdAt.plusSeconds(1)));

			this.mockMvc
				.perform(get("/api/v1/projects/{projectId}/videos", PROJECT_ID).header("Authorization",
						"Bearer " + TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].video_id").value(clipAssetId.toString()))
				.andExpect(jsonPath("$.items[0].hand_status").value("FAILED"))
				.andExpect(jsonPath("$.items[0].hand_result").value("UNKNOWN"))
				.andExpect(jsonPath("$.items[0].hand_retryable").value(false));
		}
		finally {
			this.jdbcTemplate.update("DELETE FROM ai_job_attempt WHERE ai_job_attempt_id = ?", attemptId);
			this.jdbcTemplate.update("DELETE FROM ai_job WHERE ai_job_id = ?", jobId);
			this.jdbcTemplate.update("DELETE FROM clip_asset WHERE clip_asset_id = ?", clipAssetId);
			this.jdbcTemplate.update("DELETE FROM upload_file WHERE upload_file_id = ?", uploadFileId);
			this.jdbcTemplate.update("DELETE FROM upload_batch WHERE batch_id = ?", batchId);
		}
	}

	private VideoFixture insertVideoSummaryFixture(Instant createdAt, String qualityDecision) {
		var batchId = UUID.randomUUID();
		var uploadFileId = UUID.randomUUID();
		var clientFileId = UUID.randomUUID();
		var clipAssetId = UUID.randomUUID();
		var sha256 = "d".repeat(64);
		this.jdbcTemplate.update("""
				INSERT INTO upload_batch (
				    batch_id, project_id, idempotency_key, request_fingerprint, device_id,
				    initiated_by_actor_id, file_count, total_size_bytes, manifest_sha256,
				    status, expires_at, created_at, updated_at, verified_manifest_sha256, completed_at)
				VALUES (?, ?, ?, ?, 'synthetic-video-quality-device', 'local-synthetic-admin', 1, 1234, ?,
				        'COMPLETED', ?, ?, ?, ?, ?)
				""", batchId, PROJECT_ID, "video-summary-quality-batch-" + batchId, sha256, sha256,
				Timestamp.from(createdAt.plusSeconds(3600)), Timestamp.from(createdAt), Timestamp.from(createdAt),
				sha256, Timestamp.from(createdAt));
		this.jdbcTemplate.update("""
				INSERT INTO upload_file (
				    upload_file_id, batch_id, client_file_id, idempotency_key, request_fingerprint,
				    file_kind, content_type, size_bytes, sha256, part_size_bytes, total_parts,
				    status, created_at, updated_at, storage_bucket, object_key, storage_upload_id,
				    multipart_created_at, completion_started_at, completion_finished_at,
				    verified_sha256, completed_storage_etag)
				VALUES (?, ?, ?, ?, ?, 'VIDEO', 'video/mp4', 1234, ?, 8388608, 1, 'UPLOADED',
				        ?, ?, 'synthetic-bucket', ?, ?, ?, ?, ?, ?, 'synthetic-etag')
				""", uploadFileId, batchId, clientFileId, "video-summary-quality-file-" + uploadFileId, sha256, sha256,
				Timestamp.from(createdAt), Timestamp.from(createdAt), "objects/" + uploadFileId,
				"multipart-" + uploadFileId, Timestamp.from(createdAt), Timestamp.from(createdAt),
				Timestamp.from(createdAt), sha256);
		this.jdbcTemplate.update("""
				INSERT INTO clip_asset (
				    clip_asset_id, project_id, batch_id, source_upload_file_id, client_file_id,
				    content_type, size_bytes, sha256, storage_bucket, object_key, storage_etag,
				    asset_status, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'video/mp4', 1234, ?, 'synthetic-bucket', ?,
				        'synthetic-etag', 'INGESTED', ?, ?)
				""", clipAssetId, PROJECT_ID, batchId, uploadFileId, clientFileId, sha256, "objects/" + clipAssetId,
				Timestamp.from(createdAt), Timestamp.from(createdAt));
		if (qualityDecision != null) {
			var reasonCode = switch (qualityDecision) {
				case "QUALIFIED" -> "MEETS_REQUIREMENTS";
				case "REJECTED" -> "OTHER_QUALITY_FAILURE";
				case "NEEDS_REVIEW" -> "UNCERTAIN_QUALITY";
				default -> throw new IllegalArgumentException("Unsupported quality decision: " + qualityDecision);
			};
			this.jdbcTemplate.update("""
					INSERT INTO quality_review (
					    quality_review_id, project_id, clip_asset_id, decision, reason_code,
					    reviewed_by, reviewed_at, version)
					VALUES (?, ?, ?, ?, ?, 'summary-quality-reviewer', ?, 0)
					""", UUID.randomUUID(), PROJECT_ID, clipAssetId, qualityDecision, reasonCode,
					Timestamp.from(createdAt));
		}
		return new VideoFixture(batchId, uploadFileId, clipAssetId, createdAt);
	}

	private record QualityFixture(VideoFixture video, String expectedDecision) {
	}

	private record VideoFixture(UUID batchId, UUID uploadFileId, UUID clipAssetId, Instant createdAt) {
	}

}
