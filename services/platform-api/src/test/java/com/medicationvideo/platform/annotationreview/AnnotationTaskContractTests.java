package com.medicationvideo.platform.annotationreview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.medicationvideo.platform.support.CaptureMediaProjectionTestData;
import com.medicationvideo.platform.support.ProjectMembershipTestData;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnnotationTaskContractTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000500");
	private static final UUID OTHER_PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000599");
	private static final String ADMIN_TOKEN = "synthetic-test-admin-bearer-token-do-not-reuse";
	private static final String MEMBER_TOKEN = "synthetic-test-bearer-token-do-not-reuse";
	private static final String SHA256 = "b".repeat(64);
	private static final String CURRENT_LABEL_SCHEMA_ID = "10000000-0000-0000-0000-000000000001";
	private static final String CURRENT_LABEL_SCHEMA_DIGEST =
			"305e92dcd10343e6c97b5780e59485b63183fadad7e9b87bc85db4bb037aa203";
	private static final String MEDIA_RECIPE_DIGEST =
			"8900937958d0fb81a9c9bf2d9e81ac3e57f35d838d5a4da52b58ccf0f5cd5765";
	private static final Instant BASE_TIME = Instant.parse("2026-07-23T01:00:00Z");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		clearState(PROJECT_ID);
		clearState(OTHER_PROJECT_ID);
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, PROJECT_ID);
	}

	@AfterEach
	void tearDown() {
		clearState(PROJECT_ID);
		clearState(OTHER_PROJECT_ID);
	}

	@Test
	void administratorCreatesAQualifiedAssetTaskAndItIsAudited() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		var idempotencyKey = "annotation-task-qualified-" + UUID.randomUUID();

		create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR, idempotencyKey)
				.andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.project_id").value(PROJECT_ID.toString()))
				.andExpect(jsonPath("$.clip_asset_id").value(clipAssetId.toString()))
				.andExpect(jsonPath("$.label_schema_id").value(CURRENT_LABEL_SCHEMA_ID))
				.andExpect(jsonPath("$.label_schema_digest").value(CURRENT_LABEL_SCHEMA_DIGEST))
				.andExpect(jsonPath("$.assignee_actor_id").value(ProjectMembershipTestData.MEMBER_ACTOR))
				.andExpect(jsonPath("$.status").value("ASSIGNED"));

		this.mockMvc.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID)
				.header("Authorization", "Bearer " + MEMBER_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].clip_asset_id").value(clipAssetId.toString()))
				.andExpect(jsonPath("$.progress.scope").value("ASSIGNEE"))
				.andExpect(jsonPath("$.progress.total").value(1))
				.andExpect(jsonPath("$.progress.awaiting_submission").value(1))
				.andExpect(jsonPath("$.progress.rework_required").value(0))
				.andExpect(jsonPath("$.progress.awaiting_review").value(0))
				.andExpect(jsonPath("$.progress.completed").value(0));

		assertEquals(1, count("""
				SELECT count(*) FROM audit_event
				WHERE action = 'ANNOTATION_TASK_CREATED'
				  AND object_type = 'AnnotationTask'
				  AND details_json ->> 'clip_asset_id' = ?
				""", clipAssetId.toString()));
	}

	@Test
	void projectMemberReadsTheImmutablePrimaryStepLabelSchema() throws Exception {
		this.mockMvc.perform(get("/api/v1/projects/{projectId}/annotation-label-schema", PROJECT_ID)
				.header("Authorization", "Bearer " + MEMBER_TOKEN))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.label_schema_id").value(CURRENT_LABEL_SCHEMA_ID))
				.andExpect(jsonPath("$.schema_key").value("medication-primary-step"))
				.andExpect(jsonPath("$.schema_version").value("p0-primary-step.v1"))
				.andExpect(jsonPath("$.schema_digest").value(CURRENT_LABEL_SCHEMA_DIGEST))
				.andExpect(jsonPath("$.layer").value("primary_step"))
				.andExpect(jsonPath("$.labels.length()").value(5))
				.andExpect(jsonPath("$.labels[0].label_id").value("prepare_workspace"))
				.andExpect(jsonPath("$.labels[0].display_name").value("准备工位/器具"))
				.andExpect(jsonPath("$.labels[4].label_id").value("cleanup"));

		this.mockMvc.perform(get("/api/v1/projects/{projectId}/annotation-label-schema", UUID.randomUUID())
				.header("Authorization", "Bearer " + MEMBER_TOKEN))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error_code").value("PROJECT_NOT_FOUND"));
	}

	@Test
	void datasetDraftListUsesAStableCursorAndDoesNotCrossProjectBoundaries() throws Exception {
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, OTHER_PROJECT_ID);
		var newestId = UUID.fromString("d0000000-0000-4000-8000-000000000005");
		var sameTimeHigherId = UUID.fromString("d0000000-0000-4000-8000-000000000004");
		var sameTimeLowerId = UUID.fromString("d0000000-0000-4000-8000-000000000003");
		var oldestId = UUID.fromString("d0000000-0000-4000-8000-000000000002");
		var otherProjectId = UUID.fromString("d0000000-0000-4000-8000-000000000001");
		insertDatasetDraft(PROJECT_ID, newestId, "最新草稿", BASE_TIME.plusSeconds(20));
		insertDatasetDraft(PROJECT_ID, sameTimeHigherId, "同秒草稿 B", BASE_TIME.plusSeconds(10));
		insertDatasetDraft(PROJECT_ID, sameTimeLowerId, "同秒草稿 A", BASE_TIME.plusSeconds(10));
		insertDatasetDraft(PROJECT_ID, oldestId, "最早草稿", BASE_TIME);
		insertDatasetDraft(OTHER_PROJECT_ID, otherProjectId, "其他项目草稿", BASE_TIME.plusSeconds(30));

		var firstPage = this.mockMvc.perform(get("/api/v1/projects/{projectId}/dataset-versions", PROJECT_ID)
				.param("limit", "2")
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].dataset_version_id").value(newestId.toString()))
				.andExpect(jsonPath("$.items[1].dataset_version_id").value(sameTimeHigherId.toString()))
				.andExpect(jsonPath("$.next_cursor").isNotEmpty())
				.andReturn().getResponse().getContentAsString();
		var nextCursor = com.jayway.jsonpath.JsonPath.<String>read(firstPage, "$.next_cursor");

		this.mockMvc.perform(get("/api/v1/projects/{projectId}/dataset-versions", PROJECT_ID)
				.param("limit", "2")
				.param("cursor", nextCursor)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].dataset_version_id").value(sameTimeLowerId.toString()))
				.andExpect(jsonPath("$.items[1].dataset_version_id").value(oldestId.toString()))
				.andExpect(jsonPath("$.next_cursor").isEmpty());

		this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}",
						PROJECT_ID,
						otherProjectId)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error_code").value("DATASET_VERSION_NOT_FOUND"));
	}

	@Test
	void administratorCreatesAMultiSessionDraftInTheSubmittedOrder() throws Exception {
		var firstSessionId = createEligibleDatasetSession(BASE_TIME.plusSeconds(10));
		var secondSessionId = createEligibleDatasetSession(BASE_TIME.plusSeconds(20));

		var response = createDatasetDraft(
				ADMIN_TOKEN,
				List.of(secondSessionId, firstSessionId),
				"dataset-version-multi-session-" + UUID.randomUUID())
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("DRAFT"))
				.andReturn().getResponse().getContentAsString();
		var datasetVersionId = UUID.fromString(
				com.jayway.jsonpath.JsonPath.<String>read(response, "$.dataset_version_id"));

		var memberSessionIds = this.jdbcTemplate.query("""
				SELECT capture_session_id
				FROM dataset_member_session
				WHERE dataset_version_id = ?
				ORDER BY candidate_index
				""", (resultSet, rowNumber) -> UUID.fromString(resultSet.getString("capture_session_id")), datasetVersionId);
		assertEquals(List.of(secondSessionId, firstSessionId), memberSessionIds);
		assertEquals(2, count("SELECT count(*) FROM dataset_member_session WHERE dataset_version_id = ?", datasetVersionId));
		assertEquals(2, count("SELECT count(*) FROM dataset_member_stream WHERE dataset_version_id = ?", datasetVersionId));
		assertEquals(1, count("""
				SELECT count(*) FROM audit_event
				WHERE action = 'DATASET_VERSION_DRAFT_CREATED'
				  AND object_id = ?
				""", datasetVersionId.toString()));
		this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.members.length()").value(2))
				.andExpect(jsonPath("$.members[0].capture_session_id").value(secondSessionId.toString()))
				.andExpect(jsonPath("$.members[0].candidate_index").value(0))
				.andExpect(jsonPath("$.members[1].capture_session_id").value(firstSessionId.toString()))
				.andExpect(jsonPath("$.members[1].candidate_index").value(1));
	}

	@Test
	void administratorCreatesOneReproducibleCaptureSessionSplitPlan() throws Exception {
		var firstSessionId = createEligibleDatasetSession(BASE_TIME.plusSeconds(10));
		var secondSessionId = createEligibleDatasetSession(BASE_TIME.plusSeconds(20));
		var thirdSessionId = createEligibleDatasetSession(BASE_TIME.plusSeconds(30));
		var draftResponse = createDatasetDraft(
				ADMIN_TOKEN,
				List.of(firstSessionId, secondSessionId, thirdSessionId),
				"dataset-version-split-plan-" + UUID.randomUUID())
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var datasetVersionId = UUID.fromString(
				com.jayway.jsonpath.JsonPath.<String>read(draftResponse, "$.dataset_version_id"));

		this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.split_plan").isEmpty())
				.andExpect(jsonPath("$.can_create_split_plan").value(true));
		this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + MEMBER_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.can_create_split_plan").value(false));

		createDatasetSplitPlan(
				MEMBER_TOKEN, datasetVersionId, 1, "dataset-split-member-" + UUID.randomUUID())
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("PROJECT_ACCESS_DENIED"));
		createDatasetSplitPlan(
				ADMIN_TOKEN, datasetVersionId, 3, "dataset-split-invalid-" + UUID.randomUUID())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("DATASET_SPLIT_PLAN_HOLDOUT_COUNT_INVALID"));
		assertEquals(0, count("SELECT count(*) FROM dataset_split_plan WHERE project_id = ?", PROJECT_ID));

		var idempotencyKey = "dataset-split-plan-" + UUID.randomUUID();
		var splitResponse = createDatasetSplitPlan(ADMIN_TOKEN, datasetVersionId, 1, idempotencyKey)
				.andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.dataset_version_id").value(datasetVersionId.toString()))
				.andExpect(jsonPath("$.policy_version").value("CAPTURE_SESSION_HASH_RANK_V1"))
				.andExpect(jsonPath("$.split_seed").isNumber())
				.andExpect(jsonPath("$.pilot_count").value(2))
				.andExpect(jsonPath("$.holdout_count").value(1))
				.andExpect(jsonPath("$.assignments.length()").value(3))
				.andExpect(jsonPath("$.assignments[0].candidate_index").value(0))
				.andExpect(jsonPath("$.assignments[1].candidate_index").value(1))
				.andExpect(jsonPath("$.assignments[2].candidate_index").value(2))
				.andExpect(jsonPath("$.assignments[0].leakage_group_type").value("CAPTURE_SESSION"))
				.andReturn().getResponse().getContentAsString();
		var splitPlanId = UUID.fromString(
				com.jayway.jsonpath.JsonPath.<String>read(splitResponse, "$.dataset_split_plan_id"));

		assertEquals(1, count("SELECT count(*) FROM dataset_split_plan WHERE dataset_version_id = ?", datasetVersionId));
		assertEquals(3, count("SELECT count(*) FROM dataset_split_assignment WHERE dataset_version_id = ?", datasetVersionId));
		assertEquals(1, count("""
				SELECT count(*) FROM dataset_split_assignment
				WHERE dataset_version_id = ? AND split = 'HOLDOUT'
				""", datasetVersionId));
		assertEquals(3, count("""
				SELECT count(*) FROM dataset_split_assignment
				WHERE dataset_version_id = ? AND leakage_group_id = capture_session_id
				""", datasetVersionId));
		assertEquals(1, count("""
				SELECT count(*) FROM audit_event
				WHERE action = 'DATASET_SPLIT_PLAN_CREATED' AND object_id = ?
				""", splitPlanId.toString()));

		createDatasetSplitPlan(ADMIN_TOKEN, datasetVersionId, 1, idempotencyKey)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.dataset_split_plan_id").value(splitPlanId.toString()));
		createDatasetSplitPlan(ADMIN_TOKEN, datasetVersionId, 2, idempotencyKey)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("DATASET_SPLIT_PLAN_IDEMPOTENCY_KEY_REUSED"));
		createDatasetSplitPlan(
				ADMIN_TOKEN, datasetVersionId, 1, "dataset-split-second-" + UUID.randomUUID())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("DATASET_SPLIT_PLAN_ALREADY_EXISTS"));
		assertEquals(1, count("SELECT count(*) FROM dataset_split_plan WHERE dataset_version_id = ?", datasetVersionId));
		assertEquals(1, count("""
				SELECT count(*) FROM audit_event
				WHERE action = 'DATASET_SPLIT_PLAN_CREATED' AND object_id = ?
				""", splitPlanId.toString()));

		this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + MEMBER_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.split_plan.dataset_split_plan_id").value(splitPlanId.toString()))
				.andExpect(jsonPath("$.split_plan.assignments.length()").value(3))
				.andExpect(jsonPath("$.can_create_split_plan").value(false));
		this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}/current-admission",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + MEMBER_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.build_readiness.ready_for_build").value(false))
				.andExpect(jsonPath("$.build_readiness.dataset_split_plan_id").value(splitPlanId.toString()))
				.andExpect(jsonPath("$.build_readiness.blocking_reasons.length()").value(4))
				.andExpect(jsonPath("$.build_readiness.blocking_reasons[0]")
						.value("SELECTION_RULE_NOT_FIXED"))
				.andExpect(jsonPath("$.build_readiness.blocking_reasons[3]")
						.value("EXPORT_PROFILE_NOT_FIXED"));
	}

	@Test
	void datasetDraftRejectsAnIdempotencyReplayWhoseSessionOrderChanged() throws Exception {
		var firstSessionId = createEligibleDatasetSession(BASE_TIME.plusSeconds(10));
		var secondSessionId = createEligibleDatasetSession(BASE_TIME.plusSeconds(20));
		var idempotencyKey = "dataset-version-order-replay-" + UUID.randomUUID();

		createDatasetDraft(
				ADMIN_TOKEN,
				List.of(firstSessionId, secondSessionId),
				idempotencyKey)
				.andExpect(status().isCreated());
		createDatasetDraft(
				ADMIN_TOKEN,
				List.of(secondSessionId, firstSessionId),
				idempotencyKey)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("DATASET_VERSION_IDEMPOTENCY_KEY_REUSED"));

		assertEquals(1, count("SELECT count(*) FROM dataset_version WHERE project_id = ?", PROJECT_ID));
	}

	@Test
	void administratorsStartSealValidateAndFreezeAReadyDatasetBuildExactlyOnce() throws Exception {
		var firstSessionId = createEligibleDatasetSession(BASE_TIME.plusSeconds(10));
		var secondSessionId = createEligibleDatasetSession(BASE_TIME.plusSeconds(20));
		var draftResponse = createDatasetDraft(
				ADMIN_TOKEN,
				List.of(firstSessionId, secondSessionId),
				"dataset-build-start-draft-" + UUID.randomUUID())
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var datasetVersionId = UUID.fromString(
				com.jayway.jsonpath.JsonPath.read(draftResponse, "$.dataset_version_id"));
		createDatasetSplitPlan(
				ADMIN_TOKEN,
				datasetVersionId,
				1,
				"dataset-build-start-split-" + UUID.randomUUID())
				.andExpect(status().isCreated());
		createDatasetBuildSpecification(
				ADMIN_TOKEN,
				datasetVersionId,
				"dataset-build-start-spec-" + UUID.randomUUID())
				.andExpect(status().isCreated());

		this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}/current-admission",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.build_readiness.ready_for_build").value(true))
				.andExpect(jsonPath("$.can_start_build").value(true));
		this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}/current-admission",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + MEMBER_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.can_start_build").value(false));

		var idempotencyKey = "dataset-build-start-" + UUID.randomUUID();
		startDatasetBuild(MEMBER_TOKEN, datasetVersionId, idempotencyKey)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("PROJECT_ACCESS_DENIED"));
		var startedResponse = startDatasetBuild(ADMIN_TOKEN, datasetVersionId, idempotencyKey)
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.dataset_version_id").value(datasetVersionId.toString()))
				.andExpect(jsonPath("$.status").value("BUILDING"))
				.andExpect(jsonPath("$.started_by").value(ProjectMembershipTestData.ADMIN_ACTOR))
				.andExpect(jsonPath("$.started_at").exists())
				.andReturn().getResponse().getContentAsString();
		var startedAt = com.jayway.jsonpath.JsonPath.<String>read(startedResponse, "$.started_at");

		startDatasetBuild(ADMIN_TOKEN, datasetVersionId, idempotencyKey)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("BUILDING"))
				.andExpect(jsonPath("$.started_at").value(startedAt));
		startDatasetBuild(
				ADMIN_TOKEN,
				datasetVersionId,
				"dataset-build-start-second-" + UUID.randomUUID())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("DATASET_VERSION_BUILD_STATUS_INVALID"));

		this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("BUILDING"));
		this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}/current-admission",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.build_readiness.ready_for_build").value(true))
				.andExpect(jsonPath("$.can_start_build").value(false));
		assertThrows(DataIntegrityViolationException.class, () -> this.jdbcTemplate.update("""
				UPDATE dataset_version
				SET build_started_at = NULL
				WHERE dataset_version_id = ?
				""", datasetVersionId));

		var snapshotIdempotencyKey = "dataset-build-snapshot-" + UUID.randomUUID();
		createDatasetBuildSnapshot(MEMBER_TOKEN, datasetVersionId, snapshotIdempotencyKey)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("PROJECT_ACCESS_DENIED"));
		var snapshotResponse = createDatasetBuildSnapshot(
				ADMIN_TOKEN, datasetVersionId, snapshotIdempotencyKey)
				.andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.dataset_version_id").value(datasetVersionId.toString()))
				.andExpect(jsonPath("$.snapshot_schema_version")
						.value("DATASET_RELATIONAL_SNAPSHOT_V1"))
				.andExpect(jsonPath("$.input_variant_policy").value("RAW_CLIP_ASSET_V1"))
				.andExpect(jsonPath("$.member_count").value(2))
				.andExpect(jsonPath("$.stream_count").value(2))
				.andExpect(jsonPath("$.snapshot_digest").value(org.hamcrest.Matchers.matchesPattern("[a-f0-9]{64}")))
				.andReturn().getResponse().getContentAsString();
		var buildSnapshotId = com.jayway.jsonpath.JsonPath.<String>read(
				snapshotResponse, "$.dataset_build_snapshot_id");
		var snapshotDigest = com.jayway.jsonpath.JsonPath.<String>read(
				snapshotResponse, "$.snapshot_digest");

		createDatasetBuildSnapshot(ADMIN_TOKEN, datasetVersionId, snapshotIdempotencyKey)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.dataset_build_snapshot_id").value(buildSnapshotId))
				.andExpect(jsonPath("$.snapshot_digest").value(snapshotDigest));
		createDatasetBuildSnapshot(
				ADMIN_TOKEN,
				datasetVersionId,
				"dataset-build-snapshot-second-" + UUID.randomUUID())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("DATASET_BUILD_SNAPSHOT_STATUS_INVALID"));

		this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("VALIDATING"))
				.andExpect(jsonPath("$.build_snapshot.dataset_build_snapshot_id").value(buildSnapshotId))
				.andExpect(jsonPath("$.build_snapshot.snapshot_digest").value(snapshotDigest))
				.andExpect(jsonPath("$.can_create_build_snapshot").value(false))
				.andExpect(jsonPath("$.validation").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.can_validate").value(true));
		assertEquals(1, count(
				"SELECT count(*) FROM dataset_build_snapshot WHERE dataset_version_id = ?",
				datasetVersionId));
		assertThrows(DataIntegrityViolationException.class, () -> this.jdbcTemplate.update("""
				UPDATE dataset_version
				SET build_snapshot_id = NULL
				WHERE dataset_version_id = ?
				""", datasetVersionId));

		var validationIdempotencyKey = "dataset-validation-" + UUID.randomUUID();
		validateDatasetVersion(MEMBER_TOKEN, datasetVersionId, validationIdempotencyKey)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("PROJECT_ACCESS_DENIED"));
		var validationResponse = validateDatasetVersion(
				ADMIN_TOKEN, datasetVersionId, validationIdempotencyKey)
				.andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.dataset_version_id").value(datasetVersionId.toString()))
				.andExpect(jsonPath("$.dataset_build_snapshot_id").value(buildSnapshotId))
				.andExpect(jsonPath("$.validation_profile_version").value("DATASET_FREEZE_VALIDATION_V1"))
				.andExpect(jsonPath("$.outcome").value("PASS"))
				.andExpect(jsonPath("$.reason_codes").isEmpty())
				.andExpect(jsonPath("$.executed_checks.length()").value(5))
				.andExpect(jsonPath("$.validated_snapshot_digest").value(snapshotDigest))
				.andReturn().getResponse().getContentAsString();
		var validationId = com.jayway.jsonpath.JsonPath.<String>read(
				validationResponse, "$.dataset_version_validation_id");

		validateDatasetVersion(ADMIN_TOKEN, datasetVersionId, validationIdempotencyKey)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.dataset_version_validation_id").value(validationId))
				.andExpect(jsonPath("$.outcome").value("PASS"));
		validateDatasetVersion(
				ADMIN_TOKEN,
				datasetVersionId,
				"dataset-validation-second-" + UUID.randomUUID())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("DATASET_VERSION_VALIDATION_ALREADY_EXISTS"));

		this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("VALIDATING"))
				.andExpect(jsonPath("$.validation.dataset_version_validation_id").value(validationId))
				.andExpect(jsonPath("$.validation.outcome").value("PASS"))
				.andExpect(jsonPath("$.can_validate").value(false))
				.andExpect(jsonPath("$.freeze_approval.status").value("PENDING"))
				.andExpect(jsonPath("$.freeze_approval.requested_by")
						.value(ProjectMembershipTestData.ADMIN_ACTOR))
				.andExpect(jsonPath("$.can_freeze").value(false));
		assertEquals(1, count(
				"SELECT count(*) FROM dataset_version_validation WHERE dataset_version_id = ?",
				datasetVersionId));
		assertEquals(1, count(
				"SELECT count(*) FROM approval_request WHERE object_id = ?",
				datasetVersionId));
		assertThrows(DataIntegrityViolationException.class, () -> this.jdbcTemplate.update("""
				UPDATE dataset_version_validation
				SET outcome = 'FAIL'
				WHERE dataset_version_id = ?
				""", datasetVersionId));

		var freezeIdempotencyKey = "dataset-freeze-" + UUID.randomUUID();
		freezeDatasetVersion(ADMIN_TOKEN, datasetVersionId, freezeIdempotencyKey)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code")
						.value("DATASET_VERSION_FREEZE_SELF_APPROVAL_FORBIDDEN"));
		this.jdbcTemplate.update("""
				UPDATE membership
				SET role = 'ADMIN',
				    updated_by = ?,
				    updated_at = ?,
				    row_version = row_version + 1
				WHERE project_id = ? AND actor_id = ?
				""",
				ProjectMembershipTestData.ADMIN_ACTOR,
				Timestamp.from(BASE_TIME.plusSeconds(30)),
				PROJECT_ID,
				ProjectMembershipTestData.MEMBER_ACTOR);
		var pendingApprovalResponse = this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + MEMBER_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("VALIDATING"))
				.andExpect(jsonPath("$.freeze_approval.status").value("PENDING"))
				.andExpect(jsonPath("$.can_freeze").value(true))
				.andReturn().getResponse().getContentAsString();
		var approvalRequestId = com.jayway.jsonpath.JsonPath.<String>read(
				pendingApprovalResponse, "$.freeze_approval.approval_request_id");

		var freezeResponse = freezeDatasetVersion(MEMBER_TOKEN, datasetVersionId, freezeIdempotencyKey)
				.andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.dataset_version_id").value(datasetVersionId.toString()))
				.andExpect(jsonPath("$.status").value("FROZEN"))
				.andExpect(jsonPath("$.validation_id").value(validationId))
				.andExpect(jsonPath("$.approval_request_id").value(approvalRequestId))
				.andExpect(jsonPath("$.frozen_by").value(ProjectMembershipTestData.MEMBER_ACTOR))
				.andReturn().getResponse().getContentAsString();
		var approvalDecisionId = com.jayway.jsonpath.JsonPath.<String>read(
				freezeResponse, "$.approval_decision_id");
		freezeDatasetVersion(MEMBER_TOKEN, datasetVersionId, freezeIdempotencyKey)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.approval_decision_id").value(approvalDecisionId))
				.andExpect(jsonPath("$.status").value("FROZEN"));
		freezeDatasetVersion(
				MEMBER_TOKEN,
				datasetVersionId,
				"dataset-freeze-second-" + UUID.randomUUID())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("DATASET_VERSION_ALREADY_FROZEN"));

		this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + MEMBER_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FROZEN"))
				.andExpect(jsonPath("$.freeze_approval.status").value("APPROVED"))
				.andExpect(jsonPath("$.freeze_approval.approval_decision_id").value(approvalDecisionId))
				.andExpect(jsonPath("$.freeze_approval.approved_by")
						.value(ProjectMembershipTestData.MEMBER_ACTOR))
				.andExpect(jsonPath("$.can_freeze").value(false))
				.andExpect(jsonPath("$.export_request").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.can_request_export").value(true));
		assertEquals(1, count(
				"SELECT count(*) FROM approval_decision WHERE approval_request_id = ?",
				UUID.fromString(approvalRequestId)));
		assertThrows(DataIntegrityViolationException.class, () -> this.jdbcTemplate.update("""
				UPDATE dataset_version
				SET frozen_by = NULL
				WHERE dataset_version_id = ?
				""", datasetVersionId));

		this.jdbcTemplate.update("""
				UPDATE membership
				SET role = 'MEMBER',
				    updated_by = ?,
				    updated_at = ?,
				    row_version = row_version + 1
				WHERE project_id = ? AND actor_id = ?
				""",
				ProjectMembershipTestData.ADMIN_ACTOR,
				Timestamp.from(BASE_TIME.plusSeconds(40)),
				PROJECT_ID,
				ProjectMembershipTestData.MEMBER_ACTOR);
		var exportExpiry = Instant.now()
				.plusSeconds(3600)
				.truncatedTo(java.time.temporal.ChronoUnit.MICROS)
				.toString();
		var exportIdempotencyKey = "dataset-export-" + UUID.randomUUID();
		requestDatasetExport(
				MEMBER_TOKEN,
				datasetVersionId,
				exportIdempotencyKey,
				exportExpiry)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("PROJECT_ACCESS_DENIED"));
		var exportResponse = requestDatasetExport(
				ADMIN_TOKEN,
				datasetVersionId,
				exportIdempotencyKey,
				exportExpiry)
				.andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.dataset_version_id").value(datasetVersionId.toString()))
				.andExpect(jsonPath("$.status").value("REQUESTED"))
				.andExpect(jsonPath("$.purpose").value("MODEL_TRAINING"))
				.andExpect(jsonPath("$.expires_at").value(exportExpiry))
				.andExpect(jsonPath("$.dataset_build_snapshot_id").value(buildSnapshotId))
				.andExpect(jsonPath("$.snapshot_digest").value(snapshotDigest))
				.andExpect(jsonPath("$.dataset_version_validation_id").value(validationId))
				.andExpect(jsonPath("$.export_profile_key").value("p0-dataset-export-v2"))
				.andExpect(jsonPath("$.approval.status").value("PENDING"))
				.andExpect(jsonPath("$.approval.requested_by")
						.value(ProjectMembershipTestData.ADMIN_ACTOR))
				.andReturn().getResponse().getContentAsString();
		var datasetExportId = com.jayway.jsonpath.JsonPath.<String>read(
				exportResponse, "$.dataset_export_id");
		var exportApprovalRequestId = com.jayway.jsonpath.JsonPath.<String>read(
				exportResponse, "$.approval.approval_request_id");
		requestDatasetExport(
				ADMIN_TOKEN,
				datasetVersionId,
				exportIdempotencyKey,
				exportExpiry)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.dataset_export_id").value(datasetExportId))
				.andExpect(jsonPath("$.approval.approval_request_id").value(exportApprovalRequestId));
		requestDatasetExport(
				ADMIN_TOKEN,
				datasetVersionId,
				"dataset-export-second-" + UUID.randomUUID(),
				exportExpiry)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("DATASET_EXPORT_ALREADY_REQUESTED"));
		getDatasetExportApprovalReadiness(ADMIN_TOKEN, datasetVersionId)
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.dataset_export_id").value(datasetExportId))
				.andExpect(jsonPath("$.approval_request_id").value(exportApprovalRequestId))
				.andExpect(jsonPath("$.ready_for_approval").value(false))
				.andExpect(jsonPath("$.blocking_reasons[0]").value("SELF_APPROVAL_FORBIDDEN"));
		getDatasetExportApprovalReadiness(MEMBER_TOKEN, datasetVersionId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ready_for_approval").value(false))
				.andExpect(jsonPath("$.blocking_reasons[0]").value("APPROVER_ADMIN_REQUIRED"));
		this.jdbcTemplate.update("""
				UPDATE membership
				SET role = 'ADMIN',
				    updated_by = ?,
				    updated_at = ?,
				    row_version = row_version + 1
				WHERE project_id = ? AND actor_id = ?
				""",
				ProjectMembershipTestData.ADMIN_ACTOR,
				Timestamp.from(BASE_TIME.plusSeconds(41)),
				PROJECT_ID,
				ProjectMembershipTestData.MEMBER_ACTOR);
		getDatasetExportApprovalReadiness(MEMBER_TOKEN, datasetVersionId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.dataset_export_id").value(datasetExportId))
				.andExpect(jsonPath("$.ready_for_approval").value(true))
				.andExpect(jsonPath("$.blocking_reasons").isEmpty());

		this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + MEMBER_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.export_request.dataset_export_id").value(datasetExportId))
				.andExpect(jsonPath("$.export_request.status").value("REQUESTED"))
				.andExpect(jsonPath("$.export_request.approval.status").value("PENDING"))
				.andExpect(jsonPath("$.can_request_export").value(false));
		assertEquals(1, count(
				"SELECT count(*) FROM dataset_export WHERE dataset_version_id = ?",
				datasetVersionId));
		assertEquals(2, count(
				"SELECT count(*) FROM approval_request WHERE object_id = ?",
				datasetVersionId));
		assertEquals(1, count("""
				SELECT count(*) FROM audit_event
				WHERE action = 'DATASET_VERSION_BUILD_STARTED'
				  AND object_id = ?
				""", datasetVersionId.toString()));
		assertEquals(1, count("""
				SELECT count(*) FROM audit_event
				WHERE action = 'DATASET_BUILD_SNAPSHOT_CREATED'
				  AND object_id = ?
				""", buildSnapshotId));
		assertEquals(1, count("""
				SELECT count(*) FROM audit_event
				WHERE action = 'DATASET_VERSION_VALIDATION_COMPLETED'
				  AND object_id = ?
				""", validationId));
		assertEquals(1, count("""
				SELECT count(*) FROM audit_event
				WHERE action = 'DATASET_VERSION_FROZEN'
				  AND object_id = ?
				""", datasetVersionId.toString()));
		assertEquals(1, count("""
				SELECT count(*) FROM audit_event
				WHERE action = 'DATASET_EXPORT_REQUESTED'
				  AND object_id = ?
				""", datasetExportId));
	}

	@Test
	void datasetDraftCreationIsAtomicWhenOneSubmittedSessionIsNotEligible() throws Exception {
		var draftAuditEventsBefore = count("""
				SELECT count(*) FROM audit_event
				WHERE project_id = ?
				  AND action = 'DATASET_VERSION_DRAFT_CREATED'
				""", PROJECT_ID);
		var eligibleSessionId = createEligibleDatasetSession(BASE_TIME.plusSeconds(10));
		var ineligibleAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		var ineligibleSessionId = CaptureMediaProjectionTestData.bindEligible(
				this.jdbcTemplate, PROJECT_ID, ineligibleAssetId, BASE_TIME.plusSeconds(20)).captureSessionId();

		createDatasetDraft(
				ADMIN_TOKEN,
				List.of(eligibleSessionId, ineligibleSessionId),
				"dataset-version-atomic-" + UUID.randomUUID())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("DATASET_VERSION_SESSION_NOT_ELIGIBLE"))
				.andExpect(jsonPath("$.details.capture_session_id").value(ineligibleSessionId.toString()));

		assertEquals(0, count("SELECT count(*) FROM dataset_version WHERE project_id = ?", PROJECT_ID));
		assertEquals(0, count("SELECT count(*) FROM dataset_member_session WHERE project_id = ?", PROJECT_ID));
		assertEquals(0, count("SELECT count(*) FROM dataset_member_stream WHERE project_id = ?", PROJECT_ID));
		assertEquals(draftAuditEventsBefore, count("""
				SELECT count(*) FROM audit_event
				WHERE project_id = ?
				  AND action = 'DATASET_VERSION_DRAFT_CREATED'
				""", PROJECT_ID));
	}

	@Test
	void datasetDraftCreationRejectsMoreThanOneHundredSubmittedSessions() throws Exception {
		var captureSessionIds = IntStream.range(0, 101)
				.mapToObj(index -> UUID.randomUUID())
				.toList();

		createDatasetDraft(
				ADMIN_TOKEN,
				captureSessionIds,
				"dataset-version-too-many-" + UUID.randomUUID())
				.andExpect(status().isBadRequest())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.error_code").value("INVALID_DATASET_VERSION_REQUEST"));

		assertEquals(0, count("SELECT count(*) FROM dataset_version WHERE project_id = ?", PROJECT_ID));
	}

	@Test
	void onlyQualifiedAssetsAndProjectAdministratorsCanCreateTasks() throws Exception {
		var qualifiedAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, qualifiedAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		var rejectedAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, rejectedAssetId, "REJECTED", "WRONG_SCENE");
		var pendingAssetId = insertClipAsset(PROJECT_ID, "UPLOADED", false);

		create(MEMBER_TOKEN, qualifiedAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-member-" + UUID.randomUUID())
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("PROJECT_ACCESS_DENIED"));
		create(ADMIN_TOKEN, rejectedAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-rejected-" + UUID.randomUUID())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("ANNOTATION_TASK_QUALITY_NOT_QUALIFIED"));
		create(ADMIN_TOKEN, pendingAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-pending-" + UUID.randomUUID())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("ANNOTATION_TASK_NOT_READY"));
	}

	@Test
	void creationReplayIsSafeAndEachAssetHasOnlyOneCurrentTask() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		var idempotencyKey = "annotation-task-replay-" + UUID.randomUUID();

		create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR, idempotencyKey)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.version").value(0));
		create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR, idempotencyKey)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.version").value(0));
		create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-duplicate-" + UUID.randomUUID())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("ANNOTATION_TASK_ALREADY_EXISTS"));

		assertEquals(1, count("SELECT count(*) FROM annotation_task WHERE clip_asset_id = ?", clipAssetId));
		assertEquals(1, count("""
				SELECT count(*) FROM audit_event
				WHERE action = 'ANNOTATION_TASK_CREATED'
				  AND details_json ->> 'clip_asset_id' = ?
				""", clipAssetId.toString()));
	}

	@Test
	void assigneeSubmitsOneImmutableTimeRangeRevisionAndItIsAudited() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		var taskResponse = create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-submit-" + UUID.randomUUID())
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var taskId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(taskResponse, "$.annotation_task_id"));
		var idempotencyKey = "annotation-revision-submit-" + UUID.randomUUID();

		var response = submit(MEMBER_TOKEN, taskId, idempotencyKey,
				"""
				{"segments":[{"start_ms":120,"end_ms":780,"label":"retrieve_medicine"}],"note":"合成验证标注"}
				""")
				.andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.annotation_task_id").value(taskId.toString()))
				.andExpect(jsonPath("$.clip_asset_id").value(clipAssetId.toString()))
				.andExpect(jsonPath("$.revision_number").value(1))
				.andExpect(jsonPath("$.annotation_schema_version").value("annotation-document.v1"))
				.andExpect(jsonPath("$.label_schema_id").value(CURRENT_LABEL_SCHEMA_ID))
				.andExpect(jsonPath("$.label_schema_digest").value(CURRENT_LABEL_SCHEMA_DIGEST))
				.andReturn().getResponse().getContentAsString();
		var revisionId = com.jayway.jsonpath.JsonPath.<String>read(response, "$.annotation_revision_id");

		submit(MEMBER_TOKEN, taskId, idempotencyKey,
				"""
				{"segments":[{"start_ms":120,"end_ms":780,"label":"retrieve_medicine"}],"note":"合成验证标注"}
				""")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.annotation_revision_id").value(revisionId));
		submit(ADMIN_TOKEN, taskId, "annotation-revision-admin-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":1,\"end_ms\":2,\"label\":\"x\"}]}")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("ANNOTATION_TASK_NOT_ASSIGNEE"));
		submit(MEMBER_TOKEN, taskId, idempotencyKey,
				"{\"segments\":[{\"start_ms\":1,\"end_ms\":2,\"label\":\"prepare_dose\"}]}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("ANNOTATION_REVISION_IDEMPOTENCY_KEY_REUSED"));
		submit(MEMBER_TOKEN, taskId, "annotation-revision-new-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":1,\"end_ms\":2,\"label\":\"cleanup\"}]}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("ANNOTATION_TASK_ALREADY_SUBMITTED"));

		assertEquals("SUBMITTED", this.jdbcTemplate.queryForObject(
				"SELECT task_status FROM annotation_task WHERE annotation_task_id = ?", String.class, taskId));
		assertEquals(1, count("SELECT count(*) FROM annotation_revision WHERE annotation_task_id = ?", taskId));
		assertEquals(1, count("""
				SELECT count(*) FROM audit_event
				WHERE action = 'ANNOTATION_REVISION_SUBMITTED'
				  AND object_type = 'AnnotationRevision'
				  AND object_id = ?
				""", revisionId));
	}

	@Test
	void submissionRejectsAnInvalidTimeRangeBeforeCreatingARetentionRecord() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		var taskResponse = create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-invalid-range-" + UUID.randomUUID())
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var taskId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(taskResponse, "$.annotation_task_id"));

		submit(MEMBER_TOKEN, taskId, "annotation-revision-invalid-range-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":100,\"end_ms\":100,\"label\":\"prepare_workspace\"}]}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error_code").value("ANNOTATION_SEGMENT_TIME_RANGE_INVALID"));
		assertEquals("ASSIGNED", this.jdbcTemplate.queryForObject(
				"SELECT task_status FROM annotation_task WHERE annotation_task_id = ?", String.class, taskId));
		assertEquals(0, count("SELECT count(*) FROM annotation_revision WHERE annotation_task_id = ?", taskId));
	}

	@Test
	void submissionRequiresSchemaLabelsAndNonOverlappingPrimarySteps() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		var taskResponse = create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-schema-validation-" + UUID.randomUUID())
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var taskId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(taskResponse, "$.annotation_task_id"));

		submit(MEMBER_TOKEN, taskId, "annotation-revision-unknown-label-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":0,\"end_ms\":100,\"label\":\"unknown_step\"}]}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error_code").value("ANNOTATION_LABEL_NOT_ALLOWED"));
		submit(MEMBER_TOKEN, taskId, "annotation-revision-overlap-" + UUID.randomUUID(),
				"""
				{"segments":[
				  {"start_ms":0,"end_ms":300,"label":"prepare_workspace"},
				  {"start_ms":100,"end_ms":200,"label":"retrieve_medicine"},
				  {"start_ms":200,"end_ms":400,"label":"prepare_dose"}
				]}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error_code").value("ANNOTATION_PRIMARY_STEP_OVERLAP"));
		assertEquals(0, count("SELECT count(*) FROM annotation_revision WHERE annotation_task_id = ?", taskId));

		submit(MEMBER_TOKEN, taskId, "annotation-revision-adjacent-" + UUID.randomUUID(),
				"""
				{"segments":[
				  {"start_ms":0,"end_ms":100,"label":"prepare_workspace"},
				  {"start_ms":100,"end_ms":200,"label":"retrieve_medicine"}
				]}
				""")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.label_schema_id").value(CURRENT_LABEL_SCHEMA_ID));
		assertEquals(1, count("SELECT count(*) FROM annotation_revision WHERE annotation_task_id = ?", taskId));
	}

	@Test
	void submissionRejectsASegmentBeyondTheVerifiedMediaDuration() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		insertVerifiedMediaReport(PROJECT_ID, clipAssetId, 500_000L);
		var taskResponse = create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-duration-" + UUID.randomUUID())
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var taskId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(taskResponse, "$.annotation_task_id"));

		submit(MEMBER_TOKEN, taskId, "annotation-revision-duration-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":100,\"end_ms\":501,\"label\":\"prepare_dose\"}]}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error_code").value("ANNOTATION_SEGMENT_EXCEEDS_MEDIA_DURATION"));
		assertEquals("ASSIGNED", this.jdbcTemplate.queryForObject(
				"SELECT task_status FROM annotation_task WHERE annotation_task_id = ?", String.class, taskId));
		assertEquals(0, count("SELECT count(*) FROM annotation_revision WHERE annotation_task_id = ?", taskId));
	}

	@Test
	void administratorIndependentlyReviewsSubmittedRevisionOnceAndItIsAudited() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		var captureSessionId = CaptureMediaProjectionTestData.bindEligible(
				this.jdbcTemplate, PROJECT_ID, clipAssetId, BASE_TIME).captureSessionId();
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		insertVerifiedMediaReport(PROJECT_ID, clipAssetId, 1_000_000L);
		var taskResponse = create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-review-" + UUID.randomUUID())
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var taskId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(taskResponse, "$.annotation_task_id"));
		var revisionResponse = submit(MEMBER_TOKEN, taskId, "annotation-revision-review-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":120,\"end_ms\":780,\"label\":\"retrieve_medicine\"}]}")
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var revisionId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(revisionResponse, "$.annotation_revision_id"));
		var idempotencyKey = "annotation-review-" + UUID.randomUUID();

		this.mockMvc.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].annotation_revision.annotation_revision_id").value(revisionId.toString()))
				.andExpect(jsonPath("$.items[0].annotation_revision.annotation_document.segments.length()").value(1))
				.andExpect(jsonPath("$.items[0].can_review").value(true))
				.andExpect(jsonPath("$.progress.scope").value("PROJECT"))
				.andExpect(jsonPath("$.progress.awaiting_review").value(1));

		var response = review(ADMIN_TOKEN, revisionId, idempotencyKey,
				"{\"decision\":\"APPROVED\",\"review_note\":\"时间段与画面一致\"}")
				.andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.annotation_revision_id").value(revisionId.toString()))
				.andExpect(jsonPath("$.decision").value("APPROVED"))
				.andExpect(jsonPath("$.reviewed_by").value(ProjectMembershipTestData.ADMIN_ACTOR))
				.andReturn().getResponse().getContentAsString();
		var reviewId = com.jayway.jsonpath.JsonPath.<String>read(response, "$.annotation_review_id");

		review(ADMIN_TOKEN, revisionId, idempotencyKey,
				"{\"decision\":\"APPROVED\",\"review_note\":\"时间段与画面一致\"}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.annotation_review_id").value(reviewId));
		review(MEMBER_TOKEN, revisionId, "annotation-review-member-" + UUID.randomUUID(),
				"{\"decision\":\"REJECTED\"}")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("PROJECT_ACCESS_DENIED"));
		this.mockMvc.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].status").value("COMPLETED"))
				.andExpect(jsonPath("$.items[0].annotation_revision.review.decision").value("APPROVED"))
				.andExpect(jsonPath("$.items[0].annotation_revision.post_cleaning_result.outcome").value("PASS"))
				.andExpect(jsonPath("$.items[0].annotation_revision.post_cleaning_result.rule_set_key")
						.value("p0-post-annotation"))
				.andExpect(jsonPath("$.items[0].can_review").value(false))
				.andExpect(jsonPath("$.items[0].can_recheck_post_cleaning").value(true))
				.andExpect(jsonPath("$.progress.total").value(1))
				.andExpect(jsonPath("$.progress.awaiting_review").value(0))
				.andExpect(jsonPath("$.progress.completed").value(1));

		assertEquals(1, count("SELECT count(*) FROM annotation_review WHERE annotation_revision_id = ?", revisionId));
		assertEquals(1, count("SELECT count(*) FROM cleaning_run WHERE annotation_revision_id = ?", revisionId));
		assertEquals("PASS", this.jdbcTemplate.queryForObject(
				"SELECT outcome FROM cleaning_result WHERE annotation_revision_id = ?",
				String.class,
				revisionId));
		assertEquals(1, count("""
				SELECT count(*) FROM audit_event
				WHERE action = 'ANNOTATION_REVISION_REVIEWED'
				  AND object_type = 'AnnotationReview'
				  AND object_id = ?
				""", reviewId));
		assertEquals(1, count("""
				SELECT count(*) FROM audit_event
				WHERE action = 'POST_ANNOTATION_CLEANING_COMPLETED'
				  AND object_type = 'CleaningResult'
				  AND details_json ->> 'annotation_revision_id' = ?
				""", revisionId.toString()));

		this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/capture-sessions/{captureSessionId}/dataset-admission-preview",
						PROJECT_ID,
						captureSessionId)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.eligible").value(true))
				.andExpect(jsonPath("$.blocking_reasons").isEmpty())
				.andExpect(jsonPath("$.streams[0].eligible").value(true))
				.andExpect(jsonPath("$.streams[0].quality_review_decision").value("QUALIFIED"))
				.andExpect(jsonPath("$.streams[0].annotation_review_decision").value("APPROVED"))
				.andExpect(jsonPath("$.streams[0].post_cleaning_decision").value("PASS"))
				.andExpect(jsonPath("$.streams[0].blocking_reasons").isEmpty());

		var datasetIdempotencyKey = "dataset-version-draft-" + UUID.randomUUID();
		var datasetDraft = createDatasetDraft(
				ADMIN_TOKEN,
				captureSessionId,
				datasetIdempotencyKey)
				.andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.project_id").value(PROJECT_ID.toString()))
				.andExpect(jsonPath("$.display_name").value("训练候选集 01"))
				.andExpect(jsonPath("$.status").value("DRAFT"))
				.andReturn().getResponse().getContentAsString();
		var datasetVersionId = UUID.fromString(
				com.jayway.jsonpath.JsonPath.<String>read(datasetDraft, "$.dataset_version_id"));
		assertEquals(1, count("SELECT count(*) FROM dataset_member_session WHERE dataset_version_id = ?", datasetVersionId));
		assertEquals(1, count("SELECT count(*) FROM dataset_member_stream WHERE dataset_version_id = ?", datasetVersionId));
		assertEquals(this.jdbcTemplate.queryForObject(
				"SELECT document_sha256 FROM annotation_revision WHERE annotation_revision_id = ?",
				String.class,
				revisionId), this.jdbcTemplate.queryForObject(
				"SELECT annotation_document_sha256 FROM dataset_member_stream WHERE dataset_version_id = ?",
				String.class,
				datasetVersionId));
		assertEquals(1, count("""
				SELECT count(*) FROM audit_event
				WHERE action = 'DATASET_VERSION_DRAFT_CREATED'
				  AND object_type = 'DatasetVersion'
				  AND object_id = ?
				""", datasetVersionId.toString()));
		var datasetVersionRowsBeforeCurrentAdmissionRead = count(
				"SELECT count(*) FROM dataset_version WHERE project_id = ?", PROJECT_ID);
		var memberSessionRowsBeforeCurrentAdmissionRead = count(
				"SELECT count(*) FROM dataset_member_session WHERE project_id = ?", PROJECT_ID);
		var memberStreamRowsBeforeCurrentAdmissionRead = count(
				"SELECT count(*) FROM dataset_member_stream WHERE project_id = ?", PROJECT_ID);
		var auditEventsBeforeDatasetRead = count("SELECT count(*) FROM audit_event");
		var snapshotQualityReviewVersion = this.jdbcTemplate.queryForObject(
				"SELECT quality_review_version FROM dataset_member_stream WHERE dataset_version_id = ?",
				Long.class,
				datasetVersionId);
		this.mockMvc.perform(get("/api/v1/projects/{projectId}/dataset-versions", PROJECT_ID)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.items[0].dataset_version_id").value(datasetVersionId.toString()))
				.andExpect(jsonPath("$.items[0].status").value("DRAFT"))
				.andExpect(jsonPath("$.next_cursor").isEmpty());
		this.mockMvc.perform(get("/api/v1/projects/{projectId}/dataset-versions", PROJECT_ID)
				.header("Authorization", "Bearer " + MEMBER_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].dataset_version_id").value(datasetVersionId.toString()));
		this.mockMvc.perform(get("/api/v1/projects/{projectId}/dataset-versions", PROJECT_ID)
				.param("limit", "0")
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isBadRequest())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.error_code").value("INVALID_DATASET_VERSION_REQUEST"));
		this.mockMvc.perform(get("/api/v1/projects/{projectId}/dataset-versions?cursor=not-a-cursor", PROJECT_ID)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error_code").value("INVALID_CURSOR"));
		var overflowingCursor = Base64.getUrlEncoder().withoutPadding().encodeToString((
				"v1|dataset-version|9223372036854775807|2147483647|" + UUID.randomUUID())
				.getBytes(StandardCharsets.UTF_8));
		this.mockMvc.perform(get("/api/v1/projects/{projectId}/dataset-versions", PROJECT_ID)
				.param("cursor", overflowingCursor)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error_code").value("INVALID_CURSOR"));
		this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + MEMBER_TOKEN))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.dataset_version_id").value(datasetVersionId.toString()))
				.andExpect(jsonPath("$.members[0].capture_session_id").value(captureSessionId.toString()))
				.andExpect(jsonPath("$.members[0].candidate_index").value(0))
				.andExpect(jsonPath("$.members[0].streams[0].clip_asset_id").value(clipAssetId.toString()))
				.andExpect(jsonPath("$.members[0].streams[0].quality_review_decision").value("QUALIFIED"))
				.andExpect(jsonPath("$.members[0].streams[0].post_cleaning_outcome").value("PASS"))
				.andExpect(jsonPath("$.members[0].streams[0].annotation_document_sha256").exists())
				.andExpect(jsonPath("$.members[0].streams[0].media_url").doesNotExist());
		this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}/current-admission",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.dataset_version_id").value(datasetVersionId.toString()))
				.andExpect(jsonPath("$.all_members_currently_admission_eligible").value(true))
				.andExpect(jsonPath("$.members.length()").value(1))
				.andExpect(jsonPath("$.members[0].capture_session_id").value(captureSessionId.toString()))
				.andExpect(jsonPath("$.members[0].candidate_index").value(0))
				.andExpect(jsonPath("$.members[0].current_admission.eligible").value(true))
				.andExpect(jsonPath("$.members[0].evidence_changes").isEmpty())
				.andExpect(jsonPath("$.build_readiness.ready_for_build").value(false))
				.andExpect(jsonPath("$.build_readiness.immutable_evidence_still_current").value(true))
				.andExpect(jsonPath("$.build_readiness.uniform_label_schema_digest")
						.value(CURRENT_LABEL_SCHEMA_DIGEST))
				.andExpect(jsonPath("$.build_readiness.uniform_cleaning_rule_set_digest").exists())
				.andExpect(jsonPath("$.build_readiness.dataset_split_plan_id").isEmpty())
				.andExpect(jsonPath("$.build_readiness.blocking_reasons.length()").value(5))
				.andExpect(jsonPath("$.build_readiness.blocking_reasons[0]")
						.value("SELECTION_RULE_NOT_FIXED"))
				.andExpect(jsonPath("$.build_readiness.blocking_reasons[3]").value("SPLIT_PLAN_NOT_FIXED"));
		this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}/current-admission",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + MEMBER_TOKEN))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.all_members_currently_admission_eligible").value(true));
		assertEquals(datasetVersionRowsBeforeCurrentAdmissionRead,
				count("SELECT count(*) FROM dataset_version WHERE project_id = ?", PROJECT_ID));
		assertEquals(memberSessionRowsBeforeCurrentAdmissionRead,
				count("SELECT count(*) FROM dataset_member_session WHERE project_id = ?", PROJECT_ID));
		assertEquals(memberStreamRowsBeforeCurrentAdmissionRead,
				count("SELECT count(*) FROM dataset_member_stream WHERE project_id = ?", PROJECT_ID));
		this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}",
						PROJECT_ID,
						UUID.randomUUID())
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error_code").value("DATASET_VERSION_NOT_FOUND"));
		this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}/current-admission",
						PROJECT_ID,
						UUID.randomUUID())
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isNotFound())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.error_code").value("DATASET_VERSION_NOT_FOUND"));
		assertEquals(auditEventsBeforeDatasetRead, count("SELECT count(*) FROM audit_event"));
		createDatasetDraft(ADMIN_TOKEN, captureSessionId, datasetIdempotencyKey)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.dataset_version_id").value(datasetVersionId.toString()));
		assertEquals(1, count("SELECT count(*) FROM dataset_version WHERE project_id = ?", PROJECT_ID));
		createDatasetDraft(MEMBER_TOKEN, captureSessionId, "dataset-version-member-" + UUID.randomUUID())
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("PROJECT_ACCESS_DENIED"));

		this.jdbcTemplate.update("""
				UPDATE quality_review
				SET version = version + 1,
				    reviewed_at = ?
				WHERE project_id = ? AND clip_asset_id = ?
				""", Timestamp.from(BASE_TIME.plusSeconds(30)), PROJECT_ID, clipAssetId);

		this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/capture-sessions/{captureSessionId}/dataset-admission-preview",
						PROJECT_ID,
						captureSessionId)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.eligible").value(false))
				.andExpect(jsonPath("$.streams[0].eligible").value(false))
				.andExpect(jsonPath("$.streams[0].blocking_reasons[0]")
						.value("QUALITY_REVIEW_CHANGED_AFTER_POST_CLEANING"));
		this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}/current-admission",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + MEMBER_TOKEN))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.all_members_currently_admission_eligible").value(false))
				.andExpect(jsonPath("$.members[0].current_admission.eligible").value(false))
				.andExpect(jsonPath("$.members[0].evidence_changes[0].scope").value("STREAM"))
				.andExpect(jsonPath("$.members[0].evidence_changes[0].camera_stream_id").isNotEmpty())
				.andExpect(jsonPath("$.members[0].evidence_changes[0].change_codes[0]")
						.value("STREAM_QUALITY_REVIEW_VERSION_CHANGED"))
				.andExpect(jsonPath("$.build_readiness.immutable_evidence_still_current").value(false))
				.andExpect(jsonPath("$.build_readiness.blocking_reasons[5]")
						.value("CURRENT_ADMISSION_NOT_ELIGIBLE"))
				.andExpect(jsonPath("$.build_readiness.blocking_reasons[6]")
						.value("SNAPSHOT_EVIDENCE_CHANGED"));
		this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + MEMBER_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.members[0].streams[0].quality_review_version")
						.value(snapshotQualityReviewVersion));

		recheck(MEMBER_TOKEN, revisionId)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("PROJECT_ACCESS_DENIED"));
		var rechecked = recheck(ADMIN_TOKEN, revisionId)
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.outcome").value("PASS"))
				.andReturn().getResponse().getContentAsString();
		var recheckedResultId = com.jayway.jsonpath.JsonPath.<String>read(rechecked, "$.cleaning_result_id");
		recheck(ADMIN_TOKEN, revisionId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cleaning_result_id").value(recheckedResultId));
		assertEquals(2, count("SELECT count(*) FROM cleaning_run WHERE annotation_revision_id = ?", revisionId));
		assertEquals(2, count("""
				SELECT count(*) FROM audit_event
				WHERE action = 'POST_ANNOTATION_CLEANING_COMPLETED'
				  AND details_json ->> 'annotation_revision_id' = ?
				""", revisionId.toString()));
		var auditEventsBeforeRecoveredCurrentAdmissionRead = count("SELECT count(*) FROM audit_event");
		this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}/current-admission",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.all_members_currently_admission_eligible").value(true))
				.andExpect(jsonPath("$.members[0].current_admission.eligible").value(true))
				.andExpect(jsonPath("$.members[0].evidence_changes[0].scope").value("STREAM"))
				.andExpect(jsonPath("$.members[0].evidence_changes[0].change_codes[0]")
						.value("STREAM_QUALITY_REVIEW_VERSION_CHANGED"))
				.andExpect(jsonPath("$.members[0].evidence_changes[0].change_codes[1]")
						.value("STREAM_CLEANING_RESULT_CHANGED"))
				.andExpect(jsonPath("$.members[0].evidence_changes[0].change_codes[2]")
						.value("STREAM_CLEANING_RUN_CHANGED"));
		assertEquals(auditEventsBeforeRecoveredCurrentAdmissionRead, count("SELECT count(*) FROM audit_event"));

		this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/capture-sessions/{captureSessionId}/dataset-admission-preview",
						PROJECT_ID,
						captureSessionId)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.eligible").value(true))
				.andExpect(jsonPath("$.streams[0].eligible").value(true))
				.andExpect(jsonPath("$.streams[0].blocking_reasons").isEmpty());
	}

	@Test
	void approvedRevisionWithoutVerifiedDurationIsWarningRatherThanImplicitPass() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		var taskResponse = create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-warning-" + UUID.randomUUID())
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var taskId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(taskResponse, "$.annotation_task_id"));
		var revisionResponse = submit(MEMBER_TOKEN, taskId, "annotation-revision-warning-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":10,\"end_ms\":20,\"label\":\"prepare_workspace\"}]}")
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var revisionId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
				revisionResponse, "$.annotation_revision_id"));

		review(ADMIN_TOKEN, revisionId, "annotation-review-warning-" + UUID.randomUUID(),
				"{\"decision\":\"APPROVED\"}")
				.andExpect(status().isCreated());

		this.mockMvc.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].status").value("COMPLETED"))
				.andExpect(jsonPath("$.items[0].annotation_revision.post_cleaning_result.outcome")
						.value("WARNING"))
				.andExpect(jsonPath(
						"$.items[0].annotation_revision.post_cleaning_result.reason_codes[0]")
						.value("VERIFIED_MEDIA_DURATION_UNAVAILABLE"));
	}

	@Test
	void zeroDurationFactIsPersistedAsARejectInsteadOfFailingTheReview() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		var taskResponse = create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-zero-duration-" + UUID.randomUUID())
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var taskId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(taskResponse, "$.annotation_task_id"));
		var revisionResponse = submit(
				MEMBER_TOKEN,
				taskId,
				"annotation-revision-zero-duration-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":0,\"end_ms\":10,\"label\":\"prepare_workspace\"}]}")
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var revisionId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
				revisionResponse, "$.annotation_revision_id"));
		insertVerifiedMediaReport(PROJECT_ID, clipAssetId, 0);

		review(ADMIN_TOKEN, revisionId, "annotation-review-zero-duration-" + UUID.randomUUID(),
				"{\"decision\":\"APPROVED\"}")
				.andExpect(status().isCreated());

		this.mockMvc.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].status").value("COMPLETED"))
				.andExpect(jsonPath("$.items[0].annotation_revision.post_cleaning_result.outcome")
						.value("REJECT"))
				.andExpect(jsonPath(
						"$.items[0].annotation_revision.post_cleaning_result.reason_codes[0]")
						.value("VERIFIED_MEDIA_DURATION_INVALID"));
		assertEquals(0L, this.jdbcTemplate.queryForObject(
				"SELECT verified_duration_microseconds FROM cleaning_run WHERE annotation_revision_id = ?",
				Long.class,
				revisionId));
	}

	@Test
	void changedAnnotationDocumentIsRejectedByDigestRevalidation() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		insertVerifiedMediaReport(PROJECT_ID, clipAssetId, 1_000_000L);
		var taskResponse = create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-digest-recheck-" + UUID.randomUUID())
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var taskId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(taskResponse, "$.annotation_task_id"));
		var revisionResponse = submit(
				MEMBER_TOKEN,
				taskId,
				"annotation-revision-digest-recheck-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":10,\"end_ms\":20,\"label\":\"prepare_workspace\"}]}")
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var revisionId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
				revisionResponse, "$.annotation_revision_id"));
		this.jdbcTemplate.update("""
				UPDATE annotation_revision
				SET annotation_document = jsonb_set(
				    annotation_document,
				    '{segments,0,end_ms}',
				    '30'::jsonb)
				WHERE annotation_revision_id = ?
				""", revisionId);

		review(ADMIN_TOKEN, revisionId, "annotation-review-digest-recheck-" + UUID.randomUUID(),
				"{\"decision\":\"APPROVED\"}")
				.andExpect(status().isCreated());

		this.mockMvc.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].annotation_revision.post_cleaning_result.outcome")
						.value("REJECT"))
				.andExpect(jsonPath(
						"$.items[0].annotation_revision.post_cleaning_result.reason_codes[0]")
						.value("ANNOTATION_DOCUMENT_DIGEST_MISMATCH"));
	}

	@Test
	void latestVerifiedMediaReportIsSelectedAndFrozenDeterministically() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		insertVerifiedMediaReport(PROJECT_ID, clipAssetId, 100_000L, BASE_TIME);
		var latestReportId = insertVerifiedMediaReport(
				PROJECT_ID,
				clipAssetId,
				1_000_000L,
				BASE_TIME.plusSeconds(10),
				"d".repeat(64));
		var taskResponse = create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-latest-media-" + UUID.randomUUID())
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var taskId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(taskResponse, "$.annotation_task_id"));
		var revisionResponse = submit(
				MEMBER_TOKEN,
				taskId,
				"annotation-revision-latest-media-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":100,\"end_ms\":500,\"label\":\"prepare_workspace\"}]}")
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var revisionId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
				revisionResponse, "$.annotation_revision_id"));

		review(ADMIN_TOKEN, revisionId, "annotation-review-latest-media-" + UUID.randomUUID(),
				"{\"decision\":\"APPROVED\"}")
				.andExpect(status().isCreated());

		assertEquals(latestReportId, this.jdbcTemplate.queryForObject(
				"SELECT media_report_id FROM cleaning_run WHERE annotation_revision_id = ?",
				UUID.class,
				revisionId));
		assertEquals(1_000_000L, this.jdbcTemplate.queryForObject(
				"SELECT verified_duration_microseconds FROM cleaning_run WHERE annotation_revision_id = ?",
				Long.class,
				revisionId));
	}

	@Test
	void submittingAdministratorCannotReviewTheirOwnRevision() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		var taskResponse = create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.ADMIN_ACTOR,
				"annotation-task-self-review-" + UUID.randomUUID())
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var taskId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(taskResponse, "$.annotation_task_id"));
		var revisionResponse = submit(ADMIN_TOKEN, taskId, "annotation-revision-self-review-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":1,\"end_ms\":2,\"label\":\"retrieve_medicine\"}]}")
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var revisionId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(revisionResponse, "$.annotation_revision_id"));

		review(ADMIN_TOKEN, revisionId, "annotation-review-self-" + UUID.randomUUID(),
				"{\"decision\":\"APPROVED\"}")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("ANNOTATION_REVIEW_SELF_REVIEW_FORBIDDEN"));
	}

	@Test
	void rejectedReviewReturnsTaskForAnImmutableNextRevision() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		var taskResponse = create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-rework-" + UUID.randomUUID())
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var taskId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(taskResponse, "$.annotation_task_id"));
		var firstRevisionResponse = submit(MEMBER_TOKEN, taskId, "annotation-revision-first-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":100,\"end_ms\":200,\"label\":\"cleanup\"}]}")
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var firstRevisionId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
				firstRevisionResponse, "$.annotation_revision_id"));

		review(ADMIN_TOKEN, firstRevisionId, "annotation-review-reject-" + UUID.randomUUID(),
				"{\"decision\":\"REJECTED\"}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.decision").value("REJECTED"));
		assertEquals("ASSIGNED", this.jdbcTemplate.queryForObject(
				"SELECT task_status FROM annotation_task WHERE annotation_task_id = ?", String.class, taskId));
		this.mockMvc.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID)
				.header("Authorization", "Bearer " + MEMBER_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].can_submit").value(true))
				.andExpect(jsonPath("$.items[0].annotation_revision.review.decision").value("REJECTED"))
				.andExpect(jsonPath("$.progress.awaiting_submission").value(0))
				.andExpect(jsonPath("$.progress.rework_required").value(1));

		submit(MEMBER_TOKEN, taskId, "annotation-revision-second-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":120,\"end_ms\":360,\"label\":\"retrieve_medicine\"}]}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.revision_number").value(2));
		assertEquals(2, count("SELECT count(*) FROM annotation_revision WHERE annotation_task_id = ?", taskId));
		assertEquals(1, count("SELECT count(*) FROM annotation_review WHERE annotation_revision_id = ?", firstRevisionId));
		assertEquals(0, count("SELECT count(*) FROM cleaning_run WHERE annotation_revision_id = ?", firstRevisionId));
	}

	@Test
	void membersOnlySeeTasksAssignedToThemWhileAdministratorsSeeTheProjectQueue() throws Exception {
		var assignedToMember = insertClipAsset(PROJECT_ID, "INGESTED", false);
		var assignedToAdmin = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, assignedToMember, "QUALIFIED", "MEETS_REQUIREMENTS");
		insertQualityReview(PROJECT_ID, assignedToAdmin, "QUALIFIED", "MEETS_REQUIREMENTS");
		create(ADMIN_TOKEN, assignedToMember, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-member-list-" + UUID.randomUUID()).andExpect(status().isCreated());
		create(ADMIN_TOKEN, assignedToAdmin, ProjectMembershipTestData.ADMIN_ACTOR,
				"annotation-task-admin-list-" + UUID.randomUUID()).andExpect(status().isCreated());

		this.mockMvc.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID)
				.header("Authorization", "Bearer " + MEMBER_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].assignee_actor_id").value(ProjectMembershipTestData.MEMBER_ACTOR))
				.andExpect(jsonPath("$.progress.scope").value("ASSIGNEE"))
				.andExpect(jsonPath("$.progress.total").value(1));
		this.mockMvc.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.progress.scope").value("PROJECT"))
				.andExpect(jsonPath("$.progress.total").value(2))
				.andExpect(jsonPath("$.progress.awaiting_submission").value(2));
	}

	private org.springframework.test.web.servlet.ResultActions create(
			String token,
			UUID clipAssetId,
			String assigneeActorId,
			String idempotencyKey) throws Exception {
		return this.mockMvc.perform(post(
						"/api/v1/projects/{projectId}/clip-assets/{clipAssetId}/annotation-tasks",
						PROJECT_ID,
						clipAssetId)
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", "annotation-task-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"assignee_actor_id\":\"" + assigneeActorId + "\"}"));
	}

	private org.springframework.test.web.servlet.ResultActions submit(
			String token,
			UUID taskId,
			String idempotencyKey,
			String body) throws Exception {
		return this.mockMvc.perform(post(
						"/api/v1/projects/{projectId}/annotation-tasks/{taskId}/revisions",
						PROJECT_ID,
						taskId)
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", "annotation-revision-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private org.springframework.test.web.servlet.ResultActions review(
			String token,
			UUID revisionId,
			String idempotencyKey,
			String body) throws Exception {
		return this.mockMvc.perform(post(
						"/api/v1/projects/{projectId}/annotation-revisions/{revisionId}/review",
						PROJECT_ID,
						revisionId)
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", "annotation-review-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private org.springframework.test.web.servlet.ResultActions recheck(String token, UUID revisionId) throws Exception {
		return this.mockMvc.perform(post(
						"/api/v1/projects/{projectId}/annotation-revisions/{revisionId}/post-cleaning-rechecks",
						PROJECT_ID,
						revisionId)
				.header("Authorization", "Bearer " + token)
				.header("X-Request-Id", "annotation-recheck-" + UUID.randomUUID()));
	}

	private org.springframework.test.web.servlet.ResultActions createDatasetDraft(
			String token,
			UUID captureSessionId,
			String idempotencyKey) throws Exception {
		return createDatasetDraft(token, List.of(captureSessionId), idempotencyKey);
	}

	private org.springframework.test.web.servlet.ResultActions createDatasetSplitPlan(
			String token,
			UUID datasetVersionId,
			int holdoutCount,
			String idempotencyKey) throws Exception {
		return this.mockMvc.perform(post(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}/split-plans",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", "dataset-split-request-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"holdout_count\":" + holdoutCount + "}"));
	}

	private org.springframework.test.web.servlet.ResultActions createDatasetBuildSpecification(
			String token,
			UUID datasetVersionId,
			String idempotencyKey) throws Exception {
		return this.mockMvc.perform(post(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}/build-specifications",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", "dataset-build-spec-request-" + UUID.randomUUID()));
	}

	private org.springframework.test.web.servlet.ResultActions startDatasetBuild(
			String token,
			UUID datasetVersionId,
			String idempotencyKey) throws Exception {
		return this.mockMvc.perform(post(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}/build",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", "dataset-build-start-request-" + UUID.randomUUID()));
	}

	private org.springframework.test.web.servlet.ResultActions createDatasetBuildSnapshot(
			String token,
			UUID datasetVersionId,
			String idempotencyKey) throws Exception {
		return this.mockMvc.perform(post(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}/build-snapshots",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", "dataset-build-snapshot-request-" + UUID.randomUUID()));
	}

	private org.springframework.test.web.servlet.ResultActions validateDatasetVersion(
			String token,
			UUID datasetVersionId,
			String idempotencyKey) throws Exception {
		return this.mockMvc.perform(post(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}/validations",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", "dataset-validation-request-" + UUID.randomUUID()));
	}

	private org.springframework.test.web.servlet.ResultActions freezeDatasetVersion(
			String token,
			UUID datasetVersionId,
			String idempotencyKey) throws Exception {
		return this.mockMvc.perform(post(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}/freezes",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", "dataset-freeze-request-" + UUID.randomUUID()));
	}

	private org.springframework.test.web.servlet.ResultActions requestDatasetExport(
			String token,
			UUID datasetVersionId,
			String idempotencyKey,
			String expiresAt) throws Exception {
		return this.mockMvc.perform(post(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}/exports",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", "dataset-export-request-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"purpose":"MODEL_TRAINING","expires_at":"%s"}
						""".formatted(expiresAt)));
	}

	private org.springframework.test.web.servlet.ResultActions getDatasetExportApprovalReadiness(
			String token,
			UUID datasetVersionId) throws Exception {
		return this.mockMvc.perform(get(
						"/api/v1/projects/{projectId}/dataset-versions/{datasetVersionId}/exports/approval-readiness",
						PROJECT_ID,
						datasetVersionId)
				.header("Authorization", "Bearer " + token));
	}

	private org.springframework.test.web.servlet.ResultActions createDatasetDraft(
			String token,
			List<UUID> captureSessionIds,
			String idempotencyKey) throws Exception {
		var captureSessionIdsJson = String.join(",", captureSessionIds.stream()
				.map(captureSessionId -> "\"" + captureSessionId + "\"")
				.toList());
		return this.mockMvc.perform(post("/api/v1/projects/{projectId}/dataset-versions", PROJECT_ID)
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", "dataset-version-request-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"display_name\":\"训练候选集 01\",\"capture_session_ids\":["
						+ captureSessionIdsJson + "]}"));
	}

	private UUID createEligibleDatasetSession(Instant createdAt) throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		var captureSessionId = CaptureMediaProjectionTestData.bindEligible(
				this.jdbcTemplate, PROJECT_ID, clipAssetId, createdAt).captureSessionId();
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		insertVerifiedMediaReport(PROJECT_ID, clipAssetId, 1_000_000L);
		var taskResponse = create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-dataset-multi-" + UUID.randomUUID())
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var taskId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(taskResponse, "$.annotation_task_id"));
		var revisionResponse = submit(MEMBER_TOKEN, taskId,
				"annotation-revision-dataset-multi-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":120,\"end_ms\":780,\"label\":\"retrieve_medicine\"}]}")
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var revisionId = UUID.fromString(
				com.jayway.jsonpath.JsonPath.read(revisionResponse, "$.annotation_revision_id"));
		review(ADMIN_TOKEN, revisionId, "annotation-review-dataset-multi-" + UUID.randomUUID(),
				"{\"decision\":\"APPROVED\"}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.decision").value("APPROVED"));
		return captureSessionId;
	}

	private void insertQualityReview(UUID projectId, UUID clipAssetId, String decision, String reasonCode) {
		this.jdbcTemplate.update("""
				INSERT INTO quality_review (
				    quality_review_id, project_id, clip_asset_id, decision, reason_code,
				    reviewed_by, reviewed_at, version)
				VALUES (?, ?, ?, ?, ?, ?, ?, 0)
				""",
				UUID.randomUUID(), projectId, clipAssetId, decision, reasonCode,
				ProjectMembershipTestData.MEMBER_ACTOR, Timestamp.from(BASE_TIME));
	}

	private UUID insertClipAsset(UUID projectId, String status, boolean deleted) {
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, projectId);
		var batchId = UUID.randomUUID();
		var uploadFileId = UUID.randomUUID();
		var clientFileId = UUID.randomUUID();
		var clipAssetId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO upload_batch (
				    batch_id, project_id, idempotency_key, request_fingerprint, device_id,
				    initiated_by_actor_id, file_count, total_size_bytes, manifest_sha256,
				    status, expires_at, created_at, updated_at, verified_manifest_sha256, completed_at)
				VALUES (?, ?, ?, ?, 'synthetic-annotation-device', ?, 1, 1234, ?,
				        'COMPLETED', ?, ?, ?, ?, ?)
				""",
				batchId, projectId, "annotation-batch-" + batchId, SHA256,
				ProjectMembershipTestData.ADMIN_ACTOR, SHA256,
				Timestamp.from(BASE_TIME.plusSeconds(3600)), Timestamp.from(BASE_TIME),
				Timestamp.from(BASE_TIME), SHA256, Timestamp.from(BASE_TIME));
		this.jdbcTemplate.update("""
				INSERT INTO upload_file (
				    upload_file_id, batch_id, client_file_id, idempotency_key, request_fingerprint,
				    file_kind, content_type, size_bytes, sha256, part_size_bytes, total_parts,
				    status, created_at, updated_at, storage_bucket, object_key, storage_upload_id,
				    multipart_created_at, completion_started_at, completion_finished_at,
				    verified_sha256, completed_storage_etag)
				VALUES (?, ?, ?, ?, ?, 'VIDEO', 'video/mp4', 1234, ?, 8388608, 1,
				        'UPLOADED', ?, ?, 'synthetic-bucket', ?, ?, ?, ?, ?, ?, 'synthetic-etag')
				""",
				uploadFileId, batchId, clientFileId, "annotation-file-" + uploadFileId, SHA256,
				SHA256, Timestamp.from(BASE_TIME), Timestamp.from(BASE_TIME),
				"objects/" + uploadFileId, "multipart-" + uploadFileId, Timestamp.from(BASE_TIME),
				Timestamp.from(BASE_TIME), Timestamp.from(BASE_TIME), SHA256);
		this.jdbcTemplate.update("""
				INSERT INTO clip_asset (
				    clip_asset_id, project_id, batch_id, source_upload_file_id, client_file_id,
				    content_type, size_bytes, sha256, storage_bucket, object_key, storage_etag,
				    asset_status, deleted_at, deleted_by, delete_reason, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, ?, 'video/mp4', 1234, ?, 'synthetic-bucket', ?, 'synthetic-etag',
				        ?, ?, ?, ?, ?, ?, 0)
				""",
				clipAssetId, projectId, batchId, uploadFileId, clientFileId, SHA256,
				"objects/" + clipAssetId, status,
				deleted ? Timestamp.from(BASE_TIME) : null,
				deleted ? ProjectMembershipTestData.ADMIN_ACTOR : null,
				deleted ? "synthetic deletion" : null,
				Timestamp.from(BASE_TIME), Timestamp.from(BASE_TIME));
		return clipAssetId;
	}

	private UUID insertVerifiedMediaReport(UUID projectId, UUID clipAssetId, long durationMicroseconds) {
		return insertVerifiedMediaReport(projectId, clipAssetId, durationMicroseconds, BASE_TIME);
	}

	private UUID insertVerifiedMediaReport(
			UUID projectId,
			UUID clipAssetId,
			long durationMicroseconds,
			Instant createdAt) {
		return insertVerifiedMediaReport(
				projectId, clipAssetId, durationMicroseconds, createdAt, MEDIA_RECIPE_DIGEST);
	}

	private UUID insertVerifiedMediaReport(
			UUID projectId,
			UUID clipAssetId,
			long durationMicroseconds,
			Instant createdAt,
			String recipeDigest) {
		var mediaJobId = UUID.randomUUID();
		var mediaReportId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO media_job (
				    media_job_id, project_id, clip_asset_id, job_type, input_digest, input_size_bytes,
				    content_type, status, idempotency_key, request_fingerprint, created_at, updated_at,
				    version, probe_profile_key, recipe_digest)
				VALUES (?, ?, ?, 'MEDIA_PROBE', ?, 1234, 'video/mp4', 'SUCCEEDED', ?, ?, ?, ?, 0,
				        'media-probe-baseline-v1', ?)
				""",
				mediaJobId, projectId, clipAssetId, SHA256, "annotation-media-job-" + mediaJobId,
				SHA256, Timestamp.from(createdAt), Timestamp.from(createdAt), recipeDigest);
		this.jdbcTemplate.update("""
				INSERT INTO media_report (
				    media_report_id, media_job_id, project_id, clip_asset_id, report_schema_version,
				    input_digest, recipe_digest, software_digest, container_format, duration_microseconds,
				    video_stream_count, primary_video_stream_index, video_codec, width, height,
				    time_base_numerator, time_base_denominator, variable_frame_rate, audio_stream_count,
				    decode_verified, input_digest_verified, warning_codes, created_at)
				VALUES (?, ?, ?, ?, 'media-report.v1', ?, ?,
				        'sha256:207391c770bee4a8dfe3530ef5f31af07904950f9bc2dcb822fedb086222164a',
				        'mov,mp4,m4a,3gp,3g2,mj2', ?, 1, 0, 'h264', 640, 360, 1, 90000, false, 0,
				        true, true, '[]'::jsonb, ?)
				""",
				mediaReportId, mediaJobId, projectId, clipAssetId, SHA256, recipeDigest, durationMicroseconds,
				Timestamp.from(createdAt));
		return mediaReportId;
	}

	private void insertDatasetDraft(UUID projectId, UUID datasetVersionId, String displayName, Instant createdAt) {
		this.jdbcTemplate.update("""
				INSERT INTO dataset_version (
				    dataset_version_id,
				    project_id,
				    display_name,
				    dataset_version_status,
				    created_by,
				    idempotency_key,
				    request_fingerprint,
				    created_at,
				    row_version
				)
				VALUES (?, ?, ?, 'DRAFT', 'test-admin', ?, ?, ?, 0)
				""",
				datasetVersionId,
				projectId,
				displayName,
				"dataset-query-seed-" + datasetVersionId,
				SHA256,
				Timestamp.from(createdAt));
	}

	private int count(String sql, Object... arguments) {
		return this.jdbcTemplate.queryForObject(sql, Integer.class, arguments);
	}

	private void clearState(UUID projectId) {
		this.jdbcTemplate.update("DELETE FROM dataset_export WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("""
				UPDATE dataset_version
				SET dataset_version_status = 'VALIDATING',
				    freeze_approval_request_id = NULL,
				    freeze_approval_decision_id = NULL,
				    frozen_by = NULL,
				    freeze_idempotency_key = NULL,
				    freeze_request_fingerprint = NULL,
				    frozen_at = NULL
				WHERE project_id = ? AND dataset_version_status = 'FROZEN'
				""", projectId);
		this.jdbcTemplate.update("DELETE FROM approval_decision WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM approval_request WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM dataset_version_validation WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("""
				UPDATE dataset_version
				SET dataset_version_status = 'BUILDING', build_snapshot_id = NULL
				WHERE project_id = ? AND dataset_version_status = 'VALIDATING'
				""", projectId);
		this.jdbcTemplate.update("DELETE FROM dataset_build_snapshot WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM dataset_build_specification WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM dataset_split_assignment WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM dataset_split_plan WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM dataset_member_stream WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM dataset_member_session WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM dataset_version WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("""
				DELETE FROM camera_stream
				WHERE capture_session_id IN (
				    SELECT capture_session_id FROM capture_session WHERE project_id = ?)
				""", projectId);
		this.jdbcTemplate.update("""
				DELETE FROM camera_set_snapshot_member
				WHERE camera_set_snapshot_id IN (
				    SELECT snapshot.camera_set_snapshot_id
				    FROM camera_set_snapshot snapshot
				    JOIN capture_session session
				      ON session.capture_session_id = snapshot.capture_session_id
				    WHERE session.project_id = ?)
				""", projectId);
		this.jdbcTemplate.update("""
				DELETE FROM camera_set_snapshot
				WHERE capture_session_id IN (
				    SELECT capture_session_id FROM capture_session WHERE project_id = ?)
				""", projectId);
		this.jdbcTemplate.update("DELETE FROM capture_session WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM cleaning_result WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM cleaning_run WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM annotation_review WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM annotation_revision WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM annotation_task WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM quality_review WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM media_report WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM media_job WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM clip_asset WHERE project_id = ?", projectId);
		this.jdbcTemplate.update(
				"DELETE FROM upload_file WHERE batch_id IN (SELECT batch_id FROM upload_batch WHERE project_id = ?)",
				projectId);
		this.jdbcTemplate.update("DELETE FROM upload_batch WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("""
				DELETE FROM camera_set_member
				WHERE camera_set_id IN (SELECT camera_set_id FROM camera_set WHERE project_id = ?)
				""", projectId);
		this.jdbcTemplate.update("DELETE FROM camera_set WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM camera_channel WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM membership WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM project WHERE project_id = ?", projectId);
	}

}
