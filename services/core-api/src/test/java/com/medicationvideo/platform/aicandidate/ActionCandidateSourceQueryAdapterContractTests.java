package com.medicationvideo.platform.aicandidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.medicationvideo.platform.support.ProjectMembershipTestData;

@SpringBootTest
@ActiveProfiles("test")
class ActionCandidateSourceQueryAdapterContractTests {

	private static final String SHA256 = "a".repeat(64);

	private static final String SCHEMA_DIGEST = "b".repeat(64);

	private static final String MODEL_SHA256 = "c".repeat(64);

	private static final String PROFILE_SHA256 = "d".repeat(64);

	private UUID projectId;

	private UUID captureGroupId;

	private UUID clipAssetId;

	private UUID annotationTaskId;

	private UUID labelSchemaId;

	private UUID uploadBatchId;

	private UUID uploadFileId;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ActionCandidateSourceQueryPort sourceQuery;

	@BeforeEach
	void setUp() {
		this.projectId = UUID.randomUUID();
		this.captureGroupId = UUID.randomUUID();
		this.clipAssetId = UUID.randomUUID();
		this.annotationTaskId = UUID.randomUUID();
		this.labelSchemaId = UUID.randomUUID();
		this.uploadBatchId = UUID.randomUUID();
		this.uploadFileId = UUID.randomUUID();
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, this.projectId);
		insertClipAsset();
	}

	@AfterEach
	void cleanUp() {
		if (this.projectId == null) {
			return;
		}
		this.jdbcTemplate.update(
				"DELETE FROM action_candidate WHERE ai_job_id IN (SELECT ai_job_id FROM ai_job WHERE project_id = ?)",
				this.projectId);
		this.jdbcTemplate.update(
				"DELETE FROM ai_job_attempt WHERE ai_job_id IN (SELECT ai_job_id FROM ai_job WHERE project_id = ?)",
				this.projectId);
		this.jdbcTemplate.update("DELETE FROM ai_job WHERE project_id = ?", this.projectId);
		this.jdbcTemplate.update("DELETE FROM clip_asset WHERE clip_asset_id = ?", this.clipAssetId);
		this.jdbcTemplate.update("DELETE FROM upload_file WHERE upload_file_id = ?", this.uploadFileId);
		this.jdbcTemplate.update("DELETE FROM upload_batch WHERE batch_id = ?", this.uploadBatchId);
		this.jdbcTemplate.update("DELETE FROM membership WHERE project_id = ?", this.projectId);
		this.jdbcTemplate.update("DELETE FROM project WHERE project_id = ?", this.projectId);
	}

	@Test
	void returnsOnlyCandidatesMatchingTheCurrentJobTaskSchemaAndAllowedLabelSnapshot() {
		var jobId = UUID.randomUUID();
		insertJob(jobId, this.annotationTaskId, this.labelSchemaId, SCHEMA_DIGEST,
				"[\"draw_liquid\",\"open_container\"]", Instant.parse("2026-09-20T00:00:00Z"), "SUCCEEDED");
		insertSuccessfulAttempt(jobId);

		var validId = UUID.randomUUID();
		var wrongGroupId = UUID.randomUUID();
		var wrongProjectId = UUID.randomUUID();
		var wrongClipId = UUID.randomUUID();
		var wrongTaskId = UUID.randomUUID();
		var wrongSchemaId = UUID.randomUUID();
		var wrongDigestId = UUID.randomUUID();
		var disallowedLabelId = UUID.randomUUID();
		insertCandidate(jobId, validId, this.projectId, this.captureGroupId, this.clipAssetId, this.annotationTaskId,
				this.labelSchemaId, SCHEMA_DIGEST, "draw_liquid", 1200, 3400);
		insertCandidate(jobId, wrongGroupId, this.projectId, UUID.randomUUID(), this.clipAssetId, this.annotationTaskId,
				this.labelSchemaId, SCHEMA_DIGEST, "draw_liquid", 100, 200);
		insertCandidate(jobId, wrongProjectId, UUID.randomUUID(), this.captureGroupId, this.clipAssetId,
				this.annotationTaskId, this.labelSchemaId, SCHEMA_DIGEST, "draw_liquid", 100, 200);
		insertCandidate(jobId, wrongClipId, this.projectId, this.captureGroupId, UUID.randomUUID(),
				this.annotationTaskId, this.labelSchemaId, SCHEMA_DIGEST, "draw_liquid", 100, 200);
		insertCandidate(jobId, wrongTaskId, this.projectId, this.captureGroupId, this.clipAssetId, UUID.randomUUID(),
				this.labelSchemaId, SCHEMA_DIGEST, "draw_liquid", 100, 200);
		insertCandidate(jobId, wrongSchemaId, this.projectId, this.captureGroupId, this.clipAssetId,
				this.annotationTaskId, UUID.randomUUID(), SCHEMA_DIGEST, "draw_liquid", 100, 200);
		insertCandidate(jobId, wrongDigestId, this.projectId, this.captureGroupId, this.clipAssetId,
				this.annotationTaskId, this.labelSchemaId, SHA256, "draw_liquid", 100, 200);
		insertCandidate(jobId, disallowedLabelId, this.projectId, this.captureGroupId, this.clipAssetId,
				this.annotationTaskId, this.labelSchemaId, SCHEMA_DIGEST, "invented_label", 100, 200);

		var requested = new LinkedHashSet<>(Set.of(validId, wrongGroupId, wrongProjectId, wrongClipId, wrongTaskId,
				wrongSchemaId, wrongDigestId, disallowedLabelId, UUID.randomUUID()));
		var found = this.sourceQuery.findEligible(this.projectId, this.captureGroupId, this.annotationTaskId,
				this.clipAssetId, this.labelSchemaId, SCHEMA_DIGEST, requested);

		assertEquals(Set.of(validId), found.keySet());
		var source = found.get(validId);
		assertEquals(this.clipAssetId, source.clipAssetId());
		assertEquals(this.labelSchemaId, source.labelSchemaId());
		assertEquals(SCHEMA_DIGEST, source.labelSchemaDigest());
		assertEquals(1200, source.startMs());
		assertEquals(3400, source.endMs());
		assertEquals("draw_liquid", source.label());
	}

	@Test
	void ignoresCandidatesAfterANewerJobSupersedesTheirSource() {
		var oldJobId = UUID.randomUUID();
		insertJob(oldJobId, this.annotationTaskId, this.labelSchemaId, SCHEMA_DIGEST, "[\"draw_liquid\"]",
				Instant.parse("2026-09-20T00:00:00Z"), "SUCCEEDED");
		insertSuccessfulAttempt(oldJobId);
		var oldCandidateId = UUID.randomUUID();
		insertCandidate(oldJobId, oldCandidateId, this.projectId, this.captureGroupId, this.clipAssetId,
				this.annotationTaskId, this.labelSchemaId, SCHEMA_DIGEST, "draw_liquid", 100, 500);

		var newerJobId = UUID.randomUUID();
		insertJob(newerJobId, UUID.randomUUID(), this.labelSchemaId, SCHEMA_DIGEST, "[\"draw_liquid\"]",
				Instant.parse("2026-09-20T00:00:01Z"), "SUCCEEDED");
		insertSuccessfulAttempt(newerJobId);

		var found = this.sourceQuery.findEligible(this.projectId, this.captureGroupId, this.annotationTaskId,
				this.clipAssetId, this.labelSchemaId, SCHEMA_DIGEST, Set.of(oldCandidateId));

		assertTrue(found.isEmpty());
	}

	@Test
	void doesNotExposeCandidatesFromAFailedCurrentJob() {
		var jobId = UUID.randomUUID();
		insertJob(jobId, this.annotationTaskId, this.labelSchemaId, SCHEMA_DIGEST, "[\"draw_liquid\"]",
				Instant.parse("2026-09-20T00:00:00Z"), "FAILED");
		var candidateId = UUID.randomUUID();
		insertCandidate(jobId, candidateId, this.projectId, this.captureGroupId, this.clipAssetId,
				this.annotationTaskId, this.labelSchemaId, SCHEMA_DIGEST, "draw_liquid", 100, 500);

		var found = this.sourceQuery.findEligible(this.projectId, this.captureGroupId, this.annotationTaskId,
				this.clipAssetId, this.labelSchemaId, SCHEMA_DIGEST, Set.of(candidateId));

		assertTrue(found.isEmpty());
	}

	@Test
	void missingRequiredContextFailsClosed() {
		assertTrue(this.sourceQuery
			.findEligible(this.projectId, this.captureGroupId, null, this.clipAssetId, this.labelSchemaId,
					SCHEMA_DIGEST, Set.of(UUID.randomUUID()))
			.isEmpty());
		assertTrue(this.sourceQuery
			.findEligible(this.projectId, this.captureGroupId, this.annotationTaskId, this.clipAssetId,
					this.labelSchemaId, "not-a-digest", Set.of(UUID.randomUUID()))
			.isEmpty());
		assertTrue(this.sourceQuery
			.findEligible(this.projectId, this.captureGroupId, this.annotationTaskId, this.clipAssetId,
					this.labelSchemaId, SCHEMA_DIGEST, Set.of())
			.isEmpty());
		assertTrue(this.sourceQuery
			.findEligible(this.projectId, this.captureGroupId, this.annotationTaskId, this.clipAssetId,
					this.labelSchemaId, SCHEMA_DIGEST, Set.of(UUID.randomUUID()))
			.isEmpty());
		var candidateIdsWithNull = new LinkedHashSet<UUID>();
		candidateIdsWithNull.add(UUID.randomUUID());
		candidateIdsWithNull.add(null);
		assertTrue(this.sourceQuery
			.findEligible(this.projectId, this.captureGroupId, this.annotationTaskId, this.clipAssetId,
					this.labelSchemaId, SCHEMA_DIGEST, candidateIdsWithNull)
			.isEmpty());
	}

	private void insertJob(UUID jobId, UUID taskId, UUID schemaId, String schemaDigest, String allowedLabels,
			Instant createdAt, String status) {
		var inputDigest = UUID.randomUUID().toString().replace("-", "").substring(0, 32) + SHA256.substring(32);
		this.jdbcTemplate.update(
				"""
						INSERT INTO ai_job (
						    ai_job_id, project_id, clip_asset_id, algorithm_key, algorithm_version,
						    model_sha256, input_sha256, profile_sha256, input_size_bytes, content_type, sampling_fps,
						    status, idempotency_key, request_fingerprint, requested_by, created_at, updated_at, version,
						    hand_result, annotation_task_id, label_schema_id, label_schema_digest, allowed_labels)
						VALUES (?, ?, ?, 'QWEN3_5_ACTION', 'v1', ?, ?, ?, 1000, 'video/mp4', 1, ?, ?, ?, 'synthetic-test', ?, ?, 0,
						        'UNKNOWN', ?, ?, ?, CAST(? AS JSONB))
						""",
				jobId, this.projectId, this.clipAssetId, MODEL_SHA256, inputDigest, PROFILE_SHA256, status,
				"candidate-source-job-" + jobId, SHA256, Timestamp.from(createdAt), Timestamp.from(createdAt), taskId,
				schemaId, schemaDigest, allowedLabels);
	}

	private void insertClipAsset() {
		var now = Timestamp.from(Instant.parse("2026-09-20T00:00:00Z"));
		var clientFileId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO upload_batch (
				    batch_id, project_id, idempotency_key, request_fingerprint, device_id,
				    initiated_by_actor_id, file_count, total_size_bytes, manifest_sha256,
				    status, expires_at, created_at, updated_at, verified_manifest_sha256, completed_at)
				VALUES (?, ?, ?, ?, 'synthetic-candidate-source-device', 'synthetic', 1, 100, ?,
				        'COMPLETED', ?, ?, ?, ?, ?)
				""", this.uploadBatchId, this.projectId, "candidate-source-batch-" + this.uploadBatchId, SHA256, SHA256,
				Timestamp.from(now.toInstant().plusSeconds(3600)), now, now, SHA256, now);
		this.jdbcTemplate.update("""
				INSERT INTO upload_file (
				    upload_file_id, batch_id, client_file_id, idempotency_key, request_fingerprint,
				    file_kind, content_type, size_bytes, sha256, part_size_bytes, total_parts,
				    status, created_at, updated_at, storage_bucket, object_key, storage_upload_id,
				    multipart_created_at, completion_started_at, completion_finished_at,
				    verified_sha256, completed_storage_etag)
				VALUES (?, ?, ?, ?, ?, 'VIDEO', 'video/mp4', 100, ?, 8388608, 1, 'UPLOADED',
				        ?, ?, 'synthetic-bucket', ?, ?, ?, ?, ?, ?, 'synthetic-etag')
				""", this.uploadFileId, this.uploadBatchId, clientFileId, "candidate-source-file-" + this.uploadFileId,
				SHA256, SHA256, now, now, "candidate-source/" + this.clipAssetId, "multipart-" + this.uploadFileId, now,
				now, now, SHA256);
		this.jdbcTemplate.update("""
				INSERT INTO clip_asset (
				    clip_asset_id, project_id, batch_id, source_upload_file_id, client_file_id,
				    content_type, size_bytes, sha256, storage_bucket, object_key, storage_etag,
				    asset_status, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'video/mp4', 100, ?, 'synthetic-bucket', ?,
				        'synthetic-etag', 'INGESTED', ?, ?)
				""", this.clipAssetId, this.projectId, this.uploadBatchId, this.uploadFileId, clientFileId, SHA256,
				"candidate-source/" + this.clipAssetId, now, now);
	}

	private void insertSuccessfulAttempt(UUID jobId) {
		var now = Timestamp.from(Instant.parse("2026-09-20T00:00:02Z"));
		this.jdbcTemplate.update("""
				INSERT INTO ai_job_attempt (
				    ai_job_attempt_id, ai_job_id, attempt, message_id, lease_token_hash, status,
				    worker_id, lease_until, updated_at, created_at)
				VALUES (?, ?, 1, ?, ?, 'SUCCEEDED', 'synthetic-action-worker', ?, ?, ?)
				""", UUID.randomUUID(), jobId, UUID.randomUUID(), SHA256, now, now, now);
	}

	private void insertCandidate(UUID jobId, UUID candidateId, UUID candidateProjectId, UUID candidateGroupId,
			UUID candidateClipAssetId, UUID candidateTaskId, UUID candidateSchemaId, String candidateSchemaDigest,
			String label, long startMs, long endMs) {
		this.jdbcTemplate.update("""
				INSERT INTO action_candidate (
				    action_candidate_id, ai_job_id, project_id, capture_group_id, clip_asset_id,
				    annotation_task_id, label_schema_id, label_schema_digest, start_ms, end_ms,
				    action_label, confidence, source_window, stitched_windows, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0.9, 1, '[]'::JSONB, ?)
				""", candidateId, jobId, candidateProjectId, candidateGroupId, candidateClipAssetId, candidateTaskId,
				candidateSchemaId, candidateSchemaDigest, startMs, endMs, label,
				Timestamp.from(Instant.parse("2026-09-20T00:00:03Z")));
	}

}
