package com.medicationvideo.platform.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.medicationvideo.platform.support.ProjectMembershipTestData;

class PostCleaningMigrationTests {

	private static final UUID PROJECT_ID =
			UUID.fromString("00000000-0000-0000-0000-000000000134");
	private static final String SHA256 = "a".repeat(64);
	private static final String DOCUMENT_SHA256 = "b".repeat(64);
	private static final String LABEL_SCHEMA_ID = "10000000-0000-0000-0000-000000000001";
	private static final String LABEL_SCHEMA_DIGEST =
			"305e92dcd10343e6c97b5780e59485b63183fadad7e9b87bc85db4bb037aa203";
	private static final Instant CREATED_AT = Instant.parse("2026-07-25T02:00:00Z");

	@Test
	void upgradesApprovedRevisionToCompletedTaskWithTruthfulLegacyWarning() {
		try (var postgres = new PostgreSQLContainer("postgres:18.4-bookworm")) {
			postgres.start();
			var dataSource = new DriverManagerDataSource(
					postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
			var jdbc = new JdbcTemplate(dataSource);

			migrateTo(dataSource, "33");
			ProjectMembershipTestData.ensureProject(jdbc, PROJECT_ID);
			var clipAssetId = insertClipAsset(jdbc);
			insertQualityReview(jdbc, clipAssetId);
			insertVerifiedMediaReport(jdbc, clipAssetId, 0);
			var taskId = UUID.randomUUID();
			var revisionId = UUID.randomUUID();
			var reviewId = UUID.randomUUID();
			insertApprovedRevision(jdbc, clipAssetId, taskId, revisionId, reviewId);

			migrateTo(dataSource, "34");

			assertEquals("COMPLETED", jdbc.queryForObject(
					"SELECT task_status FROM annotation_task WHERE annotation_task_id = ?",
					String.class,
					taskId));
			assertEquals(1, jdbc.queryForObject(
					"""
					SELECT count(*)
					FROM cleaning_run
					WHERE cleaning_run_id = ?
					  AND annotation_revision_id = ?
					  AND annotation_review_id = ?
					  AND trigger_type = 'LEGACY_MIGRATION'
					  AND asset_sha256 = ?
					  AND document_sha256 = ?
					""",
					Integer.class,
					revisionId,
					revisionId,
					reviewId,
					SHA256,
					DOCUMENT_SHA256));
			assertEquals(0L, jdbc.queryForObject(
					"SELECT verified_duration_microseconds FROM cleaning_run WHERE cleaning_run_id = ?",
					Long.class,
					revisionId));
			assertEquals(1, jdbc.queryForObject(
					"""
					SELECT count(*)
					FROM cleaning_result
					WHERE cleaning_result_id = ?
					  AND outcome = 'WARNING'
					  AND reason_codes @> '["LEGACY_APPROVED_REVISION_REQUIRES_REVALIDATION"]'::jsonb
					""",
					Integer.class,
					reviewId));
			assertTrue(jdbc.queryForObject(
					"SELECT evaluated_at FROM cleaning_result WHERE cleaning_result_id = ?",
					Timestamp.class,
					reviewId).toInstant().isAfter(CREATED_AT.plusSeconds(20)));
			assertEquals(1, jdbc.queryForObject(
					"""
					SELECT count(*)
					FROM audit_event
					WHERE action = 'POST_ANNOTATION_CLEANING_MIGRATED'
					  AND object_id = ?
					  AND actor_type = 'SYSTEM'
					""",
					Integer.class,
					reviewId.toString()));
		}
	}

	private static void migrateTo(DriverManagerDataSource dataSource, String target) {
		Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration")
				.target(target)
				.load()
				.migrate();
	}

	private static UUID insertClipAsset(JdbcTemplate jdbc) {
		var batchId = UUID.randomUUID();
		var uploadFileId = UUID.randomUUID();
		var clientFileId = UUID.randomUUID();
		var clipAssetId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO upload_batch (
				    batch_id, project_id, idempotency_key, request_fingerprint, device_id,
				    initiated_by_actor_id, file_count, total_size_bytes, manifest_sha256,
				    status, expires_at, created_at, updated_at, verified_manifest_sha256, completed_at)
				VALUES (?, ?, ?, ?, 'synthetic-cleaning-device', ?, 1, 1234, ?,
				        'COMPLETED', ?, ?, ?, ?, ?)
				""",
				batchId,
				PROJECT_ID,
				"cleaning-batch-" + batchId,
				SHA256,
				ProjectMembershipTestData.ADMIN_ACTOR,
				SHA256,
				Timestamp.from(CREATED_AT.plusSeconds(3600)),
				Timestamp.from(CREATED_AT),
				Timestamp.from(CREATED_AT),
				SHA256,
				Timestamp.from(CREATED_AT));
		jdbc.update("""
				INSERT INTO upload_file (
				    upload_file_id, batch_id, client_file_id, idempotency_key, request_fingerprint,
				    file_kind, content_type, size_bytes, sha256, part_size_bytes, total_parts,
				    status, created_at, updated_at, storage_bucket, object_key, storage_upload_id,
				    multipart_created_at, completion_started_at, completion_finished_at,
				    verified_sha256, completed_storage_etag)
				VALUES (?, ?, ?, ?, ?, 'VIDEO', 'video/mp4', 1234, ?, 8388608, 1,
				        'UPLOADED', ?, ?, 'synthetic-bucket', ?, ?, ?, ?, ?, ?, 'synthetic-etag')
				""",
				uploadFileId,
				batchId,
				clientFileId,
				"cleaning-file-" + uploadFileId,
				SHA256,
				SHA256,
				Timestamp.from(CREATED_AT),
				Timestamp.from(CREATED_AT),
				"objects/" + uploadFileId,
				"multipart-" + uploadFileId,
				Timestamp.from(CREATED_AT),
				Timestamp.from(CREATED_AT),
				Timestamp.from(CREATED_AT),
				SHA256);
		jdbc.update("""
				INSERT INTO clip_asset (
				    clip_asset_id, project_id, batch_id, source_upload_file_id, client_file_id,
				    content_type, size_bytes, sha256, storage_bucket, object_key, storage_etag,
				    asset_status, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, ?, 'video/mp4', 1234, ?, 'synthetic-bucket', ?,
				        'synthetic-etag', 'INGESTED', ?, ?, 0)
				""",
				clipAssetId,
				PROJECT_ID,
				batchId,
				uploadFileId,
				clientFileId,
				SHA256,
				"objects/" + clipAssetId,
				Timestamp.from(CREATED_AT),
				Timestamp.from(CREATED_AT));
		return clipAssetId;
	}

	private static void insertQualityReview(JdbcTemplate jdbc, UUID clipAssetId) {
		jdbc.update("""
				INSERT INTO quality_review (
				    quality_review_id, project_id, clip_asset_id, decision, reason_code,
				    reviewed_by, reviewed_at, version)
				VALUES (?, ?, ?, 'QUALIFIED', 'MEETS_REQUIREMENTS', ?, ?, 2)
				""",
				UUID.randomUUID(),
				PROJECT_ID,
				clipAssetId,
				ProjectMembershipTestData.MEMBER_ACTOR,
				Timestamp.from(CREATED_AT));
	}

	private static void insertVerifiedMediaReport(
			JdbcTemplate jdbc,
			UUID clipAssetId,
			long durationMicroseconds) {
		var mediaJobId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO media_job (
				    media_job_id, project_id, clip_asset_id, job_type, input_digest,
				    input_size_bytes, content_type, status, idempotency_key,
				    request_fingerprint, created_at, updated_at, version,
				    probe_profile_key, recipe_digest)
				VALUES (?, ?, ?, 'MEDIA_PROBE', ?, 1234, 'video/mp4', 'SUCCEEDED', ?, ?, ?, ?, 0,
				        'media-probe-baseline-v1',
				        '8900937958d0fb81a9c9bf2d9e81ac3e57f35d838d5a4da52b58ccf0f5cd5765')
				""",
				mediaJobId,
				PROJECT_ID,
				clipAssetId,
				SHA256,
				"cleaning-media-job-" + mediaJobId,
				SHA256,
				Timestamp.from(CREATED_AT),
				Timestamp.from(CREATED_AT));
		jdbc.update("""
				INSERT INTO media_report (
				    media_report_id, media_job_id, project_id, clip_asset_id,
				    report_schema_version, input_digest, recipe_digest, software_digest,
				    container_format, duration_microseconds, video_stream_count,
				    primary_video_stream_index, video_codec, width, height,
				    time_base_numerator, time_base_denominator, variable_frame_rate,
				    audio_stream_count, decode_verified, input_digest_verified,
				    warning_codes, created_at)
				VALUES (?, ?, ?, ?, 'media-report.v1', ?,
				        '8900937958d0fb81a9c9bf2d9e81ac3e57f35d838d5a4da52b58ccf0f5cd5765',
				        'sha256:207391c770bee4a8dfe3530ef5f31af07904950f9bc2dcb822fedb086222164a',
				        'mov,mp4,m4a,3gp,3g2,mj2', ?, 1, 0, 'h264', 640, 360,
				        1, 90000, false, 0, true, true, '[]'::jsonb, ?)
				""",
				UUID.randomUUID(),
				mediaJobId,
				PROJECT_ID,
				clipAssetId,
				SHA256,
				durationMicroseconds,
				Timestamp.from(CREATED_AT.plusSeconds(5)));
	}

	private static void insertApprovedRevision(
			JdbcTemplate jdbc,
			UUID clipAssetId,
			UUID taskId,
			UUID revisionId,
			UUID reviewId) {
		jdbc.update("""
				INSERT INTO annotation_task (
				    annotation_task_id, project_id, clip_asset_id, label_schema_id,
				    label_schema_digest, assignee_actor_id, created_by, idempotency_key,
				    task_status, created_at, updated_at, version)
				VALUES (?, ?, ?, ?::uuid, ?, ?, ?, ?, 'SUBMITTED', ?, ?, 1)
				""",
				taskId,
				PROJECT_ID,
				clipAssetId,
				LABEL_SCHEMA_ID,
				LABEL_SCHEMA_DIGEST,
				ProjectMembershipTestData.MEMBER_ACTOR,
				ProjectMembershipTestData.ADMIN_ACTOR,
				"cleaning-task-" + taskId,
				Timestamp.from(CREATED_AT),
				Timestamp.from(CREATED_AT));
		jdbc.update("""
				INSERT INTO annotation_revision (
				    annotation_revision_id, project_id, annotation_task_id, clip_asset_id,
				    revision_number, annotation_schema_version, label_schema_id,
				    label_schema_digest, annotation_document, document_sha256,
				    submitted_by, idempotency_key, submitted_at)
				VALUES (?, ?, ?, ?, 1, 'annotation-document.v1', ?::uuid, ?,
				        '{"schema_version":"annotation-document.v1","segments":[{"start_ms":0,"end_ms":10,"label":"prepare_workspace"}]}'::jsonb,
				        ?, ?, ?, ?)
				""",
				revisionId,
				PROJECT_ID,
				taskId,
				clipAssetId,
				LABEL_SCHEMA_ID,
				LABEL_SCHEMA_DIGEST,
				DOCUMENT_SHA256,
				ProjectMembershipTestData.MEMBER_ACTOR,
				"cleaning-revision-" + revisionId,
				Timestamp.from(CREATED_AT.plusSeconds(10)));
		jdbc.update("""
				INSERT INTO annotation_review (
				    annotation_review_id, project_id, annotation_revision_id, decision,
				    review_note, reviewed_by, idempotency_key, reviewed_at)
				VALUES (?, ?, ?, 'APPROVED', NULL, ?, ?, ?)
				""",
				reviewId,
				PROJECT_ID,
				revisionId,
				ProjectMembershipTestData.ADMIN_ACTOR,
				"cleaning-review-" + reviewId,
				Timestamp.from(CREATED_AT.plusSeconds(20)));
	}
}
