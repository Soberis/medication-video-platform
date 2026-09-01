package com.medicationvideo.platform.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.medicationvideo.platform.support.ProjectMembershipTestData;

class LegacyDatabaseReductionMigrationTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000165");

	private static final String ADMIN_ACTOR = "synthetic-migration-admin";

	private static final String SHA256 = "a".repeat(64);

	private static final String LABEL_SCHEMA_ID = "10000000-0000-0000-0000-000000000001";

	private static final String LABEL_SCHEMA_DIGEST = "305e92dcd10343e6c97b5780e59485b63183fadad7e9b87bc85db4bb037aa203";

	@Test
	void migratesFreshDatabaseToV72AndPreservesP0Schema() {
		try (var postgres = new PostgreSQLContainer("postgres:18.4-bookworm")) {
			postgres.start();
			var dataSource = dataSource(postgres);
			var jdbc = new JdbcTemplate(dataSource);

			var flyway = configure(dataSource);
			flyway.migrate();
			flyway.validate();

			assertEquals(1, migrationCount(jdbc, "65"));
			assertEquals(1, migrationCount(jdbc, "66"));
			assertEquals(1, migrationCount(jdbc, "72"));
			assertLegacyObjectsAbsent(jdbc);
			assertCurrentQueueContracts(jdbc);
			assertP0ObjectsPresent(jdbc);
		}
	}

	@Test
	void upgradesExistingV64DatabaseToV72WithoutCascade() {
		try (var postgres = new PostgreSQLContainer("postgres:18.4-bookworm")) {
			postgres.start();
			var dataSource = dataSource(postgres);
			var jdbc = new JdbcTemplate(dataSource);

			var v64 = configure(dataSource, "64");
			v64.migrate();
			v64.validate();
			assertEquals(1, migrationCount(jdbc, "64"));
			assertLegacyRowsAreEmpty(jdbc);

			var v66 = configure(dataSource);
			v66.migrate();
			v66.validate();

			assertEquals(1, migrationCount(jdbc, "65"));
			assertEquals(1, migrationCount(jdbc, "66"));
			assertEquals(1, migrationCount(jdbc, "72"));
			assertLegacyObjectsAbsent(jdbc);
			assertCurrentQueueContracts(jdbc);
			assertP0ObjectsPresent(jdbc);
		}
	}

	@Test
	void refusesToRetireSingleVideoPersistenceWhenHistoricalRowsExist() {
		try (var postgres = new PostgreSQLContainer("postgres:18.4-bookworm")) {
			postgres.start();
			var dataSource = dataSource(postgres);
			var jdbc = new JdbcTemplate(dataSource);

			configure(dataSource, "64").migrate();
			ProjectMembershipTestData.ensureProject(jdbc, PROJECT_ID);
			var clipAssetId = insertLegacyClipAsset(jdbc);
			var taskId = UUID.randomUUID();
			var now = Timestamp.from(Instant.parse("2026-07-25T02:00:00Z"));
			jdbc.update("""
					INSERT INTO annotation_task (
					    annotation_task_id, project_id, clip_asset_id, label_schema_id,
					    label_schema_digest, assignee_actor_id, created_by, idempotency_key,
					    task_status, created_at, updated_at, version)
					VALUES (?, ?, ?, ?::uuid, ?, ?, ?, ?, 'ASSIGNED', ?, ?, 0)
					""", taskId, PROJECT_ID, clipAssetId, LABEL_SCHEMA_ID, LABEL_SCHEMA_DIGEST, ADMIN_ACTOR,
					ADMIN_ACTOR, "legacy-task-" + taskId, now, now);

			assertThrows(FlywayException.class, () -> configure(dataSource).migrate());
			assertEquals(0, migrationCount(jdbc, "72"));
			assertEquals(1, jdbc.queryForObject(
					"SELECT count(*) FROM annotation_task WHERE annotation_task_id = ? AND capture_group_id IS NULL",
					Integer.class, taskId));
			assertTrue(columnExists(jdbc, "annotation_task", "clip_asset_id"));
			assertTrue(tableExists(jdbc, "cleaning_run"));
			assertTrue(tableExists(jdbc, "training_data_eligibility"));
		}
	}

	@Test
	void refusesToRetireLegacyObjectsWhenHistoricalRowsExist() {
		try (var postgres = new PostgreSQLContainer("postgres:18.4-bookworm")) {
			postgres.start();
			var dataSource = dataSource(postgres);
			var jdbc = new JdbcTemplate(dataSource);

			configure(dataSource, "64").migrate();
			ProjectMembershipTestData.ensureProject(jdbc, PROJECT_ID);
			var now = Timestamp.from(Instant.now());
			jdbc.update("""
					INSERT INTO asset_folder (
					    folder_id, project_id, display_name, created_by, created_at,
					    updated_by, updated_at)
					VALUES (?, ?, 'legacy-folder', ?, ?, ?, ?)
					""", UUID.randomUUID(), PROJECT_ID, ADMIN_ACTOR, now, ADMIN_ACTOR, now);

			assertThrows(FlywayException.class, () -> configure(dataSource).migrate());
			assertEquals(0, migrationCount(jdbc, "65"));
			assertEquals(0, migrationCount(jdbc, "66"));
			assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM asset_folder", Integer.class));
			assertTrue(tableExists(jdbc, "asset_folder"));
		}
	}

	@Test
	void refusesToRetireDatasetObjectsWhenHistoricalRowsExist() {
		try (var postgres = new PostgreSQLContainer("postgres:18.4-bookworm")) {
			postgres.start();
			var dataSource = dataSource(postgres);
			var jdbc = new JdbcTemplate(dataSource);

			configure(dataSource, "65").migrate();
			ProjectMembershipTestData.ensureProject(jdbc, PROJECT_ID);
			var now = Timestamp.from(Instant.now());
			var datasetVersionId = UUID.randomUUID();
			jdbc.update("""
					INSERT INTO dataset_version (
					    dataset_version_id, project_id, display_name, dataset_version_status,
					    created_by, idempotency_key, request_fingerprint, created_at)
					VALUES (?, ?, 'legacy-dataset', 'DRAFT', ?, ?, ?, ?)
					""", datasetVersionId, PROJECT_ID, ADMIN_ACTOR, "legacy-dataset-001", "a".repeat(64), now);

			assertThrows(FlywayException.class, () -> configure(dataSource).migrate());
			assertEquals(0, migrationCount(jdbc, "66"));
			assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM dataset_version WHERE dataset_version_id = ?",
					Integer.class, datasetVersionId));
			assertTrue(tableExists(jdbc, "dataset_version"));
		}
	}

	private static DriverManagerDataSource dataSource(PostgreSQLContainer postgres) {
		return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

	private static Flyway configure(DataSource dataSource) {
		return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
	}

	private static Flyway configure(DataSource dataSource, String target) {
		return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load();
	}

	private static int migrationCount(JdbcTemplate jdbc, String version) {
		return jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = ? AND success = true",
				Integer.class, version);
	}

	private static void assertLegacyRowsAreEmpty(JdbcTemplate jdbc) {
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM asset_folder", Integer.class));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM capture_event_log", Integer.class));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ai_evaluation", Integer.class));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ai_evaluation_metric", Integer.class));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM asset_classification_command", Integer.class));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM dataset_version", Integer.class));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM dataset_member_session", Integer.class));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM dataset_member_stream", Integer.class));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM dataset_split_plan", Integer.class));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM dataset_split_assignment", Integer.class));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM dataset_build_specification", Integer.class));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM dataset_build_snapshot", Integer.class));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM dataset_version_validation", Integer.class));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM approval_request", Integer.class));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM approval_decision", Integer.class));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM dataset_export", Integer.class));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM dataset_export_attempt", Integer.class));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM export_artifact", Integer.class));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM dataset_export_manual_replay", Integer.class));
	}

	private static void assertLegacyObjectsAbsent(JdbcTemplate jdbc) {
		assertFalse(tableExists(jdbc, "asset_folder"));
		assertFalse(columnExists(jdbc, "upload_batch", "asset_folder_id"));
		assertFalse(columnExists(jdbc, "clip_asset", "folder_id"));
		assertFalse(tableExists(jdbc, "capture_event_log"));
		assertFalse(tableExists(jdbc, "ai_evaluation"));
		assertFalse(tableExists(jdbc, "ai_evaluation_metric"));
		assertFalse(tableExists(jdbc, "asset_classification_command"));
		assertFalse(tableExists(jdbc, "dataset_version"));
		assertFalse(tableExists(jdbc, "dataset_member_session"));
		assertFalse(tableExists(jdbc, "dataset_member_stream"));
		assertFalse(tableExists(jdbc, "dataset_split_plan"));
		assertFalse(tableExists(jdbc, "dataset_split_assignment"));
		assertFalse(tableExists(jdbc, "dataset_build_specification"));
		assertFalse(tableExists(jdbc, "dataset_build_snapshot"));
		assertFalse(tableExists(jdbc, "dataset_version_validation"));
		assertFalse(tableExists(jdbc, "approval_request"));
		assertFalse(tableExists(jdbc, "approval_decision"));
		assertFalse(tableExists(jdbc, "dataset_export"));
		assertFalse(tableExists(jdbc, "dataset_export_attempt"));
		assertFalse(tableExists(jdbc, "export_artifact"));
		assertFalse(tableExists(jdbc, "dataset_export_manual_replay"));
		assertFalse(tableExists(jdbc, "cleaning_run"));
		assertFalse(tableExists(jdbc, "cleaning_result"));
		assertFalse(tableExists(jdbc, "training_data_eligibility"));
		assertFalse(tableExists(jdbc, "training_data_candidate_observations"));
		assertFalse(columnExists(jdbc, "annotation_task", "clip_asset_id"));
	}

	private static void assertCurrentQueueContracts(JdbcTemplate jdbc) {
		var inboxConstraint = jdbc.queryForObject("""
				SELECT pg_get_constraintdef(oid)
				FROM pg_constraint
				WHERE conrelid = 'public.inbox_message'::regclass
				  AND conname = 'ck_inbox_message_contract'
				""", String.class);
		assertTrue(inboxConstraint.contains("capture-sync-result-v1"));
		assertFalse(inboxConstraint.contains("dataset-export-result-v1"));

		var outboxConstraint = jdbc.queryForObject("""
				SELECT pg_get_constraintdef(oid)
				FROM pg_constraint
				WHERE conrelid = 'public.outbox_event'::regclass
				  AND conname = 'ck_outbox_capability_redacted'
				""", String.class);
		assertTrue(outboxConstraint.contains("media-probe-job.v1"));
		assertFalse(outboxConstraint.contains("dataset-export-job.v1"));
	}

	private static void assertP0ObjectsPresent(JdbcTemplate jdbc) {
		assertTrue(tableExists(jdbc, "clip_asset"));
		assertTrue(tableExists(jdbc, "capture_session"));
		assertTrue(tableExists(jdbc, "camera_set_snapshot"));
		assertTrue(tableExists(jdbc, "camera_stream"));
		assertTrue(tableExists(jdbc, "ai_job"));
		assertTrue(tableExists(jdbc, "ai_job_attempt"));
		assertTrue(tableExists(jdbc, "candidate_annotation"));
		assertTrue(tableExists(jdbc, "quality_review"));
		assertTrue(tableExists(jdbc, "annotation_revision"));
		assertTrue(tableExists(jdbc, "capture_group_quality_review"));
		assertTrue(tableExists(jdbc, "annotation_task"));
		assertTrue(tableExists(jdbc, "annotation_review"));
		assertTrue(tableExists(jdbc, "capture_group_cleaning"));
		assertTrue(tableExists(jdbc, "training_data_group_eligibility_v3"));
		assertTrue(columnExists(jdbc, "annotation_revision", "clip_asset_id"));
		assertTrue(tableExists(jdbc, "inbox_message"));
		assertTrue(tableExists(jdbc, "outbox_event"));
	}

	private static UUID insertLegacyClipAsset(JdbcTemplate jdbc) {
		var batchId = UUID.randomUUID();
		var uploadFileId = UUID.randomUUID();
		var clientFileId = UUID.randomUUID();
		var clipAssetId = UUID.randomUUID();
		var now = Timestamp.from(Instant.parse("2026-07-25T02:00:00Z"));

		jdbc.update("""
				INSERT INTO upload_batch (
				    batch_id, project_id, idempotency_key, request_fingerprint, device_id,
				    initiated_by_actor_id, file_count, total_size_bytes, manifest_sha256,
				    status, expires_at, created_at, updated_at, verified_manifest_sha256, completed_at)
				VALUES (?, ?, ?, ?, 'synthetic-migration-device', ?, 1, 1234, ?,
				        'COMPLETED', ?, ?, ?, ?, ?)
				""", batchId, PROJECT_ID, "legacy-batch-" + batchId, SHA256, ADMIN_ACTOR, SHA256,
				Timestamp.from(now.toInstant().plusSeconds(3600)), now, now, SHA256, now);
		jdbc.update("""
				INSERT INTO upload_file (
				    upload_file_id, batch_id, client_file_id, idempotency_key, request_fingerprint,
				    file_kind, content_type, size_bytes, sha256, part_size_bytes, total_parts,
				    status, created_at, updated_at, storage_bucket, object_key, storage_upload_id,
				    multipart_created_at, completion_started_at, completion_finished_at,
				    verified_sha256, completed_storage_etag)
				VALUES (?, ?, ?, ?, ?, 'VIDEO', 'video/mp4', 1234, ?, 8388608, 1,
				        'UPLOADED', ?, ?, 'synthetic-bucket', ?, ?, ?, ?, ?, ?, 'synthetic-etag')
				""", uploadFileId, batchId, clientFileId, "legacy-file-" + uploadFileId, SHA256, SHA256, now, now,
				"objects/" + uploadFileId, "multipart-" + uploadFileId, now, now, now, SHA256);
		jdbc.update("""
				INSERT INTO clip_asset (
				    clip_asset_id, project_id, batch_id, source_upload_file_id, client_file_id,
				    content_type, size_bytes, sha256, storage_bucket, object_key, storage_etag,
				    asset_status, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, ?, 'video/mp4', 1234, ?, 'synthetic-bucket', ?,
				        'synthetic-etag', 'INGESTED', ?, ?, 0)
				""", clipAssetId, PROJECT_ID, batchId, uploadFileId, clientFileId, SHA256, "objects/" + clipAssetId,
				now, now);
		return clipAssetId;
	}

	private static boolean tableExists(JdbcTemplate jdbc, String relationName) {
		return Boolean.TRUE
			.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, "public." + relationName));
	}

	private static boolean columnExists(JdbcTemplate jdbc, String tableName, String columnName) {
		return Boolean.TRUE.equals(jdbc.queryForObject("""
				SELECT EXISTS (
				    SELECT 1
				    FROM information_schema.columns
				    WHERE table_schema = 'public'
				      AND table_name = ?
				      AND column_name = ?)
				""", Boolean.class, tableName, columnName));
	}

}
