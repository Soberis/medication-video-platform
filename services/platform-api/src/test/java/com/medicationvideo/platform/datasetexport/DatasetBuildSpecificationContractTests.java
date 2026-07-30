package com.medicationvideo.platform.datasetexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.medicationvideo.platform.support.ProjectMembershipTestData;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DatasetBuildSpecificationContractTests {

	private static final UUID PROJECT_ID = new UUID(0, 701);
	private static final UUID DATASET_VERSION_ID = new UUID(0, 702);
	private static final UUID SPLIT_PLAN_ID = new UUID(0, 703);
	private static final String ADMIN_TOKEN = "synthetic-test-admin-bearer-token-do-not-reuse";
	private static final String LABEL_DIGEST = "c".repeat(64);
	private static final String CLEANING_DIGEST = "d".repeat(64);
	private static final Instant CREATED_AT = Instant.parse("2026-07-29T01:00:00Z");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		clearState();
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, PROJECT_ID);
		insertDraftWithSplit();
	}

	@AfterEach
	void tearDown() {
		clearState();
	}

	@Test
	void administratorFixesAndReadsOneIdempotentBuildSpecification() throws Exception {
		this.mockMvc.perform(get(
				"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}",
				PROJECT_ID,
				DATASET_VERSION_ID)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.build_specification").isEmpty())
				.andExpect(jsonPath("$.can_create_build_specification").value(true));

		var idempotencyKey = "dataset-build-contract-0001";
		var firstResponse = this.mockMvc.perform(post(
				"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}/build-specifications",
				PROJECT_ID,
				DATASET_VERSION_ID)
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", "dataset-build-contract-request-0001"))
				.andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.dataset_version_id").value(DATASET_VERSION_ID.toString()))
				.andExpect(jsonPath("$.selection_rule_version")
						.value("EXPLICIT_CAPTURE_SESSION_SNAPSHOT_V1"))
				.andExpect(jsonPath("$.label_schema_digest").value(LABEL_DIGEST))
				.andExpect(jsonPath("$.cleaning_rule_set_digest").value(CLEANING_DIGEST))
				.andExpect(jsonPath("$.dataset_split_plan_id").value(SPLIT_PLAN_ID.toString()))
				.andExpect(jsonPath("$.export_profile_key").value(DatasetExportProfile.CURRENT_KEY))
				.andExpect(jsonPath("$.export_profile_digest").value(DatasetExportProfile.CURRENT_DIGEST))
				.andReturn();
		var specificationId = com.jayway.jsonpath.JsonPath.read(
				firstResponse.getResponse().getContentAsString(), "$.dataset_build_specification_id").toString();

		this.mockMvc.perform(post(
				"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}/build-specifications",
				PROJECT_ID,
				DATASET_VERSION_ID)
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", idempotencyKey))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.dataset_build_specification_id").value(specificationId));

		this.mockMvc.perform(get(
				"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}",
				PROJECT_ID,
				DATASET_VERSION_ID)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.build_specification.dataset_build_specification_id")
						.value(specificationId))
				.andExpect(jsonPath("$.can_create_build_specification").value(false));

		assertEquals(1, this.jdbcTemplate.queryForObject(
				"""
				SELECT count(*)
				FROM audit_event
				WHERE project_id = ?
				  AND action = 'DATASET_BUILD_SPECIFICATION_CREATED'
				  AND object_id = ?
				""",
				Integer.class,
				PROJECT_ID,
				specificationId));
	}

	@Test
	void replaysAHistoricalV1BuildSpecificationWithoutRewritingIt() throws Exception {
		var specificationId = new UUID(0, 704);
		var idempotencyKey = "dataset-build-contract-v1-replay";
		this.jdbcTemplate.update("""
				INSERT INTO dataset_build_specification (
				    dataset_build_specification_id, dataset_version_id, project_id,
				    selection_rule_version, label_schema_digest, cleaning_rule_set_digest,
				    dataset_split_plan_id, export_profile_key, export_profile_digest,
				    created_by, idempotency_key, request_fingerprint, created_at)
				VALUES (?, ?, ?, 'EXPLICIT_CAPTURE_SESSION_SNAPSHOT_V1', ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
				specificationId,
				DATASET_VERSION_ID,
				PROJECT_ID,
				LABEL_DIGEST,
				CLEANING_DIGEST,
				SPLIT_PLAN_ID,
				DatasetExportProfile.LEGACY_V1_KEY,
				DatasetExportProfile.LEGACY_V1_DIGEST,
				ProjectMembershipTestData.ADMIN_ACTOR,
				idempotencyKey,
				DatasetBuildSpecificationService.requestFingerprint(
						DATASET_VERSION_ID,
						DatasetExportProfile.LEGACY_V1_KEY,
						DatasetExportProfile.LEGACY_V1_DIGEST),
				Timestamp.from(CREATED_AT));
		var auditCountBeforeReplay = this.jdbcTemplate.queryForObject(
				"""
				SELECT count(*)
				FROM audit_event
				WHERE project_id = ?
				  AND action = 'DATASET_BUILD_SPECIFICATION_CREATED'
				""",
				Integer.class,
				PROJECT_ID);

		this.mockMvc.perform(post(
				"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}/build-specifications",
				PROJECT_ID,
				DATASET_VERSION_ID)
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", idempotencyKey))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.dataset_build_specification_id").value(specificationId.toString()))
				.andExpect(jsonPath("$.export_profile_key").value(DatasetExportProfile.LEGACY_V1_KEY))
				.andExpect(jsonPath("$.export_profile_digest").value(DatasetExportProfile.LEGACY_V1_DIGEST));

		assertEquals(auditCountBeforeReplay, this.jdbcTemplate.queryForObject(
				"""
				SELECT count(*)
				FROM audit_event
				WHERE project_id = ?
				  AND action = 'DATASET_BUILD_SPECIFICATION_CREATED'
				""",
				Integer.class,
				PROJECT_ID));
	}

	private void insertDraftWithSplit() {
		var timestamp = Timestamp.from(CREATED_AT);
		this.jdbcTemplate.update("""
				INSERT INTO dataset_version (
				    dataset_version_id, project_id, display_name, dataset_version_status,
				    created_by, idempotency_key, request_fingerprint, created_at, row_version)
				VALUES (?, ?, 'Synthetic build draft', 'DRAFT', ?, ?, ?, ?, 0)
				""",
				DATASET_VERSION_ID,
				PROJECT_ID,
				ProjectMembershipTestData.ADMIN_ACTOR,
				"dataset-build-draft-0001",
				"a".repeat(64),
				timestamp);
		for (var index = 0; index < 2; index++) {
			var memberId = new UUID(0, 710 + index);
			var captureSessionId = new UUID(0, 720 + index);
			this.jdbcTemplate.update("""
					INSERT INTO dataset_member_session (
					    dataset_member_session_id, dataset_version_id, project_id, capture_session_id,
					    candidate_index, expected_camera_count, received_camera_count, assembly_status, quality_status)
					VALUES (?, ?, ?, ?, ?, 1, 1, 'COMPLETE', 'PASS')
					""",
					memberId,
					DATASET_VERSION_ID,
					PROJECT_ID,
					captureSessionId,
					index);
			this.jdbcTemplate.update("""
					INSERT INTO dataset_member_stream (
					    dataset_member_stream_id, dataset_member_session_id, dataset_version_id, project_id,
					    camera_stream_id, clip_asset_id, camera_index, asset_sha256,
					    quality_review_decision, quality_review_version, annotation_revision_id,
					    annotation_document_sha256, label_schema_digest, cleaning_result_id,
					    cleaning_run_id, cleaning_rule_set_digest, post_cleaning_outcome)
					VALUES (?, ?, ?, ?, ?, ?, 0, ?, 'QUALIFIED', 1, ?, ?, ?, ?, ?, ?, 'PASS')
					""",
					new UUID(0, 730 + index),
					memberId,
					DATASET_VERSION_ID,
					PROJECT_ID,
					new UUID(0, 740 + index),
					new UUID(0, 750 + index),
					"b".repeat(64),
					new UUID(0, 760 + index),
					"b".repeat(64),
					LABEL_DIGEST,
					new UUID(0, 770 + index),
					new UUID(0, 780 + index),
					CLEANING_DIGEST);
		}
		this.jdbcTemplate.update("""
				INSERT INTO dataset_split_plan (
				    dataset_split_plan_id, dataset_version_id, project_id, policy_version, split_seed,
				    holdout_count, created_by, idempotency_key, request_fingerprint, created_at)
				VALUES (?, ?, ?, 'CAPTURE_SESSION_HASH_RANK_V1', 20260729, 1, ?, ?, ?, ?)
				""",
				SPLIT_PLAN_ID,
				DATASET_VERSION_ID,
				PROJECT_ID,
				ProjectMembershipTestData.ADMIN_ACTOR,
				"dataset-build-split-0001",
				"e".repeat(64),
				timestamp);
		for (var index = 0; index < 2; index++) {
			var captureSessionId = new UUID(0, 720 + index);
			this.jdbcTemplate.update("""
					INSERT INTO dataset_split_assignment (
					    dataset_split_assignment_id, dataset_split_plan_id, dataset_version_id,
					    dataset_member_session_id, project_id, capture_session_id, candidate_index,
					    leakage_group_type, leakage_group_id, split, assignment_rank)
					VALUES (?, ?, ?, ?, ?, ?, ?, 'CAPTURE_SESSION', ?, ?, ?)
					""",
					new UUID(0, 790 + index),
					SPLIT_PLAN_ID,
					DATASET_VERSION_ID,
					new UUID(0, 710 + index),
					PROJECT_ID,
					captureSessionId,
					index,
					captureSessionId,
					index == 0 ? "PILOT" : "HOLDOUT",
					index == 0 ? 1 : 0);
		}
	}

	private void clearState() {
		this.jdbcTemplate.update("DELETE FROM dataset_build_specification WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM dataset_split_assignment WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM dataset_split_plan WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM dataset_member_stream WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM dataset_member_session WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM dataset_version WHERE project_id = ?", PROJECT_ID);
	}
}
