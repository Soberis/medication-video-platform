package com.medicationvideo.platform.assetcapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.medicationvideo.platform.support.CaptureSyncTestSupport;
import com.medicationvideo.platform.support.ProjectMembershipTestData;

@SpringBootTest
@ActiveProfiles("test")
class CaptureSyncPersistenceContractTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

	private static final UUID OTHER_PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");

	private static final String SHA256 = "a".repeat(64);

	private static final String RECIPE_DIGEST = "b".repeat(64);

	private static final Instant CREATED_AT = Instant.parse("2026-08-12T10:00:00Z");

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private CaptureSyncJobService jobService;

	@AfterEach
	void clearSyntheticFixtures() {
		CaptureSyncTestSupport.clearCaptureSyncTables(this.jdbc);
		this.jdbc.update("DELETE FROM media_report WHERE project_id IN (?, ?)", PROJECT_ID, OTHER_PROJECT_ID);
		this.jdbc.update("DELETE FROM media_job WHERE project_id IN (?, ?)", PROJECT_ID, OTHER_PROJECT_ID);
		this.jdbc.update("""
				DELETE FROM camera_stream
				WHERE capture_session_id IN (
				    SELECT capture_session_id FROM capture_session WHERE project_id IN (?, ?)
				)
				""", PROJECT_ID, OTHER_PROJECT_ID);
		this.jdbc.update("""
				DELETE FROM camera_set_snapshot_member
				WHERE camera_set_snapshot_id IN (
				    SELECT snapshot.camera_set_snapshot_id
				    FROM camera_set_snapshot snapshot
				    JOIN capture_session capture
				      ON capture.capture_session_id = snapshot.capture_session_id
				    WHERE capture.project_id IN (?, ?)
				)
				""", PROJECT_ID, OTHER_PROJECT_ID);
		this.jdbc.update("""
				DELETE FROM camera_set_snapshot
				WHERE capture_session_id IN (
				    SELECT capture_session_id FROM capture_session WHERE project_id IN (?, ?)
				)
				""", PROJECT_ID, OTHER_PROJECT_ID);
		this.jdbc.update("DELETE FROM capture_session WHERE project_id IN (?, ?)", PROJECT_ID, OTHER_PROJECT_ID);
		this.jdbc.update("DELETE FROM clip_asset WHERE project_id IN (?, ?)", PROJECT_ID, OTHER_PROJECT_ID);
		this.jdbc.update("""
				DELETE FROM upload_file
				WHERE batch_id IN (SELECT batch_id FROM upload_batch WHERE project_id IN (?, ?))
				""", PROJECT_ID, OTHER_PROJECT_ID);
		this.jdbc.update("DELETE FROM upload_batch WHERE project_id IN (?, ?)", PROJECT_ID, OTHER_PROJECT_ID);
		this.jdbc.update("""
				DELETE FROM camera_set_member
				WHERE camera_set_id IN (SELECT camera_set_id FROM camera_set WHERE project_id IN (?, ?))
				""", PROJECT_ID, OTHER_PROJECT_ID);
		this.jdbc.update("DELETE FROM camera_set WHERE project_id IN (?, ?)", PROJECT_ID, OTHER_PROJECT_ID);
		this.jdbc.update("DELETE FROM camera_channel WHERE project_id IN (?, ?)", PROJECT_ID, OTHER_PROJECT_ID);
		this.jdbc.update("DELETE FROM membership WHERE project_id IN (?, ?)", PROJECT_ID, OTHER_PROJECT_ID);
		this.jdbc.update("DELETE FROM project WHERE project_id IN (?, ?)", PROJECT_ID, OTHER_PROJECT_ID);
	}

	@Test
	void migrationCreatesProjectScopedSyncCoreAndJpaContextValidatesItsMappings() {
		assertEquals(1, tableExists("capture_sync_job"));
		assertEquals(1, tableExists("capture_sync_job_attempt"));
		assertEquals(1, tableExists("capture_sync_revision"));
		assertEquals(1, tableExists("capture_sync_stream_revision"));
		assertEquals(6, this.jdbc.queryForObject("""
				SELECT count(*)
				FROM pg_constraint
				WHERE conname IN ('fk_capture_sync_job_capture_project',
				                  'fk_capture_sync_revision_capture_project',
				                  'fk_capture_sync_stream_revision_asset_project',
				                  'uk_capture_sync_job_business',
				                  'uk_capture_sync_attempt_number',
				                  'uk_capture_sync_stream_revision_camera')
				""", Integer.class));
	}

	@Test
	void foreignKeysAndBusinessUniquenessRejectCrossProjectAndDuplicateFacts() {
		var fixture = insertFixture(PROJECT_ID);
		var otherProjectFixture = insertFixture(OTHER_PROJECT_ID);

		assertThrows(DataAccessException.class,
				() -> this.jdbc.update("""
						INSERT INTO capture_sync_job (
						    capture_sync_job_id, project_id, capture_session_id, camera_set_snapshot_id,
						    expected_camera_count, input_manifest_sha256, input_manifest,
						    sync_profile_key, sync_profile_version, sync_profile_sha256,
						    algorithm_key, algorithm_version, status, idempotency_key,
						    request_fingerprint, requested_by, created_at, updated_at)
						VALUES (?, ?, ?, ?, 1, ?, '{}'::jsonb, 'TEST/G0', 'v1', ?, 'CAPTURE_SYNC', 'v1',
						        'QUEUED', ?, ?, 'synthetic-admin', ?, ?)
						""", UUID.randomUUID(), OTHER_PROJECT_ID, fixture.captureSessionId(), fixture.snapshotId(),
						SHA256, SHA256, "sync-idempotency-cross-project-001", SHA256, Timestamp.from(CREATED_AT),
						Timestamp.from(CREATED_AT)));

		insertJob(fixture, UUID.randomUUID(), "sync-idempotency-first-001");
		assertThrows(DataAccessException.class,
				() -> insertJob(fixture, UUID.randomUUID(), "sync-idempotency-second-001"));
		assertEquals(1, this.jdbc.queryForObject("SELECT count(*) FROM capture_sync_job WHERE project_id = ?",
				Integer.class, PROJECT_ID));
		assertEquals(OTHER_PROJECT_ID, otherProjectFixture.projectId());
	}

	@Test
	void attemptsAreUniqueAndRevisionRowsAreAppendOnly() {
		var fixture = insertFixture(PROJECT_ID);
		var jobId = UUID.randomUUID();
		insertJob(fixture, jobId, "sync-idempotency-attempt-001");
		var attemptId = UUID.randomUUID();
		insertAttempt(jobId, attemptId, 1, "QUEUED");

		assertThrows(DataAccessException.class, () -> insertAttempt(jobId, UUID.randomUUID(), 1, "QUEUED"));

		this.jdbc.update("UPDATE capture_sync_job SET status = 'SUCCEEDED' WHERE capture_sync_job_id = ?", jobId);
		this.jdbc.update(
				"UPDATE capture_sync_job_attempt SET status = 'SUCCEEDED' WHERE capture_sync_job_attempt_id = ?",
				attemptId);
		var revisionId = UUID.randomUUID();
		insertRevision(fixture, jobId, attemptId, revisionId);

		assertThrows(DataAccessException.class,
				() -> this.jdbc.update(
						"UPDATE capture_sync_revision SET created_by = 'tampered' WHERE capture_sync_revision_id = ?",
						revisionId));
		assertThrows(DataAccessException.class, () -> this.jdbc
			.update("DELETE FROM capture_sync_revision WHERE capture_sync_revision_id = ?", revisionId));
	}

	@Test
	void concurrentNaturalKeyRequestsUseIndependentPostgresTransactionsAndCreateOneJobAndAttempt() throws Exception {
		var fixture = insertFixture(PROJECT_ID);
		var input = syncInput(fixture);
		var profile = SyncProfile.testOnly("TEST/G0", "v1", SHA256, "CAPTURE_SYNC", "v1", Rational.of(30, 1),
				Rational.of(1_000_000_000L, 30), 20_000_000L, "TEST_CLOCK", "TEST_ANCHOR_DRIFT");
		var ready = new CyclicBarrier(2);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var first = executor.submit(() -> concurrentCreate(ready, input, profile, "sync-concurrent-a-001"));
			var second = executor.submit(() -> concurrentCreate(ready, input, profile, "sync-concurrent-b-001"));
			var firstJob = first.get(30, TimeUnit.SECONDS);
			var secondJob = second.get(30, TimeUnit.SECONDS);

			assertEquals(firstJob.getCaptureSyncJobId(), secondJob.getCaptureSyncJobId());
			assertEquals(1, this.jdbc.queryForObject("SELECT count(*) FROM capture_sync_job WHERE project_id = ?",
					Integer.class, PROJECT_ID));
			assertEquals(1, this.jdbc.queryForObject("""
					SELECT count(*)
					FROM capture_sync_job_attempt attempt
					JOIN capture_sync_job job ON job.capture_sync_job_id = attempt.capture_sync_job_id
					WHERE job.project_id = ?
					""", Integer.class, PROJECT_ID));
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void streamRevisionKeepsStableCameraIdentityAndRejectsMismatchedMember() {
		var fixture = insertFixture(PROJECT_ID);
		var jobId = UUID.randomUUID();
		insertJob(fixture, jobId, "sync-idempotency-camera-001");
		var attemptId = UUID.randomUUID();
		insertAttempt(jobId, attemptId, 1, "SUCCEEDED");
		var revisionId = UUID.randomUUID();
		insertRevision(fixture, jobId, attemptId, revisionId);

		assertThrows(DataAccessException.class,
				() -> this.jdbc.update("""
						INSERT INTO capture_sync_stream_revision (
						    capture_sync_stream_revision_id, capture_sync_revision_id, project_id, capture_session_id,
						    camera_set_snapshot_id, camera_set_snapshot_member_id, camera_number,
						    camera_stream_id, clip_asset_id, stream_presence_state,
						    source_input_sha256, source_media_report_id, source_time_base_digest,
						    anchor_source_pts_ns, anchor_capture_timestamp_ns, present_frame_count,
						    missing_frame_count, invalid_frame_count, created_at)
						VALUES (?, ?, ?, ?, ?, ?, 2, ?, ?, 'PRESENT', ?, ?, ?, 0, 0, 1, 0, 0, ?)
						""", UUID.randomUUID(), revisionId, PROJECT_ID, fixture.captureSessionId(),
						fixture.snapshotId(), fixture.snapshotMemberId(), fixture.cameraStreamId(),
						fixture.clipAssetId(), SHA256, fixture.mediaReportId(), SHA256, Timestamp.from(CREATED_AT)));
	}

	private Fixture insertFixture(UUID projectId) {
		ProjectMembershipTestData.ensureProject(this.jdbc, projectId);
		var cameraChannelId = UUID.randomUUID();
		var cameraSetId = UUID.randomUUID();
		var cameraSetMemberId = UUID.randomUUID();
		var batchId = UUID.randomUUID();
		var metadataFileId = UUID.randomUUID();
		var videoFileId = UUID.randomUUID();
		var clientMetadataId = UUID.randomUUID();
		var clientVideoId = UUID.randomUUID();
		var clipAssetId = UUID.randomUUID();
		var captureSessionId = UUID.randomUUID();
		var snapshotId = UUID.randomUUID();
		var snapshotMemberId = UUID.randomUUID();
		var cameraStreamId = UUID.randomUUID();
		var mediaJobId = UUID.randomUUID();
		var mediaReportId = UUID.randomUUID();
		var time = Timestamp.from(CREATED_AT);
		this.jdbc.update(
				"INSERT INTO camera_channel (camera_channel_id, project_id, provisioning_key, created_at) VALUES (?, ?, ?, ?)",
				cameraChannelId, projectId, "sync-channel-" + cameraChannelId, time);
		this.jdbc.update("""
				INSERT INTO camera_set (camera_set_id, project_id, camera_set_key, configuration_version,
				    layout_version, request_fingerprint, created_at)
				VALUES (?, ?, ?, 1, 'layout-v1', ?, ?)
				""", cameraSetId, projectId, "sync-set-" + cameraSetId, SHA256, time);
		this.jdbc.update("""
				INSERT INTO camera_set_member (camera_set_member_id, camera_set_id, camera_channel_id,
				    camera_index, camera_role, mount_position, enabled, created_at)
				VALUES (?, ?, ?, 0, 'FRONT', 'front', TRUE, ?)
				""", cameraSetMemberId, cameraSetId, cameraChannelId, time);
		this.jdbc.update("""
				INSERT INTO upload_batch (
				    batch_id, project_id, idempotency_key, request_fingerprint, device_id,
				    initiated_by_actor_id, file_count, total_size_bytes, manifest_sha256, status,
				    expires_at, created_at, updated_at, verified_manifest_sha256, completed_at)
				VALUES (?, ?, ?, ?, 'sync-device', 'sync-actor', 2, 2468, ?, 'COMPLETED', ?, ?, ?, ?, ?)
				""", batchId, projectId, "sync-batch-" + batchId, SHA256, SHA256,
				Timestamp.from(CREATED_AT.plusSeconds(3600)), time, time, SHA256, time);
		insertUploadedFile(metadataFileId, batchId, clientMetadataId, "METADATA", "metadata-" + metadataFileId);
		insertUploadedFile(videoFileId, batchId, clientVideoId, "VIDEO", "video-" + videoFileId);
		this.jdbc.update("""
				INSERT INTO clip_asset (
				    clip_asset_id, project_id, batch_id, source_upload_file_id, client_file_id,
				    content_type, size_bytes, sha256, storage_bucket, object_key, storage_etag,
				    asset_status, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'video/mp4', 1234, ?, 'sync-bucket', ?, 'etag', 'INGESTED', ?, ?)
				""", clipAssetId, projectId, batchId, videoFileId, clientVideoId, SHA256, "objects/" + clipAssetId,
				time, time);
		this.jdbc.update("""
				INSERT INTO capture_session (
				    capture_session_id, project_id, batch_id, device_id, camera_set_id,
				    metadata_upload_file_id, metadata_schema_version, metadata_sha256,
				    expected_camera_count, received_camera_count, sync_group_id, device_time_utc,
				    device_timezone, server_receive_time, assembly_status, quality_status, created_at, updated_at)
				VALUES (?, ?, ?, 'sync-device', ?, ?, 'capture-session-metadata-v1', ?, 1, 1,
				        'sync-group', ?, 'UTC', ?, 'COMPLETE', 'PASS', ?, ?)
				""", captureSessionId, projectId, batchId, cameraSetId, metadataFileId, SHA256, time, time, time, time);
		this.jdbc.update("""
				INSERT INTO camera_set_snapshot (
				    camera_set_snapshot_id, capture_session_id, source_camera_set_id, camera_set_key,
				    configuration_version, layout_version, expected_camera_count, created_at)
				VALUES (?, ?, ?, ?, 1, 'layout-v1', 1, ?)
				""", snapshotId, captureSessionId, cameraSetId, "sync-set-" + cameraSetId, time);
		this.jdbc.update("""
				INSERT INTO camera_set_snapshot_member (
				    camera_set_snapshot_member_id, camera_set_snapshot_id, camera_channel_id, camera_index,
				    camera_role, mount_position, enabled, enabled_for_hand_detection, created_at)
				VALUES (?, ?, ?, 0, 'FRONT', 'front', TRUE, TRUE, ?)
				""", snapshotMemberId, snapshotId, cameraChannelId, time);
		this.jdbc.update("""
				INSERT INTO camera_stream (
				    camera_stream_id, capture_session_id, camera_set_snapshot_member_id, camera_channel_id,
				    camera_index, clip_asset_id, client_file_id, record_start_utc, time_offset_ms,
				    declared_sync_status, stream_status, quality_status, created_at, updated_at)
				VALUES (?, ?, ?, ?, 0, ?, ?, ?, 0, 'UNKNOWN', 'INGESTED', 'PASS', ?, ?)
				""", cameraStreamId, captureSessionId, snapshotMemberId, cameraChannelId, clipAssetId, clientVideoId,
				time, time, time);
		insertMediaReport(mediaJobId, mediaReportId, projectId, clipAssetId, time);
		return new Fixture(projectId, captureSessionId, snapshotId, snapshotMemberId, cameraStreamId, clipAssetId,
				mediaReportId);
	}

	private CaptureSyncJob concurrentCreate(CyclicBarrier ready, CaptureSyncInput input, SyncProfile profile,
			String idempotencyKey) throws Exception {
		ready.await(30, TimeUnit.SECONDS);
		return this.jobService.create(input, profile, idempotencyKey, "synthetic-admin");
	}

	private CaptureSyncInput syncInput(Fixture fixture) {
		var stream = new SyncStream(1, fixture.cameraStreamId(), fixture.clipAssetId(), SHA256, fixture.mediaReportId(),
				SHA256, new StreamTimeMapping(0, 0, null, "TEST", "SYNTHETIC", 1, true),
				List.of(SourceFrame.valid(0, 0, 0, 0)));
		return new CaptureSyncInput(PROJECT_ID, fixture.captureSessionId(), fixture.snapshotId(), "sync-group", SHA256,
				List.of(new ExpectedCamera(fixture.snapshotMemberId(), 1)), List.of(stream));
	}

	private void insertUploadedFile(UUID fileId, UUID batchId, UUID clientFileId, String kind, String objectKey) {
		var time = Timestamp.from(CREATED_AT);
		this.jdbc.update("""
				INSERT INTO upload_file (
				    upload_file_id, batch_id, client_file_id, idempotency_key, request_fingerprint,
				    file_kind, content_type, size_bytes, sha256, part_size_bytes, total_parts, status,
				    created_at, updated_at, storage_bucket, object_key, storage_upload_id,
				    multipart_created_at, completion_started_at, completion_finished_at,
				    verified_sha256, completed_storage_etag)
				VALUES (?, ?, ?, ?, ?, ?, ?, 1234, ?, 8388608, 1, 'UPLOADED', ?, ?,
				        'sync-bucket', ?, ?, ?, ?, ?, ?, 'etag')
				""", fileId, batchId, clientFileId, "sync-file-" + fileId, SHA256, kind,
				"METADATA".equals(kind) ? "application/json" : "video/mp4", SHA256, time, time, objectKey,
				"upload-" + fileId, time, time, time, SHA256);
	}

	private void insertMediaReport(UUID mediaJobId, UUID mediaReportId, UUID projectId, UUID clipAssetId,
			Timestamp time) {
		this.jdbc.update("""
				INSERT INTO media_job (
				    media_job_id, project_id, clip_asset_id, job_type, input_digest, input_size_bytes,
				    content_type, status, idempotency_key, request_fingerprint, created_at, updated_at,
				    probe_profile_key, recipe_digest)
				VALUES (?, ?, ?, 'MEDIA_PROBE', ?, 1234, 'video/mp4', 'SUCCEEDED', ?, ?, ?, ?,
				        'media-probe-baseline-v1', ?)
				""", mediaJobId, projectId, clipAssetId, SHA256, "sync-media-job-" + mediaJobId, SHA256, time, time,
				RECIPE_DIGEST);
		this.jdbc.update("""
				INSERT INTO media_report (
				    media_report_id, media_job_id, project_id, clip_asset_id, report_schema_version,
				    input_digest, recipe_digest, software_digest, container_format, video_codec,
				    duration_microseconds, width, height, video_stream_count, primary_video_stream_index,
				    time_base_numerator, time_base_denominator, variable_frame_rate, audio_stream_count,
				    decode_verified, input_digest_verified, warning_codes, created_at)
				VALUES (?, ?, ?, ?, 'media-report.v1', ?, ?, 'sha256:%s', 'mp4', 'h264', 1000000, 640, 360,
				        1, 0, 1, 90000, FALSE, 0, TRUE, TRUE, '[]'::jsonb, ?)
				""".formatted(SHA256), mediaReportId, mediaJobId, projectId, clipAssetId, SHA256, RECIPE_DIGEST, time);
	}

	private void insertJob(Fixture fixture, UUID jobId, String idempotencyKey) {
		this.jdbc.update("""
				INSERT INTO capture_sync_job (
				    capture_sync_job_id, project_id, capture_session_id, camera_set_snapshot_id,
				    expected_camera_count, input_manifest_sha256, input_manifest,
				    sync_profile_key, sync_profile_version, sync_profile_sha256,
				    algorithm_key, algorithm_version, status, idempotency_key,
				    request_fingerprint, requested_by, created_at, updated_at)
				VALUES (?, ?, ?, ?, 1, ?, '{}'::jsonb, 'TEST/G0', 'v1', ?, 'CAPTURE_SYNC', 'v1',
				        'QUEUED', ?, ?, 'synthetic-admin', ?, ?)
				""", jobId, fixture.projectId(), fixture.captureSessionId(), fixture.snapshotId(), SHA256, SHA256,
				idempotencyKey, SHA256, Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT));
	}

	private void insertAttempt(UUID jobId, UUID attemptId, int number, String status) {
		this.jdbc.update("""
				INSERT INTO capture_sync_job_attempt (
				    capture_sync_job_attempt_id, capture_sync_job_id, attempt, message_id, status,
				    lease_token_hash, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				""", attemptId, jobId, number, UUID.randomUUID(), status, SHA256, Timestamp.from(CREATED_AT),
				Timestamp.from(CREATED_AT));
	}

	private void insertRevision(Fixture fixture, UUID jobId, UUID attemptId, UUID revisionId) {
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
				VALUES (?, ?, ?, ?, ?, ?, 'TEST_CLOCK', 'MEDIA_PTS', 30, 1, 1000000000, 30, 20000000,
				        'CAPTURE_SYNC', 'v1', 'TEST/G0', 'v1', ?, ?, ?, 1, 1, 1, 1, 0, 0, 1,
				        ?, 'synthetic-admin')
				""", revisionId, jobId, attemptId, fixture.projectId(), fixture.captureSessionId(),
				fixture.snapshotId(), SHA256, SHA256, SHA256, Timestamp.from(CREATED_AT));
	}

	private int tableExists(String table) {
		return this.jdbc.queryForObject("""
				SELECT count(*)
				FROM information_schema.tables
				WHERE table_schema = 'public' AND table_name = ?
				""", Integer.class, table);
	}

	private record Fixture(UUID projectId, UUID captureSessionId, UUID snapshotId, UUID snapshotMemberId,
			UUID cameraStreamId, UUID clipAssetId, UUID mediaReportId) {
	}

}
