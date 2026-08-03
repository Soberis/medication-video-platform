package com.medicationvideo.platform.datasetexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.medicationvideo.platform.integration.DatasetExportMessagingTopology;
import com.medicationvideo.platform.support.ProjectMembershipTestData;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DatasetExportManualReplayContractTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final String ADMIN_TOKEN = "synthetic-test-admin-bearer-token-do-not-reuse";

	private static final String MEMBER_TOKEN = "synthetic-test-bearer-token-do-not-reuse";

	private static final String ADMIN_ACTOR = ProjectMembershipTestData.ADMIN_ACTOR;

	private static final String APPROVER_ACTOR = "synthetic.export.approver";

	private static final String BODY_SHA256 = "d".repeat(64);

	private static final DatasetExportDeadLetterReason DEAD_LETTER_REASON = DatasetExportDeadLetterReason.DATASET_EXPORT_JOB_ACQUIRE_REJECTED;

	private static final DatasetExportManualReplayReason OPERATOR_REASON = DatasetExportManualReplayReason.CONFIGURATION_FIXED;

	private static final String CHANGE_REFERENCE = "change-ref-0001";

	private static final Instant SEED_TIME = Instant.parse("2026-08-03T07:00:00Z");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private UUID datasetVersionId;

	private UUID splitPlanId;

	private UUID specificationId;

	private UUID snapshotId;

	private UUID validationId;

	private UUID freezeApprovalRequestId;

	private UUID freezeApprovalDecisionId;

	private UUID exportApprovalRequestId;

	private UUID exportApprovalDecisionId;

	private UUID datasetExportId;

	private UUID firstAttemptId;

	private UUID firstMessageId;

	private UUID firstOutboxId;

	private UUID deadLetterId;

	private String firstLeaseToken;

	@BeforeEach
	void setUp() {
		this.datasetVersionId = UUID.randomUUID();
		this.splitPlanId = UUID.randomUUID();
		this.specificationId = UUID.randomUUID();
		this.snapshotId = UUID.randomUUID();
		this.validationId = UUID.randomUUID();
		this.freezeApprovalRequestId = UUID.randomUUID();
		this.freezeApprovalDecisionId = UUID.randomUUID();
		this.exportApprovalRequestId = UUID.randomUUID();
		this.exportApprovalDecisionId = UUID.randomUUID();
		this.datasetExportId = UUID.randomUUID();
		this.firstAttemptId = UUID.randomUUID();
		this.firstMessageId = UUID.randomUUID();
		this.firstOutboxId = UUID.randomUUID();
		this.firstLeaseToken = "old-export-lease-token-contract-0001";
		this.deadLetterId = DatasetExportDeadLetterIdentity.from(DEAD_LETTER_REASON, BODY_SHA256);
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, PROJECT_ID);
		seedQueuedExport();
	}

	@AfterEach
	void tearDown() {
		clearSeed();
	}

	@Test
	void administratorReplaysQueuedExportAndPersistsOneIdempotentAuditedAttempt() throws Exception {
		var request = replayRequest();
		var idempotencyKey = "dataset-export-replay-contract-0001";

		this.mockMvc
			.perform(post("/api/v1/dataset-exports/{datasetExportId}/replays", this.datasetExportId)
				.header("Authorization", "Bearer " + MEMBER_TOKEN)
				.header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error_code").value("DATASET_EXPORT_REPLAY_ACCESS_DENIED"));

		var firstResponse = this.mockMvc
			.perform(post("/api/v1/dataset-exports/{datasetExportId}/replays", this.datasetExportId)
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", "dataset-export-replay-contract-request-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
			.andExpect(status().isAccepted())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.dataset_export_id").value(this.datasetExportId.toString()))
			.andExpect(jsonPath("$.dead_letter_id").value(this.deadLetterId.toString()))
			.andExpect(jsonPath("$.source_attempt").value(1))
			.andExpect(jsonPath("$.new_attempt").value(2))
			.andExpect(jsonPath("$.resulting_export_status").value("QUEUED"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		var replayId = JsonPath.<String>read(firstResponse, "$.replay_id");

		assertEquals("FAILED", scalar("SELECT status FROM dataset_export_attempt WHERE dataset_export_attempt_id = ?",
				this.firstAttemptId));
		assertEquals(DEAD_LETTER_REASON.name(),
				scalar("SELECT error_code FROM dataset_export_attempt WHERE dataset_export_attempt_id = ?",
						this.firstAttemptId));
		assertEquals("NON_RETRYABLE_POLICY",
				scalar("SELECT error_class FROM dataset_export_attempt WHERE dataset_export_attempt_id = ?",
						this.firstAttemptId));
		assertEquals("QUEUED",
				scalar("SELECT status FROM dataset_export WHERE dataset_export_id = ?", this.datasetExportId));
		assertEquals(2,
				count("SELECT count(*) FROM dataset_export_attempt WHERE dataset_export_id = ?", this.datasetExportId));
		assertEquals(1, count("SELECT count(*) FROM dataset_export_manual_replay WHERE dataset_export_id = ?",
				this.datasetExportId));
		assertEquals(2, count("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND schema_version = ?",
				this.datasetExportId, DatasetExportJobEnvelope.SCHEMA_VERSION));

		var newAttemptId = uuidScalar("""
				SELECT dataset_export_attempt_id
				FROM dataset_export_attempt
				WHERE dataset_export_id = ? AND attempt = 2
				""", this.datasetExportId);
		var newMessageId = uuidScalar("""
				SELECT message_id
				FROM dataset_export_attempt
				WHERE dataset_export_attempt_id = ?
				""", newAttemptId);
		var newLeaseHash = scalar(
				"SELECT lease_token_hash FROM dataset_export_attempt WHERE dataset_export_attempt_id = ?",
				newAttemptId);
		var newLeaseToken = scalar("""
				SELECT payload_json ->> 'lease_token'
				FROM outbox_event
				WHERE aggregate_id = ? AND payload_json ->> 'message_id' = ?
				""", this.datasetExportId, newMessageId.toString());
		assertNotEquals(this.firstMessageId, newMessageId);
		assertNotEquals(sha256(this.firstLeaseToken), newLeaseHash);
		assertNotEquals(this.firstLeaseToken, newLeaseToken);
		assertEquals(sha256(newLeaseToken), newLeaseHash);

		var replayRow = this.jdbcTemplate.queryForMap("""
				SELECT dead_letter_reason, body_sha256, operator_actor_id, operator_reason_code,
				       change_reference, source_attempt, new_attempt, source_message_id, idempotency_key
				FROM dataset_export_manual_replay
				WHERE replay_id = ?
				""", UUID.fromString(replayId));
		assertEquals(DEAD_LETTER_REASON.name(), replayRow.get("dead_letter_reason"));
		assertEquals(BODY_SHA256, replayRow.get("body_sha256"));
		assertEquals(ADMIN_ACTOR, replayRow.get("operator_actor_id"));
		assertEquals(OPERATOR_REASON.name(), replayRow.get("operator_reason_code"));
		assertEquals(CHANGE_REFERENCE, replayRow.get("change_reference"));
		assertEquals(1, replayRow.get("source_attempt"));
		assertEquals(2, replayRow.get("new_attempt"));
		assertEquals(this.firstMessageId, replayRow.get("source_message_id"));
		assertEquals(idempotencyKey, replayRow.get("idempotency_key"));

		var auditEventId = uuidScalar("""
				SELECT event_id
				FROM audit_event
				WHERE action = 'DATASET_EXPORT_MANUAL_REPLAY' AND object_id = ?
				ORDER BY occurred_at_utc DESC, event_id DESC
				LIMIT 1
				""", this.datasetExportId.toString());
		Map<String, Object> auditRow = this.jdbcTemplate.queryForMap("""
				SELECT actor_id, request_id, reason_code,
				       details_json ->> 'dead_letter_reason' AS dead_letter_reason,
				       details_json ->> 'change_reference' AS change_reference
				FROM audit_event
				WHERE event_id = ?
				""", auditEventId);
		assertEquals(ADMIN_ACTOR, auditRow.get("actor_id"));
		assertEquals("dataset-export-replay-contract-request-0001", auditRow.get("request_id"));
		assertEquals(OPERATOR_REASON.name(), auditRow.get("reason_code"));
		assertEquals(DEAD_LETTER_REASON.name(), auditRow.get("dead_letter_reason"));
		assertEquals(CHANGE_REFERENCE, auditRow.get("change_reference"));

		assertThrows(DataAccessException.class, () -> this.jdbcTemplate
			.update("UPDATE audit_event SET action = 'TAMPERED' WHERE event_id = ?", auditEventId));
		assertThrows(DataAccessException.class,
				() -> this.jdbcTemplate.update("DELETE FROM audit_event WHERE event_id = ?", auditEventId));

		this.mockMvc
			.perform(post("/api/v1/dataset-exports/{datasetExportId}/replays", this.datasetExportId)
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", "dataset-export-replay-contract-request-0002")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
			.andExpect(status().isAccepted())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.replay_id").value(replayId))
			.andExpect(jsonPath("$.new_attempt").value(2));

		assertEquals(1, count("SELECT count(*) FROM dataset_export_manual_replay WHERE dataset_export_id = ?",
				this.datasetExportId));
		assertEquals(2,
				count("SELECT count(*) FROM dataset_export_attempt WHERE dataset_export_id = ?", this.datasetExportId));
		assertEquals(2, count("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND schema_version = ?",
				this.datasetExportId, DatasetExportJobEnvelope.SCHEMA_VERSION));
		assertEquals(1, count("""
				SELECT count(*)
				FROM audit_event
				WHERE action = 'DATASET_EXPORT_MANUAL_REPLAY' AND object_id = ?
				""", this.datasetExportId.toString()));
	}

	private void seedQueuedExport() {
		var timestamp = Timestamp.from(SEED_TIME);
		var snapshotDigest = "c".repeat(64);
		var exportRequestFingerprint = "e".repeat(64);
		this.jdbcTemplate.update("""
				INSERT INTO dataset_version (
				    dataset_version_id, project_id, display_name, dataset_version_status,
				    created_by, idempotency_key, request_fingerprint, created_at,
				    build_started_by, build_start_idempotency_key, build_start_request_fingerprint,
				    build_started_at, row_version)
				VALUES (?, ?, 'Synthetic replay dataset', 'BUILDING', ?, ?, ?, ?, ?, ?, ?, ?, 0)
				""", this.datasetVersionId, PROJECT_ID, ADMIN_ACTOR, "dataset-version-replay-0001", "a".repeat(64),
				timestamp, ADMIN_ACTOR, "dataset-build-replay-0001", "b".repeat(64), timestamp);
		this.jdbcTemplate.update("""
				INSERT INTO dataset_split_plan (
				    dataset_split_plan_id, dataset_version_id, project_id, policy_version, split_seed,
				    holdout_count, created_by, idempotency_key, request_fingerprint, created_at)
				VALUES (?, ?, ?, 'CAPTURE_SESSION_HASH_RANK_V1', 20260803, 1, ?, ?, ?, ?)
				""", this.splitPlanId, this.datasetVersionId, PROJECT_ID, ADMIN_ACTOR, "dataset-split-replay-0001",
				"b".repeat(64), timestamp);
		this.jdbcTemplate.update("""
				INSERT INTO dataset_build_specification (
				    dataset_build_specification_id, dataset_version_id, project_id,
				    selection_rule_version, label_schema_digest, cleaning_rule_set_digest,
				    dataset_split_plan_id, export_profile_key, export_profile_digest,
				    created_by, idempotency_key, request_fingerprint, created_at)
				VALUES (?, ?, ?, 'EXPLICIT_CAPTURE_SESSION_SNAPSHOT_V1', ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", this.specificationId, this.datasetVersionId, PROJECT_ID, "f".repeat(64), "0".repeat(64),
				this.splitPlanId, DatasetExportProfile.CURRENT_KEY, DatasetExportProfile.CURRENT_DIGEST, ADMIN_ACTOR,
				"dataset-spec-replay-0001", "1".repeat(64), timestamp);
		this.jdbcTemplate.update("""
				INSERT INTO dataset_build_snapshot (
				    dataset_build_snapshot_id, dataset_version_id, project_id,
				    dataset_build_specification_id, dataset_split_plan_id, snapshot_schema_version,
				    input_variant_policy, member_count, stream_count, snapshot_digest,
				    created_by, idempotency_key, request_fingerprint, created_at)
				VALUES (?, ?, ?, ?, ?, 'DATASET_RELATIONAL_SNAPSHOT_V1', 'RAW_CLIP_ASSET_V1',
				        1, 1, ?, ?, ?, ?, ?)
				""", this.snapshotId, this.datasetVersionId, PROJECT_ID, this.specificationId, this.splitPlanId,
				snapshotDigest, ADMIN_ACTOR, "dataset-snapshot-replay-0001", "2".repeat(64), timestamp);
		this.jdbcTemplate.update(
				"""
						INSERT INTO dataset_version_validation (
						    dataset_version_validation_id, dataset_version_id, project_id,
						    dataset_build_snapshot_id, validation_profile_version, outcome,
						    reason_codes, executed_checks, validated_snapshot_digest, validated_by,
						    idempotency_key, request_fingerprint, validated_at)
						VALUES (?, ?, ?, ?, 'DATASET_FREEZE_VALIDATION_V1', 'PASS',
						        CAST('[]' AS JSONB), CAST('["SNAPSHOT_DIGEST","FROZEN_REFERENCES","SPLIT_ISOLATION","BUILD_CONTRACT","EXPORT_PROFILE"]' AS JSONB),
						        ?, ?, ?, ?, ?)
						""",
				this.validationId, this.datasetVersionId, PROJECT_ID, this.snapshotId, snapshotDigest, ADMIN_ACTOR,
				"dataset-validation-replay-0001", "3".repeat(64), timestamp);
		this.jdbcTemplate.update("""
				INSERT INTO approval_request (
				    approval_request_id, project_id, request_type, object_type, object_id,
				    object_version, requested_by, requested_at, status)
				VALUES (?, ?, 'DATASET_VERSION_FREEZE', 'DatasetVersion', ?, ?, ?, ?, 'APPROVED')
				""", this.freezeApprovalRequestId, PROJECT_ID, this.datasetVersionId, this.validationId, ADMIN_ACTOR,
				timestamp);
		this.jdbcTemplate.update("""
				INSERT INTO approval_decision (
				    approval_decision_id, project_id, approval_request_id, decision,
				    decided_by, decided_at)
				VALUES (?, ?, ?, 'APPROVED', ?, ?)
				""", this.freezeApprovalDecisionId, PROJECT_ID, this.freezeApprovalRequestId, APPROVER_ACTOR,
				timestamp);
		this.jdbcTemplate.update("""
				UPDATE dataset_version
				SET dataset_version_status = 'FROZEN', build_snapshot_id = ?,
				    freeze_approval_request_id = ?, freeze_approval_decision_id = ?,
				    frozen_by = ?, freeze_idempotency_key = ?, freeze_request_fingerprint = ?,
				    frozen_at = ?, row_version = row_version + 1
				WHERE dataset_version_id = ?
				""", this.snapshotId, this.freezeApprovalRequestId, this.freezeApprovalDecisionId, APPROVER_ACTOR,
				"dataset-freeze-replay-0001", "4".repeat(64), timestamp, this.datasetVersionId);
		this.jdbcTemplate.update("""
				INSERT INTO approval_request (
				    approval_request_id, project_id, request_type, object_type, object_id,
				    object_version, requested_by, requested_at, status)
				VALUES (?, ?, 'DATASET_EXPORT', 'DatasetVersion', ?, ?, ?, ?, 'APPROVED')
				""", this.exportApprovalRequestId, PROJECT_ID, this.datasetVersionId, this.validationId, ADMIN_ACTOR,
				timestamp);
		this.jdbcTemplate.update("""
				INSERT INTO approval_decision (
				    approval_decision_id, project_id, approval_request_id, decision,
				    decided_by, decided_at)
				VALUES (?, ?, ?, 'APPROVED', ?, ?)
				""", this.exportApprovalDecisionId, PROJECT_ID, this.exportApprovalRequestId, APPROVER_ACTOR,
				timestamp);
		this.jdbcTemplate.update("""
				INSERT INTO dataset_export (
				    dataset_export_id, project_id, dataset_version_id, dataset_build_specification_id,
				    dataset_build_snapshot_id, dataset_version_validation_id, approval_request_id,
				    export_profile_key, export_profile_digest, snapshot_digest, purpose, expires_at,
				    status, requested_by, idempotency_key, request_fingerprint, requested_at,
				    approval_decision_id, approved_by, approval_idempotency_key,
				    approval_request_fingerprint, approved_at, row_version)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'MODEL_TRAINING', ?, 'QUEUED', ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
				""", this.datasetExportId, PROJECT_ID, this.datasetVersionId, this.specificationId, this.snapshotId,
				this.validationId, this.exportApprovalRequestId, DatasetExportProfile.CURRENT_KEY,
				DatasetExportProfile.CURRENT_DIGEST, snapshotDigest, Timestamp.from(SEED_TIME.plusSeconds(3600)),
				ADMIN_ACTOR, "dataset-export-request-replay-0001", exportRequestFingerprint, timestamp,
				this.exportApprovalDecisionId, APPROVER_ACTOR, "dataset-export-approval-replay-0001", "5".repeat(64),
				timestamp);
		this.jdbcTemplate.update("""
				INSERT INTO dataset_export_attempt (
				    dataset_export_attempt_id, dataset_export_id, attempt, message_id, status,
				    lease_token_hash, created_at, updated_at, row_version)
				VALUES (?, ?, 1, ?, 'QUEUED', ?, ?, ?, 0)
				""", this.firstAttemptId, this.datasetExportId, this.firstMessageId, sha256(this.firstLeaseToken),
				timestamp, timestamp);
		this.jdbcTemplate.update("""
				INSERT INTO outbox_event (
				    outbox_event_id, aggregate_type, aggregate_id, event_type, schema_version,
				    exchange_name, routing_key, payload_json, occurred_at, available_at)
				VALUES (?, 'DatasetExport', ?, 'DatasetExportRequestedV1', ?, ?, ?, CAST(? AS JSONB), ?, ?)
				""", this.firstOutboxId, this.datasetExportId, DatasetExportJobEnvelope.SCHEMA_VERSION,
				DatasetExportMessagingTopology.EXCHANGE, DatasetExportMessagingTopology.ROUTING_KEY, oldEnvelopeJson(),
				timestamp, timestamp);
	}

	private String replayRequest() {
		return """
				{"dead_letter_id":"%s","source_message_id":"%s","source_attempt":1,
				"dead_letter_reason":"%s","body_sha256":"%s","operator_reason_code":"%s",
				"change_reference":"%s"}
				""".formatted(this.deadLetterId, this.firstMessageId, DEAD_LETTER_REASON.name(), BODY_SHA256,
				OPERATOR_REASON.name(), CHANGE_REFERENCE);
	}

	private String oldEnvelopeJson() {
		return """
				{"schema_version":"%s","message_id":"%s","export_id":"%s","job_type":"DATASET_EXPORT",
				"attempt":1,"lease_token":"%s","project_id":"%s","dataset_version_id":"%s",
				"snapshot_digest":"%s","export_profile_key":"%s","export_profile_digest":"%s",
				"requested_at":"%s"}
				""".formatted(DatasetExportJobEnvelope.SCHEMA_VERSION, this.firstMessageId, this.datasetExportId,
				firstLeaseToken, PROJECT_ID, this.datasetVersionId, "c".repeat(64), DatasetExportProfile.CURRENT_KEY,
				DatasetExportProfile.CURRENT_DIGEST, SEED_TIME);
	}

	private void clearSeed() {
		this.jdbcTemplate.update("DELETE FROM dataset_export_manual_replay WHERE dataset_export_id = ?",
				this.datasetExportId);
		this.jdbcTemplate.update("DELETE FROM outbox_event WHERE aggregate_id = ?", this.datasetExportId);
		this.jdbcTemplate.update("DELETE FROM dataset_export_attempt WHERE dataset_export_id = ?",
				this.datasetExportId);
		this.jdbcTemplate.update("DELETE FROM dataset_export WHERE dataset_export_id = ?", this.datasetExportId);
		this.jdbcTemplate.update("DELETE FROM approval_decision WHERE approval_request_id = ?",
				this.exportApprovalRequestId);
		this.jdbcTemplate.update("DELETE FROM approval_request WHERE approval_request_id = ?",
				this.exportApprovalRequestId);
		this.jdbcTemplate.update("""
				UPDATE dataset_version
				SET dataset_version_status = 'BUILDING', build_snapshot_id = NULL,
				    freeze_approval_request_id = NULL, freeze_approval_decision_id = NULL,
				    frozen_by = NULL, freeze_idempotency_key = NULL,
				    freeze_request_fingerprint = NULL, frozen_at = NULL,
				    row_version = row_version + 1
				WHERE dataset_version_id = ?
				""", this.datasetVersionId);
		this.jdbcTemplate.update("DELETE FROM approval_decision WHERE approval_request_id = ?",
				this.freezeApprovalRequestId);
		this.jdbcTemplate.update("DELETE FROM approval_request WHERE approval_request_id = ?",
				this.freezeApprovalRequestId);
		this.jdbcTemplate.update("DELETE FROM dataset_version_validation WHERE dataset_version_validation_id = ?",
				this.validationId);
		this.jdbcTemplate.update("DELETE FROM dataset_build_snapshot WHERE dataset_build_snapshot_id = ?",
				this.snapshotId);
		this.jdbcTemplate.update("DELETE FROM dataset_build_specification WHERE dataset_build_specification_id = ?",
				this.specificationId);
		this.jdbcTemplate.update("DELETE FROM dataset_split_plan WHERE dataset_split_plan_id = ?", this.splitPlanId);
		this.jdbcTemplate.update("DELETE FROM dataset_version WHERE dataset_version_id = ?", this.datasetVersionId);
	}

	private int count(String sql, Object... arguments) {
		return this.jdbcTemplate.queryForObject(sql, Integer.class, arguments);
	}

	private String scalar(String sql, Object... arguments) {
		return this.jdbcTemplate.queryForObject(sql, String.class, arguments);
	}

	private UUID uuidScalar(String sql, Object... arguments) {
		return this.jdbcTemplate.queryForObject(sql, UUID.class, arguments);
	}

	private static String sha256(String value) {
		try {
			return java.util.HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

}
