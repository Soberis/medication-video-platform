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

	@Test
	void migratesFreshDatabaseToV66AndPreservesP0Schema() {
		try (var postgres = new PostgreSQLContainer("postgres:18.4-bookworm")) {
			postgres.start();
			var dataSource = dataSource(postgres);
			var jdbc = new JdbcTemplate(dataSource);

			var flyway = configure(dataSource);
			flyway.migrate();
			flyway.validate();

			assertEquals(1, migrationCount(jdbc, "65"));
			assertEquals(1, migrationCount(jdbc, "66"));
			assertLegacyObjectsAbsent(jdbc);
			assertCurrentQueueContracts(jdbc);
			assertP0ObjectsPresent(jdbc);
		}
	}

	@Test
	void upgradesExistingV64DatabaseToV66WithoutCascade() {
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
			assertLegacyObjectsAbsent(jdbc);
			assertCurrentQueueContracts(jdbc);
			assertP0ObjectsPresent(jdbc);
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
		assertTrue(tableExists(jdbc, "ai_job"));
		assertTrue(tableExists(jdbc, "candidate_annotation"));
		assertTrue(tableExists(jdbc, "quality_review"));
		assertTrue(tableExists(jdbc, "annotation_revision"));
		assertTrue(tableExists(jdbc, "cleaning_run"));
		assertTrue(tableExists(jdbc, "cleaning_result"));
		assertTrue(tableExists(jdbc, "training_data_eligibility"));
		assertTrue(tableExists(jdbc, "training_data_candidate_observations"));
		assertTrue(tableExists(jdbc, "inbox_message"));
		assertTrue(tableExists(jdbc, "outbox_event"));
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
