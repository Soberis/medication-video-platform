package com.medicationvideo.platform.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class PrivacyQuarantineOwnershipMigrationTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

	private static final UUID OTHER_PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");

	private static final String SHA256 = "a".repeat(64);

	private static final String OTHER_SHA256 = "b".repeat(64);

	private static final Instant CREATED_AT = Instant.parse("2026-07-17T02:00:00Z");

	@Test
	void upgradesHistoricalQuarantineEvidenceAndRejectsCrossProjectOrForgedBindings() {
		try (var postgres = new PostgreSQLContainer("postgres:18.4-bookworm")) {
			postgres.start();
			var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
					postgres.getPassword());
			var jdbc = new JdbcTemplate(dataSource);

			migrateTo(dataSource, "19");
			var clipAssetId = insertClipAsset(jdbc, PROJECT_ID, SHA256);
			var historicalAssessmentId = UUID.randomUUID();
			insertPrivacyAssessment(jdbc, historicalAssessmentId, PROJECT_ID, clipAssetId, SHA256, "SUSPECTED",
					"QUARANTINE_REQUIRED", "RISK_REQUIRES_ISOLATION");
			jdbc.update("INSERT INTO privacy_finding (privacy_assessment_id, finding_type) VALUES (?, 'FACE')",
					historicalAssessmentId);

			migrateTo(dataSource, "21");

			assertEquals(1,
					jdbc.queryForObject(
							"SELECT count(*) FROM flyway_schema_history WHERE version = '21' AND success = true",
							Integer.class));
			assertEquals(1, jdbc.queryForObject("""
					SELECT count(*)
					FROM quarantine_record
					WHERE project_id = ?
					  AND clip_asset_id = ?
					  AND input_digest = ?
					  AND source_type = 'PRIVACY_ASSESSMENT'
					  AND source_id = ?
					  AND source_version = 1
					  AND status = 'ACTIVE'
					  AND activated_by = 'flyway-v20'
					""", Integer.class, PROJECT_ID, clipAssetId, SHA256, historicalAssessmentId));
			assertEquals(1, jdbc.queryForObject("""
					SELECT count(*)
					FROM audit_event
					WHERE project_id = ?
					  AND action = 'QUARANTINE_ACTIVATED'
					  AND reason_code = 'PRIVACY_RISK'
					  AND request_id = ?
					  AND details_json ->> 'clip_asset_id' = ?
					  AND details_json ->> 'source_id' = ?
					  AND details_json ->> 'source_version' = '1'
					""", Integer.class, PROJECT_ID, "v20-quarantine-" + historicalAssessmentId, clipAssetId.toString(),
					historicalAssessmentId.toString()));

			var crossProjectAssessment = assertThrows(DataIntegrityViolationException.class,
					() -> insertPrivacyAssessment(jdbc, UUID.randomUUID(), OTHER_PROJECT_ID, clipAssetId, SHA256,
							"NONE", "RELEASE_REVIEW_REQUIRED", "NO_IDENTIFIABLE_INFORMATION_OBSERVED"));
			assertTrue(crossProjectAssessment.getMostSpecificCause()
				.getMessage()
				.contains("fk_privacy_assessment_clip_asset_project"));

			var crossProjectQuarantine = assertThrows(DataIntegrityViolationException.class,
					() -> insertQuarantineRecord(jdbc, OTHER_PROJECT_ID, clipAssetId, SHA256, historicalAssessmentId,
							1));
			var crossProjectQuarantineMessage = crossProjectQuarantine.getMostSpecificCause().getMessage();
			assertTrue(crossProjectQuarantineMessage.contains("fk_quarantine_record_clip_asset_project")
					|| crossProjectQuarantineMessage.contains("fk_quarantine_record_privacy_assessment_source"));

			var sourceClipAssetId = insertClipAsset(jdbc, PROJECT_ID, SHA256);
			var sourceAssessmentId = UUID.randomUUID();
			insertPrivacyAssessment(jdbc, sourceAssessmentId, PROJECT_ID, sourceClipAssetId, SHA256, "NONE",
					"RELEASE_REVIEW_REQUIRED", "NO_IDENTIFIABLE_INFORMATION_OBSERVED");

			var wrongSourceAsset = assertThrows(DataIntegrityViolationException.class,
					() -> insertQuarantineRecord(jdbc, PROJECT_ID, clipAssetId, SHA256, sourceAssessmentId, 1));
			assertTrue(wrongSourceAsset.getMostSpecificCause()
				.getMessage()
				.contains("fk_quarantine_record_privacy_assessment_source"));

			var wrongSourceDigest = assertThrows(DataIntegrityViolationException.class,
					() -> insertQuarantineRecord(jdbc, PROJECT_ID, sourceClipAssetId, OTHER_SHA256, sourceAssessmentId,
							1));
			assertTrue(wrongSourceDigest.getMostSpecificCause()
				.getMessage()
				.contains("fk_quarantine_record_privacy_assessment_source"));

			var forgedSource = assertThrows(DataIntegrityViolationException.class,
					() -> insertQuarantineRecord(jdbc, PROJECT_ID, clipAssetId, SHA256, UUID.randomUUID(), 1));
			assertTrue(forgedSource.getMostSpecificCause()
				.getMessage()
				.contains("fk_quarantine_record_privacy_assessment_source"));

			var wrongSourceVersion = assertThrows(DataIntegrityViolationException.class,
					() -> insertQuarantineRecord(jdbc, PROJECT_ID, sourceClipAssetId, SHA256, sourceAssessmentId, 2));
			assertTrue(wrongSourceVersion.getMostSpecificCause()
				.getMessage()
				.contains("fk_quarantine_record_privacy_assessment_source"));

			assertQuarantineClipAssetForeignKeyUsesProjectIdentity(jdbc);
		}
	}

	private static void migrateTo(DriverManagerDataSource dataSource, String target) {
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load().migrate();
	}

	private static UUID insertClipAsset(JdbcTemplate jdbc, UUID projectId, String sha256) {
		var batchId = UUID.randomUUID();
		var uploadFileId = UUID.randomUUID();
		var clientFileId = UUID.randomUUID();
		var clipAssetId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO upload_batch (
				    batch_id, project_id, idempotency_key, request_fingerprint, device_id,
				    initiated_by_actor_id, file_count, total_size_bytes, manifest_sha256,
				    status, expires_at, created_at, updated_at, verified_manifest_sha256, completed_at)
				VALUES (?, ?, ?, ?, 'synthetic-migration-device', 'synthetic-migration-actor',
				        1, 1234, ?, 'COMPLETED', ?, ?, ?, ?, ?)
				""", batchId, projectId, "migration-batch-" + batchId, sha256, sha256,
				Timestamp.from(CREATED_AT.plusSeconds(3600)), Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT),
				sha256, Timestamp.from(CREATED_AT));
		jdbc.update("""
				INSERT INTO upload_file (
				    upload_file_id, batch_id, client_file_id, idempotency_key, request_fingerprint,
				    file_kind, content_type, size_bytes, sha256, part_size_bytes, total_parts,
				    status, created_at, updated_at, storage_bucket, object_key, storage_upload_id,
				    multipart_created_at, completion_started_at, completion_finished_at,
				    verified_sha256, completed_storage_etag)
				VALUES (?, ?, ?, ?, ?, 'VIDEO', 'video/mp4', 1234, ?, 8388608, 1,
				        'UPLOADED', ?, ?, 'synthetic-bucket', ?, ?, ?, ?, ?, ?, 'synthetic-etag')
				""", uploadFileId, batchId, clientFileId, "migration-file-" + uploadFileId, sha256, sha256,
				Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT), "objects/" + uploadFileId,
				"multipart-" + uploadFileId, Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT),
				Timestamp.from(CREATED_AT), sha256);
		jdbc.update("""
				INSERT INTO clip_asset (
				    clip_asset_id, project_id, batch_id, source_upload_file_id, client_file_id,
				    content_type, size_bytes, sha256, storage_bucket, object_key, storage_etag,
				    asset_status, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'video/mp4', 1234, ?, 'synthetic-bucket', ?,
				        'synthetic-etag', 'INGESTED', ?, ?)
				""", clipAssetId, projectId, batchId, uploadFileId, clientFileId, sha256, "objects/" + clipAssetId,
				Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT));
		return clipAssetId;
	}

	private static void insertPrivacyAssessment(JdbcTemplate jdbc, UUID assessmentId, UUID projectId, UUID clipAssetId,
			String inputDigest, String riskLevel, String requiredAction, String reasonCode) {
		jdbc.update("""
				INSERT INTO privacy_assessment (
				    privacy_assessment_id, project_id, clip_asset_id, input_digest,
				    assessment_version, assessment_method, policy_key, policy_digest,
				    risk_level, required_action, reason_code, assessed_by,
				    supersedes_assessment_id, idempotency_key, request_fingerprint, assessed_at)
				VALUES (?, ?, ?, ?, 1, 'HUMAN_REVIEW', 'privacy-assessment-policy-v1', ?,
				        ?, ?, ?, 'synthetic-migration-admin', NULL, ?, ?, ?)
				""", assessmentId, projectId, clipAssetId, inputDigest, OTHER_SHA256, riskLevel, requiredAction,
				reasonCode, "migration-assessment-" + assessmentId, SHA256, Timestamp.from(CREATED_AT));
	}

	private static void insertQuarantineRecord(JdbcTemplate jdbc, UUID projectId, UUID clipAssetId, String inputDigest,
			UUID sourceId, int sourceVersion) {
		jdbc.update("""
				INSERT INTO quarantine_record (
				    quarantine_record_id, project_id, clip_asset_id, input_digest,
				    reason_code, source_type, source_id, source_version, status,
				    activated_by, activated_at)
				VALUES (?, ?, ?, ?, 'PRIVACY_RISK', 'PRIVACY_ASSESSMENT', ?, ?, 'ACTIVE',
				        'synthetic-migration-admin', ?)
				""", UUID.randomUUID(), projectId, clipAssetId, inputDigest, sourceId, sourceVersion,
				Timestamp.from(CREATED_AT));
	}

	private static void assertQuarantineClipAssetForeignKeyUsesProjectIdentity(JdbcTemplate jdbc) {
		var definition = jdbc.queryForObject("""
				SELECT pg_get_constraintdef(oid)
				FROM pg_constraint
				WHERE conname = 'fk_quarantine_record_clip_asset_project'
				""", String.class);
		assertTrue(definition.contains("FOREIGN KEY (project_id, clip_asset_id)"));
		assertTrue(definition.contains("REFERENCES clip_asset(project_id, clip_asset_id)"));
	}

}
