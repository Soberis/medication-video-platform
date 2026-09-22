package com.medicationvideo.platform.aicandidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
class CandidateEvidenceProvenanceContractTests {

	private static final UUID PROJECT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

	private static final String INPUT_SHA256 = "a".repeat(64);

	private static final String REPLACED_INPUT_SHA256 = "c".repeat(64);

	private static final String MODEL_SHA256 = "b".repeat(64);

	private static final String RESULT_SHA256 = "d".repeat(64);

	private static final String RETRY_RESULT_SHA256 = "e".repeat(64);

	private static final String LEASE_HASH = "f".repeat(64);

	private static final String PROFILE_SHA256 = HandPoseProfile.PROFILE_SHA256;

	private static final Instant BASE_TIME = Instant.parse("2026-08-01T01:00:00Z");

	private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private CandidateEvidenceQueryService evidenceQueryService;

	@BeforeEach
	void clearState() {
		this.jdbcTemplate.update("DELETE FROM candidate_annotation WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update(
				"DELETE FROM ai_job_result_inbox WHERE ai_job_id IN (SELECT ai_job_id FROM ai_job WHERE project_id = ?)",
				PROJECT_ID);
		this.jdbcTemplate.update(
				"DELETE FROM ai_job_attempt WHERE ai_job_id IN (SELECT ai_job_id FROM ai_job WHERE project_id = ?)",
				PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM ai_job WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM clip_asset WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM upload_file WHERE idempotency_key LIKE 'candidate-provenance-file-%'");
		this.jdbcTemplate.update("DELETE FROM upload_batch WHERE project_id = ?", PROJECT_ID);
	}

	@AfterEach
	void cleanUpState() {
		clearState();
	}

	@Test
	void returnsOneNoJobSnapshotForN1AndOneSnapshotPerAssetForNGreaterThan1() {
		var first = createAsset(INPUT_SHA256);
		var second = createAsset(INPUT_SHA256);

		var one = this.evidenceQueryService.findByClipAssets(PROJECT_ID, List.of(first.clipAssetId()));
		var many = this.evidenceQueryService.findByClipAssets(PROJECT_ID,
				List.of(first.clipAssetId(), second.clipAssetId(), first.clipAssetId()));
		assertEquals(1, one.size());
		assertEquals(2, many.size());
		assertEquals(CandidateEvidenceQueryService.EXECUTION_STATUS_NO_JOB, one.getFirst().executionStatus());
		assertTrue(one.getFirst().provenanceValid());
		assertEquals(INPUT_SHA256, one.getFirst().clipAssetSha256());
		assertEquals(0, one.getFirst().candidateCount());
	}

	@Test
	void bindsSuccessfulHandEvidenceToInputAttemptResultAndCandidateSet() {
		var asset = createAsset(INPUT_SHA256);
		var jobId = UUID.randomUUID();
		var attemptId = UUID.randomUUID();
		var messageId = UUID.randomUUID();
		insertJob(asset, jobId, "SUCCEEDED", "HAND_DETECTED", BASE_TIME);
		insertAttempt(jobId, attemptId, 1, messageId, "SUCCEEDED", "HAND_DETECTED", null, BASE_TIME);
		insertResult(jobId, 1, messageId, RESULT_SHA256, BASE_TIME);
		insertCandidate(asset, jobId, BASE_TIME, 0);

		var evidence = this.evidenceQueryService.findByClipAssets(PROJECT_ID, List.of(asset.clipAssetId())).getFirst();

		assertTrue(evidence.provenanceValid());
		assertEquals(CandidateEvidenceQueryService.EXECUTION_STATUS_SUCCEEDED_WITH_CANDIDATES,
				evidence.executionStatus());
		assertEquals(jobId, evidence.aiJobId());
		assertEquals(INPUT_SHA256, evidence.clipAssetSha256());
		assertEquals(INPUT_SHA256, evidence.inputSha256());
		assertEquals(attemptId, evidence.aiJobAttemptId());
		assertEquals(1, evidence.attempt());
		assertEquals(messageId, evidence.messageId());
		assertEquals(RESULT_SHA256, evidence.resultSha256());
		assertEquals(1, evidence.candidateCount());
		assertEquals(0L, evidence.firstTimestampMs());
		assertEquals(0L, evidence.lastTimestampMs());
		assertNotNull(evidence.candidateSetDigest());
		assertEquals(64, evidence.candidateSetDigest().length());
		assertNotNull(evidence.runSummary());
		assertEquals(6L, evidence.runSummary().totalVideoFrames());
		assertEquals(3L, evidence.runSummary().inferenceFrameCount());
	}

	@Test
	void distinguishesNoHandAndFailedExecutionsWithoutTreatingEitherAsHandEvidence() {
		var noHandAsset = createAsset(INPUT_SHA256);
		var noHandJob = UUID.randomUUID();
		var noHandAttempt = UUID.randomUUID();
		var noHandMessage = UUID.randomUUID();
		insertJob(noHandAsset, noHandJob, "SUCCEEDED", "NO_HAND_DETECTED", BASE_TIME);
		insertAttempt(noHandJob, noHandAttempt, 1, noHandMessage, "SUCCEEDED", "NO_HAND_DETECTED", null, BASE_TIME);
		insertResult(noHandJob, 1, noHandMessage, RESULT_SHA256, BASE_TIME);

		var failedAsset = createAsset(INPUT_SHA256);
		var failedJob = UUID.randomUUID();
		var failedAttempt = UUID.randomUUID();
		var failedMessage = UUID.randomUUID();
		insertJob(failedAsset, failedJob, "FAILED", "UNKNOWN", BASE_TIME.plusSeconds(1));
		insertAttempt(failedJob, failedAttempt, 1, failedMessage, "FAILED", "UNKNOWN", "AI_HAND_POSE_INVALID_OUTPUT",
				BASE_TIME.plusSeconds(1));

		var evidence = this.evidenceQueryService.findByClipAssets(PROJECT_ID,
				List.of(noHandAsset.clipAssetId(), failedAsset.clipAssetId()));

		assertEquals(CandidateEvidenceQueryService.EXECUTION_STATUS_SUCCEEDED_NO_HAND,
				evidence.get(0).executionStatus());
		assertEquals(0, evidence.get(0).candidateCount());
		assertNotNull(evidence.get(0).candidateSetDigest());
		assertNotNull(evidence.get(0).runSummary());
		assertEquals(CandidateEvidenceQueryService.EXECUTION_STATUS_FAILED, evidence.get(1).executionStatus());
		assertTrue(evidence.get(1).provenanceValid());
		assertEquals("AI_HAND_POSE_INVALID_OUTPUT", evidence.get(1).provenanceFailureReason());
		assertEquals(0, evidence.get(1).candidateCount());
		assertNull(evidence.get(1).candidateSetDigest());
		assertNotNull(evidence.get(1).runSummary());
	}

	@Test
	void rejectsAResultFromAnOldInputAfterTheClipAssetDigestChanges() {
		var asset = createAsset(INPUT_SHA256);
		var jobId = UUID.randomUUID();
		var attemptId = UUID.randomUUID();
		var messageId = UUID.randomUUID();
		insertJob(asset, jobId, "SUCCEEDED", "HAND_DETECTED", BASE_TIME);
		insertAttempt(jobId, attemptId, 1, messageId, "SUCCEEDED", "HAND_DETECTED", null, BASE_TIME);
		insertResult(jobId, 1, messageId, RESULT_SHA256, BASE_TIME);
		insertCandidate(asset, jobId, BASE_TIME, 0);
		this.jdbcTemplate.update("UPDATE clip_asset SET sha256 = ?, version = version + 1 WHERE clip_asset_id = ?",
				REPLACED_INPUT_SHA256, asset.clipAssetId());

		var evidence = this.evidenceQueryService.findByClipAssets(PROJECT_ID, List.of(asset.clipAssetId())).getFirst();

		assertFalse(evidence.provenanceValid());
		assertEquals(CandidateEvidenceQueryService.EXECUTION_STATUS_INVALID_PROVENANCE, evidence.executionStatus());
		assertEquals("INPUT_SHA256_MISMATCH", evidence.provenanceFailureReason());
		assertEquals(REPLACED_INPUT_SHA256, evidence.clipAssetSha256());
		assertEquals(INPUT_SHA256, evidence.inputSha256());
		assertEquals(jobId, evidence.aiJobId());
	}

	@Test
	void selectsTheSecondAttemptAndRetainsItsExecutionIdentity() {
		var asset = createAsset(INPUT_SHA256);
		var jobId = UUID.randomUUID();
		var firstAttemptId = UUID.randomUUID();
		var firstMessageId = UUID.randomUUID();
		var secondAttemptId = UUID.randomUUID();
		var secondMessageId = UUID.randomUUID();
		insertJob(asset, jobId, "SUCCEEDED", "HAND_DETECTED", BASE_TIME);
		insertAttempt(jobId, firstAttemptId, 1, firstMessageId, "FAILED", "UNKNOWN", "TRANSIENT", BASE_TIME);
		insertAttempt(jobId, secondAttemptId, 2, secondMessageId, "SUCCEEDED", "HAND_DETECTED", null,
				BASE_TIME.plusSeconds(1));
		insertResult(jobId, 2, secondMessageId, RETRY_RESULT_SHA256, BASE_TIME.plusSeconds(1));
		insertCandidate(asset, jobId, BASE_TIME.plusSeconds(1), 0);

		var evidence = this.evidenceQueryService.findByClipAssets(PROJECT_ID, List.of(asset.clipAssetId())).getFirst();

		assertEquals(2, evidence.attempt());
		assertEquals(secondAttemptId, evidence.aiJobAttemptId());
		assertEquals(secondMessageId, evidence.messageId());
		assertEquals(RETRY_RESULT_SHA256, evidence.resultSha256());
		assertEquals(CandidateEvidenceQueryService.EXECUTION_STATUS_SUCCEEDED_WITH_CANDIDATES,
				evidence.executionStatus());
	}

	@Test
	void candidateSetDigestIsIndependentOfRowOrderAndJsonObjectOrder() throws Exception {
		var first = candidateSnapshot(UUID.fromString("20000000-0000-4000-8000-000000000001"), 10,
				"{\"x\":0.1,\"y\":0.2}", "[{\"x\":0.1,\"y\":0.2}]");
		var second = candidateSnapshot(UUID.fromString("20000000-0000-4000-8000-000000000002"), 20,
				"{\"y\":0.4,\"x\":0.3}", "[{\"y\":0.4,\"x\":0.3}]");
		var equivalentFirst = candidateSnapshot(first.candidateAnnotationId(), 10, "{\"y\":0.2,\"x\":0.1}",
				"[{\"y\":0.2,\"x\":0.1}]");
		var equivalentSecond = candidateSnapshot(second.candidateAnnotationId(), 20, "{\"x\":0.3,\"y\":0.4}",
				"[{\"x\":0.3,\"y\":0.4}]");

		assertEquals(CandidateEvidenceQueryService.candidateSetDigest(List.of(first, second)),
				CandidateEvidenceQueryService.candidateSetDigest(List.of(equivalentSecond, equivalentFirst)));
	}

	private Fixture createAsset(String sha256) {
		var clipAssetId = UUID.randomUUID();
		var batchId = UUID.randomUUID();
		var uploadFileId = UUID.randomUUID();
		var clientFileId = UUID.randomUUID();
		var now = BASE_TIME;
		this.jdbcTemplate.update("""
				INSERT INTO upload_batch (
				    batch_id, project_id, idempotency_key, request_fingerprint, device_id,
				    initiated_by_actor_id, file_count, total_size_bytes, manifest_sha256,
				    status, expires_at, created_at, updated_at, verified_manifest_sha256, completed_at)
				VALUES (?, ?, ?, ?, 'candidate-provenance-device', 'synthetic', 1, 100, ?,
				        'COMPLETED', ?, ?, ?, ?, ?)
				""", batchId, PROJECT_ID, "candidate-provenance-batch-" + batchId, "1".repeat(64), sha256,
				Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now), sha256,
				Timestamp.from(now));
		this.jdbcTemplate.update("""
				INSERT INTO upload_file (
				    upload_file_id, batch_id, client_file_id, idempotency_key, request_fingerprint,
				    file_kind, content_type, size_bytes, sha256, part_size_bytes, total_parts,
				    status, created_at, updated_at, storage_bucket, object_key, storage_upload_id,
				    multipart_created_at, completion_started_at, completion_finished_at,
				    verified_sha256, completed_storage_etag)
				VALUES (?, ?, ?, ?, ?, 'VIDEO', 'video/mp4', 100, ?, 8388608, 1, 'UPLOADED',
				        ?, ?, 'synthetic-bucket', ?, ?, ?, ?, ?, ?, 'synthetic-etag')
				""", uploadFileId, batchId, clientFileId, "candidate-provenance-file-" + uploadFileId, "2".repeat(64),
				sha256, Timestamp.from(now), Timestamp.from(now), "candidate-provenance/" + clipAssetId,
				"multipart-" + uploadFileId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), sha256);
		this.jdbcTemplate.update("""
				INSERT INTO clip_asset (
				    clip_asset_id, project_id, batch_id, source_upload_file_id, client_file_id,
				    content_type, size_bytes, sha256, storage_bucket, object_key, storage_etag,
				    asset_status, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'video/mp4', 100, ?, 'synthetic-bucket', ?,
				        'synthetic-etag', 'INGESTED', ?, ?)
				""", clipAssetId, PROJECT_ID, batchId, uploadFileId, clientFileId, sha256,
				"candidate-provenance/" + clipAssetId, Timestamp.from(now), Timestamp.from(now));
		return new Fixture(clipAssetId, sha256);
	}

	private void insertJob(Fixture asset, UUID jobId, String status, String handResult, Instant createdAt) {
		this.jdbcTemplate.update(
				"""
						INSERT INTO ai_job (ai_job_id, project_id, clip_asset_id, algorithm_key, algorithm_version,
						 model_sha256, input_sha256, profile_sha256, input_size_bytes, content_type, sampling_fps, status,
						 idempotency_key, request_fingerprint, requested_by, created_at, updated_at, version, hand_result)
						VALUES (?, ?, ?, 'MEDIAPIPE_HAND_LANDMARKER', 'v1', ?, ?, ?, 100, 'video/mp4', 10, ?, ?, ?, ?, ?, ?, 0, ?)
						""",
				jobId, PROJECT_ID, asset.clipAssetId(), MODEL_SHA256, asset.sha256(), PROFILE_SHA256, status,
				"candidate-provenance-job-" + jobId, "3".repeat(64), "synthetic", Timestamp.from(createdAt),
				Timestamp.from(createdAt), handResult);
	}

	private void insertAttempt(UUID jobId, UUID attemptId, int attempt, UUID messageId, String status,
			String handResult, String failureReason, Instant createdAt) {
		var succeeded = "SUCCEEDED".equals(status);
		var handDetected = "HAND_DETECTED".equals(handResult);
		this.jdbcTemplate.update("""
				INSERT INTO ai_job_attempt (
				    ai_job_attempt_id, ai_job_id, attempt, message_id, lease_token_hash, status,
				    worker_id, lease_until, updated_at, created_at, analysis_complete, hand_result,
				    started_at, completed_at, total_video_frames, inference_frame_count, frames_with_hand,
				    frames_without_hand, left_hand_detection_count, right_hand_detection_count,
				    invalid_landmark_count, processing_duration_ms, failure_reason, retryable)
				VALUES (?, ?, ?, ?, ?, ?, 'synthetic-ai-worker', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", attemptId, jobId, attempt, messageId, LEASE_HASH, status,
				Timestamp.from(createdAt.plusSeconds(30)), Timestamp.from(createdAt), Timestamp.from(createdAt),
				succeeded, handResult, Timestamp.from(createdAt), Timestamp.from(createdAt), 6L, 3L,
				handDetected ? 1L : 0L, handDetected ? 2L : 3L, handDetected ? 1L : 0L, 0L, succeeded ? 0L : 1L, 17L,
				failureReason, !succeeded);
	}

	private void insertResult(UUID jobId, int attempt, UUID messageId, String resultSha256, Instant receivedAt) {
		this.jdbcTemplate.update(
				"INSERT INTO ai_job_result_inbox (message_id, ai_job_id, attempt, result_sha256, received_at) VALUES (?, ?, ?, ?, ?)",
				messageId, jobId, attempt, resultSha256, Timestamp.from(receivedAt));
	}

	private void insertCandidate(Fixture asset, UUID jobId, Instant createdAt, int frameTimestampMs) {
		this.jdbcTemplate.update("""
				INSERT INTO candidate_annotation (
				    candidate_annotation_id, ai_job_id, project_id, clip_asset_id, frame_timestamp_ms,
				    hand_index, handedness, handedness_score, bounding_box, landmarks,
				    image_width, image_height, valid_landmark_count, created_at)
				VALUES (?, ?, ?, ?, ?, 0, 'LEFT', ?, CAST(? AS JSONB), CAST(? AS JSONB), ?, ?, ?, ?)
				""", UUID.randomUUID(), jobId, PROJECT_ID, asset.clipAssetId(), frameTimestampMs,
				new BigDecimal("0.9100"), "{\"x\":0.1,\"y\":0.2,\"width\":0.2,\"height\":0.3}",
				"[{\"x\":0.1,\"y\":0.2,\"z\":-0.01}]", 1280, 720, 1, Timestamp.from(createdAt));
	}

	private static CandidateEvidenceQueryService.CandidateAnnotationSnapshot candidateSnapshot(UUID id, int timestampMs,
			String boundingBox, String landmarks) throws Exception {
		return new CandidateEvidenceQueryService.CandidateAnnotationSnapshot(id, UUID.randomUUID(), PROJECT_ID,
				UUID.randomUUID(), timestampMs, 1280, 720, 0, "LEFT", new BigDecimal("0.9100"),
				OBJECT_MAPPER.readTree(boundingBox), OBJECT_MAPPER.readTree(landmarks), 1);
	}

	private record Fixture(UUID clipAssetId, String sha256) {
	}

}
