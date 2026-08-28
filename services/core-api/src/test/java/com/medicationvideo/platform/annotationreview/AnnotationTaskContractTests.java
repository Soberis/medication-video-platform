package com.medicationvideo.platform.annotationreview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

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

	private static final String CURRENT_LABEL_SCHEMA_DIGEST = "305e92dcd10343e6c97b5780e59485b63183fadad7e9b87bc85db4bb037aa203";

	private static final String MEDIA_RECIPE_DIGEST = "8900937958d0fb81a9c9bf2d9e81ac3e57f35d838d5a4da52b58ccf0f5cd5765";

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

		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID).header("Authorization",
					"Bearer " + MEMBER_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].clip_asset_id").value(clipAssetId.toString()))
			.andExpect(jsonPath("$.items[0].can_select_primary").value(false))
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
	void globalMemberCannotCreateAnAdministratorTaskEvenIfItsProjectMembershipWasMisconfigured() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		this.jdbcTemplate.update("""
				UPDATE membership
				SET role = 'ADMIN',
				    updated_by = ?,
				    updated_at = ?,
				    row_version = row_version + 1
				WHERE project_id = ? AND actor_id = ?
				""", ProjectMembershipTestData.ADMIN_ACTOR, Timestamp.from(BASE_TIME.plusSeconds(1)), PROJECT_ID,
				ProjectMembershipTestData.MEMBER_ACTOR);

		create(MEMBER_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-member-admin-membership-" + UUID.randomUUID())
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error_code").value("PROJECT_ACCESS_DENIED"));

		assertEquals(0, count("SELECT count(*) FROM annotation_task WHERE clip_asset_id = ?", clipAssetId));
	}

	@Test
	void projectMemberReadsTheImmutablePrimaryStepLabelSchema() throws Exception {
		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/annotation-label-schema", PROJECT_ID).header("Authorization",
					"Bearer " + MEMBER_TOKEN))
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

		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/annotation-label-schema", UUID.randomUUID())
				.header("Authorization", "Bearer " + MEMBER_TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error_code").value("PROJECT_NOT_FOUND"));
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
			.andReturn()
			.getResponse()
			.getContentAsString();
		var taskId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(taskResponse, "$.annotation_task_id"));
		var idempotencyKey = "annotation-revision-submit-" + UUID.randomUUID();

		var response = submit(MEMBER_TOKEN, taskId, idempotencyKey, """
				{"segments":[{"start_ms":120,"end_ms":780,"label":"retrieve_medicine"}],"note":"合成验证标注"}
				""").andExpect(status().isCreated())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.annotation_task_id").value(taskId.toString()))
			.andExpect(jsonPath("$.clip_asset_id").value(clipAssetId.toString()))
			.andExpect(jsonPath("$.revision_number").value(1))
			.andExpect(jsonPath("$.annotation_schema_version").value("annotation-document.v1"))
			.andExpect(jsonPath("$.label_schema_id").value(CURRENT_LABEL_SCHEMA_ID))
			.andExpect(jsonPath("$.label_schema_digest").value(CURRENT_LABEL_SCHEMA_DIGEST))
			.andReturn()
			.getResponse()
			.getContentAsString();
		var revisionId = com.jayway.jsonpath.JsonPath.<String>read(response, "$.annotation_revision_id");

		submit(MEMBER_TOKEN, taskId, idempotencyKey, """
				{"segments":[{"start_ms":120,"end_ms":780,"label":"retrieve_medicine"}],"note":"合成验证标注"}
				""").andExpect(status().isCreated()).andExpect(jsonPath("$.annotation_revision_id").value(revisionId));
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
	void candidateRemainsCandidateWhenHumanCreatesAFormalRevision() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		var candidateId = insertHandPoseCandidate(PROJECT_ID, clipAssetId);
		var candidateJobId = this.jdbcTemplate.queryForObject(
				"SELECT ai_job_id FROM candidate_annotation WHERE candidate_annotation_id = ?", UUID.class,
				candidateId);
		var candidateTimestamp = this.jdbcTemplate.queryForObject(
				"SELECT frame_timestamp_ms FROM candidate_annotation WHERE candidate_annotation_id = ?", Long.class,
				candidateId);

		var taskResponse = create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-candidate-boundary-" + UUID.randomUUID())
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		var taskId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(taskResponse, "$.annotation_task_id"));

		submit(MEMBER_TOKEN, taskId, "annotation-revision-candidate-boundary-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":120,\"end_ms\":780,\"label\":\"retrieve_medicine\"}]}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.revision_number").value(1));

		assertEquals(1,
				count("SELECT count(*) FROM candidate_annotation WHERE candidate_annotation_id = ?", candidateId));
		assertEquals(candidateJobId,
				this.jdbcTemplate.queryForObject(
						"SELECT ai_job_id FROM candidate_annotation WHERE candidate_annotation_id = ?", UUID.class,
						candidateId));
		assertEquals(candidateTimestamp,
				this.jdbcTemplate.queryForObject(
						"SELECT frame_timestamp_ms FROM candidate_annotation WHERE candidate_annotation_id = ?",
						Long.class, candidateId));
		assertEquals(1, count("SELECT count(*) FROM annotation_revision WHERE annotation_task_id = ?", taskId));
		assertEquals(0,
				count("SELECT count(*) FROM annotation_review WHERE annotation_revision_id IN "
						+ "(SELECT annotation_revision_id FROM annotation_revision WHERE annotation_task_id = ?)",
						taskId));
		assertEquals(0,
				count("SELECT count(*) FROM cleaning_run WHERE annotation_revision_id IN "
						+ "(SELECT annotation_revision_id FROM annotation_revision WHERE annotation_task_id = ?)",
						taskId));
	}

	@Test
	void anUnsubmittedRevisionCannotBeReviewed() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-unsubmitted-review-" + UUID.randomUUID())
			.andExpect(status().isCreated());

		review(ADMIN_TOKEN, UUID.randomUUID(), "annotation-review-unsubmitted-" + UUID.randomUUID(),
				"{\"decision\":\"APPROVED\"}")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error_code").value("ANNOTATION_REVISION_NOT_FOUND"));
	}

	@Test
	void submissionRejectsAnInvalidTimeRangeBeforeCreatingARetentionRecord() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		var taskResponse = create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-invalid-range-" + UUID.randomUUID())
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
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
			.andReturn()
			.getResponse()
			.getContentAsString();
		var taskId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(taskResponse, "$.annotation_task_id"));

		submit(MEMBER_TOKEN, taskId, "annotation-revision-unknown-label-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":0,\"end_ms\":100,\"label\":\"unknown_step\"}]}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error_code").value("ANNOTATION_LABEL_NOT_ALLOWED"));
		submit(MEMBER_TOKEN, taskId, "annotation-revision-overlap-" + UUID.randomUUID(), """
				{"segments":[
				  {"start_ms":0,"end_ms":300,"label":"prepare_workspace"},
				  {"start_ms":100,"end_ms":200,"label":"retrieve_medicine"},
				  {"start_ms":200,"end_ms":400,"label":"prepare_dose"}
				]}
				""").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error_code").value("ANNOTATION_PRIMARY_STEP_OVERLAP"));
		assertEquals(0, count("SELECT count(*) FROM annotation_revision WHERE annotation_task_id = ?", taskId));

		submit(MEMBER_TOKEN, taskId, "annotation-revision-adjacent-" + UUID.randomUUID(), """
				{"segments":[
				  {"start_ms":0,"end_ms":100,"label":"prepare_workspace"},
				  {"start_ms":100,"end_ms":200,"label":"retrieve_medicine"}
				]}
				""").andExpect(status().isCreated())
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
			.andReturn()
			.getResponse()
			.getContentAsString();
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
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		insertVerifiedMediaReport(PROJECT_ID, clipAssetId, 1_000_000L);
		var taskResponse = create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-review-" + UUID.randomUUID())
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		var taskId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(taskResponse, "$.annotation_task_id"));
		var revisionResponse = submit(MEMBER_TOKEN, taskId, "annotation-revision-review-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":120,\"end_ms\":780,\"label\":\"retrieve_medicine\"}]}")
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		var revisionId = UUID
			.fromString(com.jayway.jsonpath.JsonPath.read(revisionResponse, "$.annotation_revision_id"));
		var idempotencyKey = "annotation-review-" + UUID.randomUUID();

		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID).header("Authorization",
					"Bearer " + ADMIN_TOKEN))
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
			.andReturn()
			.getResponse()
			.getContentAsString();
		var reviewIdText = com.jayway.jsonpath.JsonPath.<String>read(response, "$.annotation_review_id");
		var reviewId = UUID.fromString(reviewIdText);

		review(ADMIN_TOKEN, revisionId, idempotencyKey, "{\"decision\":\"APPROVED\",\"review_note\":\"时间段与画面一致\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.annotation_review_id").value(reviewIdText));
		review(MEMBER_TOKEN, revisionId, "annotation-review-member-" + UUID.randomUUID(), "{\"decision\":\"REJECTED\"}")
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error_code").value("PROJECT_ACCESS_DENIED"));
		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID).header("Authorization",
					"Bearer " + ADMIN_TOKEN))
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
				"SELECT outcome FROM cleaning_result WHERE annotation_revision_id = ?", String.class, revisionId));
		assertEquals(1, count("""
				SELECT count(*)
				FROM cleaning_run run
				JOIN annotation_revision revision
				  ON revision.annotation_revision_id = run.annotation_revision_id
				JOIN annotation_task task
				  ON task.annotation_task_id = revision.annotation_task_id
				JOIN annotation_review review
				  ON review.annotation_review_id = run.annotation_review_id
				JOIN cleaning_result result
				  ON result.cleaning_run_id = run.cleaning_run_id
				WHERE run.project_id = ?
				  AND run.clip_asset_id = ?
				  AND task.annotation_task_id = ?
				  AND run.annotation_revision_id = ?
				  AND run.annotation_review_id = ?
				  AND result.annotation_revision_id = ?
				""", PROJECT_ID, clipAssetId, taskId, revisionId, reviewId, revisionId));
		assertEquals(1, count("""
				SELECT count(*) FROM audit_event
				WHERE action = 'ANNOTATION_REVISION_REVIEWED'
				  AND object_type = 'AnnotationReview'
				  AND object_id = ?
				""", reviewIdText));
		assertEquals(1, count("""
				SELECT count(*) FROM audit_event
				WHERE action = 'POST_ANNOTATION_CLEANING_COMPLETED'
				  AND object_type = 'CleaningResult'
				  AND details_json ->> 'annotation_revision_id' = ?
				""", revisionId.toString()));

		recheck(MEMBER_TOKEN, revisionId).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error_code").value("PROJECT_ACCESS_DENIED"));
		var rechecked = recheck(ADMIN_TOKEN, revisionId).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.outcome").value("PASS"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		var recheckedResultId = com.jayway.jsonpath.JsonPath.<String>read(rechecked, "$.cleaning_result_id");
		recheck(ADMIN_TOKEN, revisionId).andExpect(status().isOk())
			.andExpect(jsonPath("$.cleaning_result_id").value(recheckedResultId));
		assertEquals(1, count("SELECT count(*) FROM cleaning_run WHERE annotation_revision_id = ?", revisionId));
		assertEquals(1, count("""
				SELECT count(*) FROM audit_event
				WHERE action = 'POST_ANNOTATION_CLEANING_COMPLETED'
				  AND details_json ->> 'annotation_revision_id' = ?
				""", revisionId.toString()));
	}

	@Test
	void approvedRevisionWithoutVerifiedDurationIsWarningRatherThanImplicitPass() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		var taskResponse = create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-warning-" + UUID.randomUUID())
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		var taskId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(taskResponse, "$.annotation_task_id"));
		var revisionResponse = submit(MEMBER_TOKEN, taskId, "annotation-revision-warning-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":10,\"end_ms\":20,\"label\":\"prepare_workspace\"}]}")
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		var revisionId = UUID
			.fromString(com.jayway.jsonpath.JsonPath.read(revisionResponse, "$.annotation_revision_id"));

		review(ADMIN_TOKEN, revisionId, "annotation-review-warning-" + UUID.randomUUID(), "{\"decision\":\"APPROVED\"}")
			.andExpect(status().isCreated());

		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID).header("Authorization",
					"Bearer " + ADMIN_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].status").value("COMPLETED"))
			.andExpect(jsonPath("$.items[0].annotation_revision.post_cleaning_result.outcome").value("WARNING"))
			.andExpect(jsonPath("$.items[0].annotation_revision.post_cleaning_result.reason_codes[0]")
				.value("VERIFIED_MEDIA_DURATION_UNAVAILABLE"));
	}

	@Test
	void zeroDurationFactIsPersistedAsARejectInsteadOfFailingTheReview() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		var taskResponse = create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-zero-duration-" + UUID.randomUUID())
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		var taskId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(taskResponse, "$.annotation_task_id"));
		var revisionResponse = submit(MEMBER_TOKEN, taskId, "annotation-revision-zero-duration-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":0,\"end_ms\":10,\"label\":\"prepare_workspace\"}]}")
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		var revisionId = UUID
			.fromString(com.jayway.jsonpath.JsonPath.read(revisionResponse, "$.annotation_revision_id"));
		insertVerifiedMediaReport(PROJECT_ID, clipAssetId, 0);

		var reviewResponse = review(ADMIN_TOKEN, revisionId, "annotation-review-zero-duration-" + UUID.randomUUID(),
				"{\"decision\":\"APPROVED\"}")
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		var reviewId = UUID
			.fromString(com.jayway.jsonpath.JsonPath.<String>read(reviewResponse, "$.annotation_review_id"));

		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID).header("Authorization",
					"Bearer " + ADMIN_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].status").value("COMPLETED"))
			.andExpect(jsonPath("$.items[0].annotation_revision.post_cleaning_result.outcome").value("REJECT"))
			.andExpect(jsonPath("$.items[0].annotation_revision.post_cleaning_result.reason_codes[0]")
				.value("VERIFIED_MEDIA_DURATION_INVALID"));
		assertEquals(0L,
				this.jdbcTemplate.queryForObject(
						"SELECT verified_duration_microseconds FROM cleaning_run WHERE annotation_revision_id = ?",
						Long.class, revisionId));
		assertEquals(1, count("""
				SELECT count(*)
				FROM cleaning_run run
				JOIN annotation_revision revision
				  ON revision.annotation_revision_id = run.annotation_revision_id
				JOIN annotation_task task
				  ON task.annotation_task_id = revision.annotation_task_id
				JOIN annotation_review review
				  ON review.annotation_review_id = run.annotation_review_id
				JOIN cleaning_result result
				  ON result.cleaning_run_id = run.cleaning_run_id
				WHERE run.project_id = ?
				  AND run.clip_asset_id = ?
				  AND task.annotation_task_id = ?
				  AND run.annotation_revision_id = ?
				  AND run.annotation_review_id = ?
				  AND result.outcome = 'REJECT'
				""", PROJECT_ID, clipAssetId, taskId, revisionId, reviewId));
	}

	@Test
	void changedAnnotationDocumentIsRejectedByDigestRevalidation() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		insertVerifiedMediaReport(PROJECT_ID, clipAssetId, 1_000_000L);
		var taskResponse = create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-digest-recheck-" + UUID.randomUUID())
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		var taskId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(taskResponse, "$.annotation_task_id"));
		var revisionResponse = submit(MEMBER_TOKEN, taskId, "annotation-revision-digest-recheck-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":10,\"end_ms\":20,\"label\":\"prepare_workspace\"}]}")
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		var revisionId = UUID
			.fromString(com.jayway.jsonpath.JsonPath.read(revisionResponse, "$.annotation_revision_id"));
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

		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID).header("Authorization",
					"Bearer " + ADMIN_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].annotation_revision.post_cleaning_result.outcome").value("REJECT"))
			.andExpect(jsonPath("$.items[0].annotation_revision.post_cleaning_result.reason_codes[0]")
				.value("ANNOTATION_DOCUMENT_DIGEST_MISMATCH"));
	}

	@Test
	void latestVerifiedMediaReportIsSelectedAndFrozenDeterministically() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		insertVerifiedMediaReport(PROJECT_ID, clipAssetId, 100_000L, BASE_TIME);
		var latestReportId = insertVerifiedMediaReport(PROJECT_ID, clipAssetId, 1_000_000L, BASE_TIME.plusSeconds(10),
				"d".repeat(64));
		var taskResponse = create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-latest-media-" + UUID.randomUUID())
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		var taskId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(taskResponse, "$.annotation_task_id"));
		var revisionResponse = submit(MEMBER_TOKEN, taskId, "annotation-revision-latest-media-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":100,\"end_ms\":500,\"label\":\"prepare_workspace\"}]}")
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		var revisionId = UUID
			.fromString(com.jayway.jsonpath.JsonPath.read(revisionResponse, "$.annotation_revision_id"));

		review(ADMIN_TOKEN, revisionId, "annotation-review-latest-media-" + UUID.randomUUID(),
				"{\"decision\":\"APPROVED\"}")
			.andExpect(status().isCreated());

		assertEquals(latestReportId, this.jdbcTemplate.queryForObject(
				"SELECT media_report_id FROM cleaning_run WHERE annotation_revision_id = ?", UUID.class, revisionId));
		assertEquals(1_000_000L,
				this.jdbcTemplate.queryForObject(
						"SELECT verified_duration_microseconds FROM cleaning_run WHERE annotation_revision_id = ?",
						Long.class, revisionId));
	}

	@Test
	void submittingAdministratorCanReviewTheirOwnRevisionWithAnExplicitAuditMarker() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		var taskResponse = create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.ADMIN_ACTOR,
				"annotation-task-self-review-" + UUID.randomUUID())
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		var taskId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(taskResponse, "$.annotation_task_id"));
		var revisionResponse = submit(ADMIN_TOKEN, taskId, "annotation-revision-self-review-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":1,\"end_ms\":2,\"label\":\"retrieve_medicine\"}]}")
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		var revisionId = UUID
			.fromString(com.jayway.jsonpath.JsonPath.read(revisionResponse, "$.annotation_revision_id"));

		review(ADMIN_TOKEN, revisionId, "annotation-review-self-" + UUID.randomUUID(), "{\"decision\":\"APPROVED\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.reviewed_by").value(ProjectMembershipTestData.ADMIN_ACTOR));
		assertEquals(1,
				count("""
						SELECT count(*) FROM audit_event
						WHERE action = 'ANNOTATION_REVISION_REVIEWED'
						  AND object_id IN (SELECT annotation_review_id::text FROM annotation_review WHERE annotation_revision_id = ?)
						  AND reason_code = 'SELF_ADMIN_APPROVAL'
						""",
						revisionId));
	}

	@Test
	void rejectedReviewReturnsTaskForAnImmutableNextRevision() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		var taskResponse = create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-rework-" + UUID.randomUUID())
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		var taskId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(taskResponse, "$.annotation_task_id"));
		var firstRevisionResponse = submit(MEMBER_TOKEN, taskId, "annotation-revision-first-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":100,\"end_ms\":200,\"label\":\"cleanup\"}]}")
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		var firstRevisionId = UUID
			.fromString(com.jayway.jsonpath.JsonPath.read(firstRevisionResponse, "$.annotation_revision_id"));
		var firstDocument = this.jdbcTemplate.queryForObject(
				"SELECT annotation_document::text FROM annotation_revision WHERE annotation_revision_id = ?",
				String.class, firstRevisionId);
		var firstDocumentDigest = this.jdbcTemplate.queryForObject(
				"SELECT document_sha256 FROM annotation_revision WHERE annotation_revision_id = ?", String.class,
				firstRevisionId);

		review(ADMIN_TOKEN, firstRevisionId, "annotation-review-reject-" + UUID.randomUUID(),
				"{\"decision\":\"REJECTED\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.decision").value("REJECTED"));
		assertEquals("ASSIGNED", this.jdbcTemplate.queryForObject(
				"SELECT task_status FROM annotation_task WHERE annotation_task_id = ?", String.class, taskId));
		recheck(ADMIN_TOKEN, firstRevisionId).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("ANNOTATION_POST_CLEANING_RECHECK_NOT_READY"));
		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID).header("Authorization",
					"Bearer " + MEMBER_TOKEN))
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
		assertEquals(1,
				count("SELECT count(*) FROM annotation_review WHERE annotation_revision_id = ?", firstRevisionId));
		assertEquals(0, count("SELECT count(*) FROM cleaning_run WHERE annotation_revision_id = ?", firstRevisionId));
		assertEquals(firstDocument,
				this.jdbcTemplate.queryForObject(
						"SELECT annotation_document::text FROM annotation_revision WHERE annotation_revision_id = ?",
						String.class, firstRevisionId));
		assertEquals(firstDocumentDigest,
				this.jdbcTemplate.queryForObject(
						"SELECT document_sha256 FROM annotation_revision WHERE annotation_revision_id = ?",
						String.class, firstRevisionId));
	}

	@Test
	void rejectedRevisionCannotBecomeUsableData() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		var taskResponse = create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-rejected-admission-" + UUID.randomUUID())
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		var taskId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(taskResponse, "$.annotation_task_id"));
		var revisionResponse = submit(MEMBER_TOKEN, taskId,
				"annotation-revision-rejected-admission-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":100,\"end_ms\":200,\"label\":\"cleanup\"}]}")
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		var revisionId = UUID
			.fromString(com.jayway.jsonpath.JsonPath.read(revisionResponse, "$.annotation_revision_id"));

		review(ADMIN_TOKEN, revisionId, "annotation-review-rejected-admission-" + UUID.randomUUID(),
				"{\"decision\":\"REJECTED\"}")
			.andExpect(status().isCreated());

		assertEquals(0, count("SELECT count(*) FROM cleaning_run WHERE annotation_revision_id = ?", revisionId));
	}

	@Test
	void membersOnlySeeTasksAssignedToThemWhileAdministratorsSeeTheProjectQueue() throws Exception {
		var assignedToMember = insertClipAsset(PROJECT_ID, "INGESTED", false);
		var assignedToAdmin = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, assignedToMember, "QUALIFIED", "MEETS_REQUIREMENTS");
		insertQualityReview(PROJECT_ID, assignedToAdmin, "QUALIFIED", "MEETS_REQUIREMENTS");
		create(ADMIN_TOKEN, assignedToMember, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-member-list-" + UUID.randomUUID())
			.andExpect(status().isCreated());
		create(ADMIN_TOKEN, assignedToAdmin, ProjectMembershipTestData.ADMIN_ACTOR,
				"annotation-task-admin-list-" + UUID.randomUUID())
			.andExpect(status().isCreated());

		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID).header("Authorization",
					"Bearer " + MEMBER_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].assignee_actor_id").value(ProjectMembershipTestData.MEMBER_ACTOR))
			.andExpect(jsonPath("$.progress.scope").value("ASSIGNEE"))
			.andExpect(jsonPath("$.progress.total").value(1));
		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID).header("Authorization",
					"Bearer " + ADMIN_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.progress.scope").value("PROJECT"))
			.andExpect(jsonPath("$.progress.total").value(2))
			.andExpect(jsonPath("$.progress.awaiting_submission").value(2));
	}

	@Test
	void nonProjectMembersCannotReadOrWriteAnnotationTasks() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID, "INGESTED", false);
		insertQualityReview(PROJECT_ID, clipAssetId, "QUALIFIED", "MEETS_REQUIREMENTS");
		var taskResponse = create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-project-isolation-" + UUID.randomUUID())
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		var taskId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(taskResponse, "$.annotation_task_id"));

		this.jdbcTemplate.update("DELETE FROM membership WHERE project_id = ? AND actor_id = ?", PROJECT_ID,
				ProjectMembershipTestData.MEMBER_ACTOR);
		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID).header("Authorization",
					"Bearer " + MEMBER_TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error_code").value("PROJECT_NOT_FOUND"));
		submit(MEMBER_TOKEN, taskId, "annotation-revision-project-isolation-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":1,\"end_ms\":2,\"label\":\"cleanup\"}]}")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error_code").value("PROJECT_NOT_FOUND"));

		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, OTHER_PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM membership WHERE project_id = ? AND actor_id = ?", OTHER_PROJECT_ID,
				ProjectMembershipTestData.ADMIN_ACTOR);
		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/annotation-tasks", OTHER_PROJECT_ID).header("Authorization",
					"Bearer " + ADMIN_TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error_code").value("PROJECT_NOT_FOUND"));
	}

	private org.springframework.test.web.servlet.ResultActions create(String token, UUID clipAssetId,
			String assigneeActorId, String idempotencyKey) throws Exception {
		return this.mockMvc.perform(
				post("/api/v1/projects/{projectId}/clip-assets/{clipAssetId}/annotation-tasks", PROJECT_ID, clipAssetId)
					.header("Authorization", "Bearer " + token)
					.header("Idempotency-Key", idempotencyKey)
					.header("X-Request-Id", "annotation-task-" + UUID.randomUUID())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"assignee_actor_id\":\"" + assigneeActorId + "\"}"));
	}

	private org.springframework.test.web.servlet.ResultActions submit(String token, UUID taskId, String idempotencyKey,
			String body) throws Exception {
		return this.mockMvc
			.perform(post("/api/v1/projects/{projectId}/annotation-tasks/{taskId}/revisions", PROJECT_ID, taskId)
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", "annotation-revision-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private org.springframework.test.web.servlet.ResultActions review(String token, UUID revisionId,
			String idempotencyKey, String body) throws Exception {
		return this.mockMvc.perform(
				post("/api/v1/projects/{projectId}/annotation-revisions/{revisionId}/review", PROJECT_ID, revisionId)
					.header("Authorization", "Bearer " + token)
					.header("Idempotency-Key", idempotencyKey)
					.header("X-Request-Id", "annotation-review-" + UUID.randomUUID())
					.contentType(MediaType.APPLICATION_JSON)
					.content(body));
	}

	private org.springframework.test.web.servlet.ResultActions recheck(String token, UUID revisionId) throws Exception {
		return this.mockMvc
			.perform(post("/api/v1/projects/{projectId}/annotation-revisions/{revisionId}/post-cleaning-rechecks",
					PROJECT_ID, revisionId)
				.header("Authorization", "Bearer " + token)
				.header("X-Request-Id", "annotation-recheck-" + UUID.randomUUID()));
	}

	private void insertQualityReview(UUID projectId, UUID clipAssetId, String decision, String reasonCode) {
		this.jdbcTemplate.update("""
				INSERT INTO quality_review (
				    quality_review_id, project_id, clip_asset_id, decision, reason_code,
				    reviewed_by, reviewed_at, version)
				VALUES (?, ?, ?, ?, ?, ?, ?, 0)
				""", UUID.randomUUID(), projectId, clipAssetId, decision, reasonCode,
				ProjectMembershipTestData.MEMBER_ACTOR, Timestamp.from(BASE_TIME));
	}

	private UUID insertHandPoseCandidate(UUID projectId, UUID clipAssetId) {
		var aiJobId = UUID.randomUUID();
		var candidateId = UUID.randomUUID();
		var createdAt = BASE_TIME.plusSeconds(5);
		this.jdbcTemplate.update("""
				INSERT INTO ai_job (
				    ai_job_id, project_id, clip_asset_id, algorithm_key, algorithm_version,
				    model_sha256, input_sha256, profile_sha256, input_size_bytes, content_type,
				    sampling_fps, status, hand_result, idempotency_key, request_fingerprint,
				    requested_by, created_at, updated_at, version)
				VALUES (?, ?, ?, 'MEDIAPIPE_HAND_LANDMARKER', 'v1', ?, ?, ?, 1234, 'video/mp4',
				        10, 'SUCCEEDED', 'HAND_DETECTED', ?, ?, ?, ?, ?, 0)
				""", aiJobId, projectId, clipAssetId, "c".repeat(64), "d".repeat(64),
				"3a3be8035ec8a9756a32f43b007a4bb2cc2e2c0468d744d248f19c692fd90626",
				"annotation-candidate-job-" + aiJobId, "e".repeat(64), ProjectMembershipTestData.MEMBER_ACTOR,
				Timestamp.from(createdAt), Timestamp.from(createdAt));
		this.jdbcTemplate.update("""
				INSERT INTO candidate_annotation (
				    candidate_annotation_id, ai_job_id, project_id, clip_asset_id,
				    frame_timestamp_ms, hand_index, handedness, handedness_score,
				    bounding_box, landmarks, image_width, image_height, valid_landmark_count, created_at)
				VALUES (?, ?, ?, ?, 120, 0, 'LEFT', 0.9500,
				        '{"x":0.1,"y":0.2,"width":0.3,"height":0.4}'::jsonb,
				        '[{"x":0.1,"y":0.2,"z":0.0}]'::jsonb, 640, 360, 1, ?)
				""", candidateId, aiJobId, projectId, clipAssetId, Timestamp.from(createdAt));
		return candidateId;
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
				""", batchId, projectId, "annotation-batch-" + batchId, SHA256, ProjectMembershipTestData.ADMIN_ACTOR,
				SHA256, Timestamp.from(BASE_TIME.plusSeconds(3600)), Timestamp.from(BASE_TIME),
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
				""", uploadFileId, batchId, clientFileId, "annotation-file-" + uploadFileId, SHA256, SHA256,
				Timestamp.from(BASE_TIME), Timestamp.from(BASE_TIME), "objects/" + uploadFileId,
				"multipart-" + uploadFileId, Timestamp.from(BASE_TIME), Timestamp.from(BASE_TIME),
				Timestamp.from(BASE_TIME), SHA256);
		this.jdbcTemplate.update("""
				INSERT INTO clip_asset (
				    clip_asset_id, project_id, batch_id, source_upload_file_id, client_file_id,
				    content_type, size_bytes, sha256, storage_bucket, object_key, storage_etag,
				    asset_status, deleted_at, deleted_by, delete_reason, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, ?, 'video/mp4', 1234, ?, 'synthetic-bucket', ?, 'synthetic-etag',
				        ?, ?, ?, ?, ?, ?, 0)
				""", clipAssetId, projectId, batchId, uploadFileId, clientFileId, SHA256, "objects/" + clipAssetId,
				status, deleted ? Timestamp.from(BASE_TIME) : null,
				deleted ? ProjectMembershipTestData.ADMIN_ACTOR : null, deleted ? "synthetic deletion" : null,
				Timestamp.from(BASE_TIME), Timestamp.from(BASE_TIME));
		return clipAssetId;
	}

	private UUID insertVerifiedMediaReport(UUID projectId, UUID clipAssetId, long durationMicroseconds) {
		return insertVerifiedMediaReport(projectId, clipAssetId, durationMicroseconds, BASE_TIME);
	}

	private UUID insertVerifiedMediaReport(UUID projectId, UUID clipAssetId, long durationMicroseconds,
			Instant createdAt) {
		return insertVerifiedMediaReport(projectId, clipAssetId, durationMicroseconds, createdAt, MEDIA_RECIPE_DIGEST);
	}

	private UUID insertVerifiedMediaReport(UUID projectId, UUID clipAssetId, long durationMicroseconds,
			Instant createdAt, String recipeDigest) {
		var mediaJobId = UUID.randomUUID();
		var mediaReportId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO media_job (
				    media_job_id, project_id, clip_asset_id, job_type, input_digest, input_size_bytes,
				    content_type, status, idempotency_key, request_fingerprint, created_at, updated_at,
				    version, probe_profile_key, recipe_digest)
				VALUES (?, ?, ?, 'MEDIA_PROBE', ?, 1234, 'video/mp4', 'SUCCEEDED', ?, ?, ?, ?, 0,
				        'media-probe-baseline-v1', ?)
				""", mediaJobId, projectId, clipAssetId, SHA256, "annotation-media-job-" + mediaJobId, SHA256,
				Timestamp.from(createdAt), Timestamp.from(createdAt), recipeDigest);
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
				""", mediaReportId, mediaJobId, projectId, clipAssetId, SHA256, recipeDigest, durationMicroseconds,
				Timestamp.from(createdAt));
		return mediaReportId;
	}

	private int count(String sql, Object... arguments) {
		return this.jdbcTemplate.queryForObject(sql, Integer.class, arguments);
	}

	private void clearState(UUID projectId) {
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
		this.jdbcTemplate.update("DELETE FROM candidate_annotation WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("""
				DELETE FROM ai_job_result_inbox
				WHERE ai_job_id IN (SELECT ai_job_id FROM ai_job WHERE project_id = ?)
				""", projectId);
		this.jdbcTemplate.update("""
				DELETE FROM ai_job_attempt
				WHERE ai_job_id IN (SELECT ai_job_id FROM ai_job WHERE project_id = ?)
				""", projectId);
		this.jdbcTemplate.update("""
				DELETE FROM ai_job_idempotency_receipt
				WHERE project_id = ?
				""", projectId);
		this.jdbcTemplate.update("DELETE FROM ai_job WHERE project_id = ?", projectId);
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
