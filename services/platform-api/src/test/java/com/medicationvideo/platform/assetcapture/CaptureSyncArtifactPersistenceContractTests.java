package com.medicationvideo.platform.assetcapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.medicationvideo.platform.support.CaptureSyncSyntheticFixtures;
import com.medicationvideo.platform.support.CaptureSyncTestSupport;
import com.medicationvideo.platform.support.ProjectMembershipTestData;

@SpringBootTest
@ActiveProfiles("test")
class CaptureSyncArtifactPersistenceContractTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000203");

	private static final String SHA256 = "a".repeat(64);

	private static final Instant CREATED_AT = Instant.parse("2026-08-12T10:00:00Z");

	@Autowired
	private JdbcTemplate jdbc;

	@AfterEach
	void clearArtifacts() {
		CaptureSyncTestSupport.clearSyntheticProjectFixtures(this.jdbc, PROJECT_ID);
	}

	@Test
	void migrationCreatesArtifactTableInboxConstraintAndImmutability() {
		assertEquals(1, this.jdbc.queryForObject("""
				SELECT count(*) FROM information_schema.tables
				WHERE table_name = 'capture_sync_artifact'
				""", Integer.class));
		assertEquals(1, this.jdbc.queryForObject("""
				INSERT INTO inbox_message (
				    message_id, consumer_name, message_type, aggregate_id, attempt,
				    result_fingerprint, result_status, processed_at)
				VALUES (?, 'capture-sync-result-v1', 'CaptureSyncResultV1', ?, 1, ?, 'SUCCEEDED', ?)
				RETURNING 1
				""", Integer.class, UUID.randomUUID(), UUID.randomUUID(), SHA256, Timestamp.from(CREATED_AT)));
	}

	@Test
	void artifactRowsAreAppendOnly() {
		ProjectMembershipTestData.ensureProject(this.jdbc, PROJECT_ID);
		var revisionId = insertMinimalRevision();
		var artifactId = UUID.randomUUID();
		this.jdbc.update("""
				INSERT INTO capture_sync_artifact (
				    capture_sync_artifact_id, capture_sync_revision_id, artifact_role, schema_version,
				    file_name, media_type, size_bytes, sha256, logical_content_sha256,
				    storage_bucket, object_key, artifact_manifest, artifact_manifest_sha256, created_at)
				VALUES (?, ?, 'SYNCHRONIZED_FRAME_PARQUET', 'synchronized-frame.v1', 'synchronized-frames.parquet',
				        'application/vnd.apache.parquet', 1, ?, ?, 'bucket', 'key', '{}'::jsonb, ?, ?)
				""", artifactId, revisionId, SHA256, SHA256, SHA256, Timestamp.from(CREATED_AT));
		assertThrows(DataAccessException.class, () -> this.jdbc
			.update("UPDATE capture_sync_artifact SET size_bytes = 2 WHERE capture_sync_artifact_id = ?", artifactId));
	}

	private UUID insertMinimalRevision() {
		ProjectMembershipTestData.ensureProject(this.jdbc, PROJECT_ID);
		var fixture = CaptureSyncSyntheticFixtures.insertCaptureFixture(this.jdbc, PROJECT_ID, SHA256, SHA256,
				CREATED_AT);
		var jobId = UUID.randomUUID();
		var attemptId = UUID.randomUUID();
		var revisionId = UUID.randomUUID();
		this.jdbc.update("""
				INSERT INTO capture_sync_job (
				    capture_sync_job_id, project_id, capture_session_id, camera_set_snapshot_id,
				    expected_camera_count, input_manifest_sha256, input_manifest,
				    sync_profile_key, sync_profile_version, sync_profile_sha256,
				    algorithm_key, algorithm_version, status, idempotency_key,
				    request_fingerprint, requested_by, created_at, updated_at)
				VALUES (?, ?, ?, ?, 1, ?, '{}'::jsonb, 'TEST/G0', 'v1', ?, 'CAPTURE_SYNC', 'v1',
				        'SUCCEEDED', 'artifact-contract-001', ?, 'synthetic-admin', ?, ?)
				""", jobId, PROJECT_ID, fixture.captureSessionId(), fixture.snapshotId(), SHA256, SHA256, SHA256,
				Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT));
		this.jdbc.update("""
				INSERT INTO capture_sync_job_attempt (
				    capture_sync_job_attempt_id, capture_sync_job_id, attempt, message_id, status,
				    lease_token_hash, created_at, updated_at)
				VALUES (?, ?, 1, ?, 'SUCCEEDED', ?, ?, ?)
				""", attemptId, jobId, UUID.randomUUID(), SHA256, Timestamp.from(CREATED_AT),
				Timestamp.from(CREATED_AT));
		this.jdbc.update("""
				INSERT INTO capture_sync_revision (
				    capture_sync_revision_id, capture_sync_job_id, capture_sync_job_attempt_id, project_id,
				    capture_session_id, camera_set_snapshot_id, reference_clock_source, time_basis_source,
				    timeline_rate_numerator, timeline_rate_denominator, timeline_step_numerator_ns,
				    timeline_step_denominator, allowed_tolerance_ns, algorithm_key, algorithm_version,
				    sync_profile_key, sync_profile_version, sync_profile_sha256, input_manifest_sha256,
				    logical_content_sha256, camera_count_expected, camera_count_received, sync_frame_count,
				    present_frame_count, missing_frame_count, invalid_frame_count, processing_duration_ms,
				    created_at, created_by)
				VALUES (?, ?, ?, ?, ?, ?, 'TEST_CLOCK', 'TEST_CAPTURE_TIME', 30, 1, 1000000000, 30, 20000000,
				        'CAPTURE_SYNC', 'v1', 'TEST/G0', 'v1', ?, ?, ?, 1, 1, 1, 1, 0, 0, 1, ?, 'synthetic-admin')
				""", revisionId, jobId, attemptId, PROJECT_ID, fixture.captureSessionId(), fixture.snapshotId(), SHA256,
				SHA256, SHA256, Timestamp.from(CREATED_AT));
		return revisionId;
	}

}
