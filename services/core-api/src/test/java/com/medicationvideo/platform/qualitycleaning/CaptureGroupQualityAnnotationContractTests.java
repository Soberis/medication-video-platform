package com.medicationvideo.platform.qualitycleaning;

import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.medicationvideo.platform.support.ProjectMembershipTestData;
import com.medicationvideo.platform.support.VerifiedMediaTestData;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaptureGroupQualityAnnotationContractTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000700");

	private static final UUID OTHER_PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000799");

	private static final String MEMBER_TOKEN = "synthetic-test-bearer-token-do-not-reuse";

	private static final String ADMIN_TOKEN = "synthetic-test-admin-bearer-token-do-not-reuse";

	private static final String SHA256 = "a".repeat(64);

	private static final String BASE_REASON = "Synthetic group-quality conclusion";

	private static final Instant BASE_TIME = Instant.parse("2026-08-01T01:00:00Z");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		clearState(PROJECT_ID);
		clearState(OTHER_PROJECT_ID);
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, PROJECT_ID);
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, OTHER_PROJECT_ID);
	}

	@AfterEach
	void tearDown() {
		clearState(PROJECT_ID);
		clearState(OTHER_PROJECT_ID);
	}

	@Test
	void receivingMayRemainNeedsReviewButCannotAcceptAQualifyingDecision() throws Exception {
		var group = createGroup(PROJECT_ID, 1, 1, "RECEIVING", 1);

		recordGroup(group, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("receiving-qualified"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("CAPTURE_GROUP_QUALITY_RECEIPT_NOT_FINALIZED"));

		recordGroup(group, MEMBER_TOKEN, "NEEDS_REVIEW", "Waiting for receipt close", 0, key("receiving-review"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.result").value("NEEDS_REVIEW"))
			.andExpect(jsonPath("$.revision").value(1));

		assertEquals(1, count("SELECT count(*) FROM capture_group_quality_review WHERE capture_group_id = ?",
				group.captureGroupId()));
		assertEquals(0,
				count("SELECT count(*) FROM annotation_task WHERE capture_group_id = ?", group.captureGroupId()));
	}

	@Test
	void qualifiedWithExclusionsRequiresExplicitHumanLabelAssignmentAndRetriesIdempotently() throws Exception {
		var group = createGroup(PROJECT_ID, 5, 3, "INCOMPLETE", 1);
		var idempotencyKey = key("group-qualified-with-exclusions");

		var first = recordGroup(group, MEMBER_TOKEN, "QUALIFIED_WITH_EXCLUSIONS", BASE_REASON, 0, idempotencyKey)
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.result").value("QUALIFIED_WITH_EXCLUSIONS"))
			.andExpect(jsonPath("$.revision").value(1))
			.andExpect(jsonPath("$.confirmed_view_set.views.length()").value(5))
			.andExpect(jsonPath("$.input_version").value(startsWith("capture-group-view-set.v1:")))
			.andReturn();
		var reviewId = JsonPath.read(first.getResponse().getContentAsString(), "$.capture_group_quality_review_id");

		assertEquals(0,
				count("SELECT count(*) FROM annotation_task WHERE capture_group_id = ?", group.captureGroupId()));
		var taskId = taskId(group);
		assertEquals(1,
				count("SELECT count(*) FROM annotation_task WHERE capture_group_id = ?", group.captureGroupId()));
		assertEquals(0, count("SELECT count(*) FROM annotation_task WHERE project_id = ? AND capture_group_id IS NULL",
				PROJECT_ID));
		var task = this.jdbcTemplate.queryForMap("""
				SELECT capture_group_id, assignee_actor_id, created_by, primary_clip_asset_id,
				       group_quality_revision
				FROM annotation_task
				WHERE annotation_task_id = ?
				""", taskId);
		assertEquals(group.captureGroupId(), task.get("capture_group_id"));
		assertEquals(ProjectMembershipTestData.MEMBER_ACTOR, task.get("assignee_actor_id"));
		assertEquals(ProjectMembershipTestData.ADMIN_ACTOR, task.get("created_by"));
		assertEquals(null, task.get("primary_clip_asset_id"));
		assertEquals(1L, ((Number) task.get("group_quality_revision")).longValue());

		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID).header("Authorization",
					"Bearer " + MEMBER_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].capture_group_id").value(group.captureGroupId().toString()))
			.andExpect(jsonPath("$.items[0].capture_group_quality_review_id").value(reviewId))
			.andExpect(jsonPath("$.items[0].group_quality_revision").value(1))
			.andExpect(jsonPath("$.items[0].confirmed_view_set.schema_version").value("capture-group-view-set.v1"))
			.andExpect(jsonPath("$.items[0].group_input_version").value(startsWith("capture-group-view-set.v1:")))
			.andExpect(jsonPath("$.items[0].can_submit").value(false))
			.andExpect(jsonPath("$.items[0].can_select_primary").value(false));
		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID).header("Authorization",
					"Bearer " + ADMIN_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].can_select_primary").value(false));

		var replay = recordGroup(group, MEMBER_TOKEN, "QUALIFIED_WITH_EXCLUSIONS", BASE_REASON, 0, idempotencyKey)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.capture_group_quality_review_id").value(reviewId))
			.andReturn();
		assertEquals(1, count("SELECT count(*) FROM capture_group_quality_review WHERE capture_group_id = ?",
				group.captureGroupId()));
		assertEquals(1,
				count("SELECT count(*) FROM annotation_task WHERE capture_group_id = ?", group.captureGroupId()));
		assertEquals(taskId, taskId(group));
		assertEquals(ProjectMembershipTestData.MEMBER_ACTOR, this.jdbcTemplate.queryForObject(
				"SELECT assignee_actor_id FROM annotation_task WHERE annotation_task_id = ?", String.class, taskId));
		assertEquals(reviewId,
				JsonPath.read(replay.getResponse().getContentAsString(), "$.capture_group_quality_review_id"));
	}

	@Test
	void onlyAnAuthorizedHumanLabelIntentCanAssignAQualifiedGroup() throws Exception {
		var group = createGroup(PROJECT_ID, 1, 1, "COMPLETE", 1);
		recordGroup(group, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("explicit-intent-quality"))
			.andExpect(status().isOk());
		assertEquals(0,
				count("SELECT count(*) FROM annotation_task WHERE capture_group_id = ?", group.captureGroupId()));

		assignTask(group, MEMBER_TOKEN, ProjectMembershipTestData.MEMBER_ACTOR, key("unauthorized-intent"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error_code").value("PROJECT_ACCESS_DENIED"));
		var assignmentKey = key("explicit-intent-assignment");
		var first = assignTask(group, ADMIN_TOKEN, ProjectMembershipTestData.MEMBER_ACTOR, assignmentKey)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.capture_group_id").value(group.captureGroupId().toString()))
			.andExpect(jsonPath("$.assignee_actor_id").value(ProjectMembershipTestData.MEMBER_ACTOR))
			.andReturn();
		var taskId = UUID.fromString(JsonPath.read(first.getResponse().getContentAsString(), "$.annotation_task_id"));
		assignTask(group, ADMIN_TOKEN, ProjectMembershipTestData.MEMBER_ACTOR, assignmentKey)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.annotation_task_id").value(taskId.toString()));
		assertEquals(1,
				count("SELECT count(*) FROM annotation_task WHERE capture_group_id = ?", group.captureGroupId()));
	}

	@Test
	void zeroQualifiedViewsCannotAdmitAndNOneGroupWorks() throws Exception {
		var zeroQualified = createGroup(PROJECT_ID, 3, 3, "COMPLETE", 0);
		recordGroup(zeroQualified, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("zero-qualified"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("CAPTURE_GROUP_QUALITY_NO_QUALIFIED_VIEW"));
		assertEquals(0, count("SELECT count(*) FROM capture_group_quality_review WHERE capture_group_id = ?",
				zeroQualified.captureGroupId()));

		var singleView = createGroup(PROJECT_ID, 1, 1, "COMPLETE", 1);
		recordGroup(singleView, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("single-view-qualified"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.result").value("QUALIFIED"))
			.andExpect(jsonPath("$.confirmed_view_set.views.length()").value(1));
		assertEquals(0,
				count("SELECT count(*) FROM annotation_task WHERE capture_group_id = ?", singleView.captureGroupId()));
	}

	@Test
	void admissionRejectsIncompleteWholeQualificationRejectedViewsAndUnresolvedViews() throws Exception {
		var incomplete = createGroup(PROJECT_ID, 3, 2, "INCOMPLETE", 2);
		recordGroup(incomplete, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("incomplete-qualified"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("CAPTURE_GROUP_QUALITY_INCOMPLETE_REQUIRES_EXCLUSIONS"));
		assertEquals(0, count("SELECT count(*) FROM capture_group_quality_review WHERE capture_group_id = ?",
				incomplete.captureGroupId()));

		var rejectedView = createGroup(PROJECT_ID, 2, 2, "COMPLETE", 1);
		recordGroup(rejectedView, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("rejected-qualified"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("CAPTURE_GROUP_QUALITY_QUALIFIED_HAS_EXCLUSIONS"));
		recordGroup(rejectedView, MEMBER_TOKEN, "QUALIFIED_WITH_EXCLUSIONS", BASE_REASON, 0,
				key("rejected-with-exclusions"))
			.andExpect(status().isOk());

		var unresolvedView = createGroup(PROJECT_ID, 2, 2, "COMPLETE", 2);
		this.jdbcTemplate.update("DELETE FROM quality_review WHERE project_id = ? AND clip_asset_id = ?", PROJECT_ID,
				unresolvedView.clipAssetIds().getLast());
		recordGroup(unresolvedView, MEMBER_TOKEN, "QUALIFIED_WITH_EXCLUSIONS", BASE_REASON, 0,
				key("unresolved-with-exclusions"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("CAPTURE_GROUP_QUALITY_VIEW_NOT_RESOLVED"));
	}

	@Test
	void groupQualityHistoryIsAppendOnlyAndRequalificationReusesTheTask() throws Exception {
		var group = createGroup(PROJECT_ID, 2, 2, "COMPLETE", 2);
		recordGroup(group, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("history-qualified"))
			.andExpect(status().isOk());
		var taskId = taskId(group);

		recordGroup(group, MEMBER_TOKEN, "NEEDS_REVIEW", "Needs another human check", 1, key("history-downgrade"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.revision").value(2));
		recordGroup(group, ADMIN_TOKEN, "QUALIFIED", "Rechecked and qualified", 2, key("history-requalify"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.revision").value(3));

		this.mockMvc
			.perform(get(groupQualityPath(), PROJECT_ID, group.captureGroupId()).header("Authorization",
					"Bearer " + MEMBER_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.result").value("QUALIFIED"))
			.andExpect(jsonPath("$.revision").value(3));
		this.mockMvc
			.perform(get(groupQualityPath() + "/history", PROJECT_ID, group.captureGroupId()).header("Authorization",
					"Bearer " + MEMBER_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(3))
			.andExpect(jsonPath("$.items[0].revision").value(1))
			.andExpect(jsonPath("$.items[1].revision").value(2))
			.andExpect(jsonPath("$.items[2].revision").value(3))
			.andExpect(jsonPath("$.items[0].confirmed_view_set.views.length()").value(2));

		assertEquals(1,
				count("SELECT count(*) FROM annotation_task WHERE capture_group_id = ?", group.captureGroupId()));
		assertEquals(taskId, taskId(group));
		assertEquals(ProjectMembershipTestData.MEMBER_ACTOR, this.jdbcTemplate.queryForObject(
				"SELECT assignee_actor_id FROM annotation_task WHERE annotation_task_id = ?", String.class, taskId));
	}

	@Test
	void groupRevisionChainFreezesEveryCameraAndReviewReturnsTheWholeRevisionForRework() throws Exception {
		var group = createGroup(PROJECT_ID, 3, 3, "COMPLETE", 3);
		recordGroup(group, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("revision-qualified"))
			.andExpect(status().isOk());
		var taskId = taskId(group);
		var first = submit(group, taskId, MEMBER_TOKEN, key("revision-first"), 10).andExpect(status().isCreated())
			.andExpect(jsonPath("$.revision_number").value(1))
			.andExpect(jsonPath("$.annotation_schema_version").value("annotation-document.v2"))
			.andExpect(jsonPath("$.clip_asset_id").value(nullValue()))
			.andExpect(jsonPath("$.capture_group_id").value(group.captureGroupId().toString()))
			.andExpect(jsonPath("$.primary_clip_asset_id").value(nullValue()))
			.andExpect(jsonPath("$.confirmed_view_set.schema_version").value("capture-group-view-set.v1"))
			.andExpect(jsonPath("$.input_version").value(startsWith("capture-group-view-set.v1:")))
			.andReturn();
		var firstRevisionId = UUID
			.fromString(JsonPath.read(first.getResponse().getContentAsString(), "$.annotation_revision_id"));
		assertEquals(3, count(
				"SELECT count(*) FROM annotation_task_view WHERE annotation_task_id = ? AND view_state = 'SUBMITTED'",
				taskId));

		this.mockMvc
			.perform(post("/api/v1/projects/{projectId}/annotation-revisions/{revisionId}/review", PROJECT_ID,
					firstRevisionId)
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", key("revision-reject"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(reviewRequest(firstRevisionId, "REJECTED", "Synthetic rework")))
			.andExpect(status().isCreated());

		submit(group, taskId, MEMBER_TOKEN, key("revision-second"), 20).andExpect(status().isCreated())
			.andExpect(jsonPath("$.revision_number").value(2))
			.andExpect(jsonPath("$.primary_clip_asset_id").value(nullValue()))
			.andExpect(jsonPath("$.annotation_schema_version").value("annotation-document.v2"));
		assertEquals(2, count("SELECT count(*) FROM annotation_revision WHERE annotation_task_id = ?", taskId));
		assertEquals(2,
				count("SELECT count(*) FROM annotation_revision WHERE capture_group_id = ?", group.captureGroupId()));
		assertEquals(1,
				count("SELECT count(*) FROM annotation_task WHERE capture_group_id = ?", group.captureGroupId()));
	}

	@Test
	void approvedGroupRevisionReturnsReviewContextAndPassesEventCleaningWithExclusions() throws Exception {
		var group = createGroup(PROJECT_ID, 5, 3, "INCOMPLETE", 3);
		recordGroup(group, MEMBER_TOKEN, "QUALIFIED_WITH_EXCLUSIONS", BASE_REASON, 0, key("review-context-quality"))
			.andExpect(status().isOk());
		var taskId = taskId(group);
		saveCameraDraft(taskId, group.clipAssetIds().getFirst(), MEMBER_TOKEN).andExpect(status().isOk());
		var revision = submit(group, taskId, MEMBER_TOKEN, key("review-context-submit"), 400)
			.andExpect(status().isCreated())
			.andReturn();
		var revisionId = UUID
			.fromString(JsonPath.read(revision.getResponse().getContentAsString(), "$.annotation_revision_id"));
		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID).header("Authorization",
					"Bearer " + ADMIN_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].annotation_revision.annotation_revision_id").value(revisionId.toString()));

		this.mockMvc.perform(
				post("/api/v1/projects/{projectId}/annotation-revisions/{revisionId}/review", PROJECT_ID, revisionId)
					.header("Authorization", "Bearer " + ADMIN_TOKEN)
					.header("Idempotency-Key", key("review-context-approve"))
					.contentType(MediaType.APPLICATION_JSON)
					.content(reviewRequest(revisionId, "APPROVED", "Synthetic review")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.capture_group_id").value(group.captureGroupId().toString()))
			.andExpect(jsonPath("$.revision").value(1))
			.andExpect(jsonPath("$.primary_clip_asset_id").value(nullValue()))
			.andExpect(jsonPath("$.qualified_views.length()").value(3))
			.andExpect(jsonPath("$.rejected_missing_views.length()").value(2))
			.andExpect(jsonPath("$.reviewed_camera_numbers.length()").value(5))
			.andExpect(jsonPath("$.return_camera_numbers.length()").value(0))
			.andExpect(jsonPath("$.candidate_evidence.length()").value(3))
			.andExpect(jsonPath("$.candidate_evidence[0].execution_status").value("NO_JOB"))
			.andExpect(jsonPath("$.candidate_evidence[0].provenance_valid").value(true));

		assertEquals(1, count("SELECT count(*) FROM annotation_review WHERE annotation_revision_id = ?", revisionId));
		assertEquals(0,
				count("SELECT count(*) FROM capture_group_cleaning WHERE annotation_revision_id = ?", revisionId));
		assertEquals("COMPLETED", this.jdbcTemplate.queryForObject(
				"SELECT task_status FROM annotation_task WHERE annotation_task_id = ?", String.class, taskId));
	}

	@Test
	void rejectedGroupReviewDoesNotCreateACleaningPass() throws Exception {
		var group = createGroup(PROJECT_ID, 1, 1, "COMPLETE", 1);
		recordGroup(group, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("review-rejected-quality"))
			.andExpect(status().isOk());
		var taskId = taskId(group);
		saveCameraDraft(taskId, group.clipAssetIds().getFirst(), MEMBER_TOKEN).andExpect(status().isOk());
		var revision = submit(group, taskId, MEMBER_TOKEN, key("review-rejected-submit"), 200)
			.andExpect(status().isCreated())
			.andReturn();
		var revisionId = UUID
			.fromString(JsonPath.read(revision.getResponse().getContentAsString(), "$.annotation_revision_id"));

		this.mockMvc.perform(
				post("/api/v1/projects/{projectId}/annotation-revisions/{revisionId}/review", PROJECT_ID, revisionId)
					.header("Authorization", "Bearer " + ADMIN_TOKEN)
					.header("Idempotency-Key", key("review-rejected-decision"))
					.contentType(MediaType.APPLICATION_JSON)
					.content(reviewRequest(revisionId, "REJECTED", "Needs rework")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.decision").value("REJECTED"))
			.andExpect(jsonPath("$.capture_group_id").value(group.captureGroupId().toString()));

		assertEquals(0,
				count("SELECT count(*) FROM capture_group_cleaning WHERE annotation_revision_id = ?", revisionId));
		assertEquals("ASSIGNED", this.jdbcTemplate.queryForObject(
				"SELECT task_status FROM annotation_task WHERE annotation_task_id = ?", String.class, taskId));
	}

	@Test
	void approvedGroupReviewCompletesTaskWithoutCleaningSnapshot() throws Exception {
		var group = createGroup(PROJECT_ID, 2, 2, "COMPLETE", 2);
		recordGroup(group, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("mapping-quality"))
			.andExpect(status().isOk());
		var taskId = taskId(group);
		saveCameraDraft(taskId, group.clipAssetIds().getFirst(), MEMBER_TOKEN).andExpect(status().isOk());
		var revision = submit(group, taskId, MEMBER_TOKEN, key("mapping-submit"), 400).andExpect(status().isCreated())
			.andReturn();
		var revisionId = UUID
			.fromString(JsonPath.read(revision.getResponse().getContentAsString(), "$.annotation_revision_id"));
		this.mockMvc.perform(
				post("/api/v1/projects/{projectId}/annotation-revisions/{revisionId}/review", PROJECT_ID, revisionId)
					.header("Authorization", "Bearer " + ADMIN_TOKEN)
					.header("Idempotency-Key", key("mapping-approve"))
					.contentType(MediaType.APPLICATION_JSON)
					.content(reviewRequest(revisionId, "APPROVED", null)))
			.andExpect(status().isCreated());

		assertEquals("COMPLETED", this.jdbcTemplate.queryForObject(
				"SELECT task_status FROM annotation_task WHERE annotation_task_id = ?", String.class, taskId));
		assertEquals(0,
				count("SELECT count(*) FROM capture_group_cleaning WHERE annotation_revision_id = ?", revisionId));

		this.mockMvc
			.perform(post(
					"/api/v1/projects/{projectId}/annotation-revisions/{revisionId}/capture-group-cleaning-rechecks",
					PROJECT_ID, revisionId)
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isNotFound());
	}

	@Test
	void qualityDowngradeBlocksNewRevisionAndRequalificationKeepsTheTask() throws Exception {
		var group = createGroup(PROJECT_ID, 2, 2, "COMPLETE", 2);
		recordGroup(group, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("downgrade-qualified"))
			.andExpect(status().isOk());
		var taskId = taskId(group);
		saveCameraDraft(taskId, group.clipAssetIds().getFirst(), MEMBER_TOKEN).andExpect(status().isOk());

		recordGroup(group, MEMBER_TOKEN, "NEEDS_REVIEW", "View quality changed", 1, key("downgrade-review"))
			.andExpect(status().isOk());
		recordGroup(group, MEMBER_TOKEN, "REJECTED", "View quality rejected", 2, key("downgrade-rejected"))
			.andExpect(status().isOk());
		submit(group, taskId, MEMBER_TOKEN, key("downgrade-blocked"), 10).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("ANNOTATION_TASK_GROUP_QUALITY_NOT_QUALIFIED"));
		assertEquals(0, count("SELECT count(*) FROM annotation_revision WHERE annotation_task_id = ?", taskId));

		recordGroup(group, MEMBER_TOKEN, "QUALIFIED", "View quality rechecked", 3, key("downgrade-requalified"))
			.andExpect(status().isOk());
		assignTask(group, ADMIN_TOKEN, ProjectMembershipTestData.MEMBER_ACTOR, key("downgrade-reassignment"))
			.andExpect(status().isCreated());
		submit(group, taskId, MEMBER_TOKEN, key("after-requalification"), 10).andExpect(status().isCreated())
			.andExpect(jsonPath("$.revision_number").value(1));
		assertEquals(1,
				count("SELECT count(*) FROM annotation_task WHERE capture_group_id = ?", group.captureGroupId()));
		assertEquals(taskId, taskId(group));
		assertEquals(ProjectMembershipTestData.MEMBER_ACTOR, this.jdbcTemplate.queryForObject(
				"SELECT assignee_actor_id FROM annotation_task WHERE annotation_task_id = ?", String.class, taskId));
	}

	@Test
	void submittedRevisionFromOldGroupInputIsRetainedButNotExposedAsCurrent() throws Exception {
		var group = createGroup(PROJECT_ID, 2, 2, "COMPLETE", 2);
		recordGroup(group, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("stale-qualified"))
			.andExpect(status().isOk());
		var taskId = taskId(group);
		saveCameraDraft(taskId, group.clipAssetIds().getFirst(), MEMBER_TOKEN).andExpect(status().isOk());
		var first = submit(group, taskId, MEMBER_TOKEN, key("stale-first-submit"), 100).andExpect(status().isCreated())
			.andExpect(jsonPath("$.revision_number").value(1))
			.andReturn();
		var firstRevisionId = UUID
			.fromString(JsonPath.read(first.getResponse().getContentAsString(), "$.annotation_revision_id"));
		var firstFacts = this.jdbcTemplate.queryForMap("""
				SELECT annotation_revision_id, annotation_document::text AS annotation_document,
				       confirmed_view_set::text AS confirmed_view_set, input_version,
				       submitted_by, primary_clip_asset_id
				FROM annotation_revision
				WHERE annotation_task_id = ? AND revision_number = 1
				""", taskId);

		recordViewQuality(group.clipAssetIds().getLast(), MEMBER_TOKEN, "REJECTED", "WRONG_SCENE", 0)
			.andExpect(status().isOk());
		recordGroup(group, MEMBER_TOKEN, "NEEDS_REVIEW", "Supporting view changed", 1, key("stale-input-review"))
			.andExpect(status().isOk());
		recordGroup(group, MEMBER_TOKEN, "QUALIFIED_WITH_EXCLUSIONS", "Changed input requalified", 2,
				key("stale-input-qualified"))
			.andExpect(status().isOk());
		assignTask(group, ADMIN_TOKEN, ProjectMembershipTestData.MEMBER_ACTOR, key("stale-reassignment"))
			.andExpect(status().isCreated());

		assertEquals("ASSIGNED", this.jdbcTemplate.queryForObject(
				"SELECT task_status FROM annotation_task WHERE annotation_task_id = ?", String.class, taskId));
		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID).header("Authorization",
					"Bearer " + MEMBER_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].status").value("ASSIGNED"))
			.andExpect(jsonPath("$.items[0].can_submit").value(false))
			.andExpect(jsonPath("$.items[0].camera_view_progress.pending").value(2))
			.andExpect(jsonPath("$.items[0].annotation_revision").isEmpty());

		this.mockMvc
			.perform(post("/api/v1/projects/{projectId}/annotation-revisions/{revisionId}/review", PROJECT_ID,
					firstRevisionId)
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", key("stale-review"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(reviewRequest(firstRevisionId, "APPROVED", null)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("ANNOTATION_REVISION_NOT_CURRENT"));
		assertEquals(0,
				count("SELECT count(*) FROM annotation_review WHERE annotation_revision_id = ?", firstRevisionId));

		submit(group, taskId, MEMBER_TOKEN, key("stale-second-submit"), 120).andExpect(status().isCreated())
			.andExpect(jsonPath("$.revision_number").value(2));
		assertEquals(2, count("SELECT count(*) FROM annotation_revision WHERE annotation_task_id = ?", taskId));
		assertEquals(firstFacts, this.jdbcTemplate.queryForMap("""
				SELECT annotation_revision_id, annotation_document::text AS annotation_document,
				       confirmed_view_set::text AS confirmed_view_set, input_version,
				       submitted_by, primary_clip_asset_id
				FROM annotation_revision
				WHERE annotation_task_id = ? AND revision_number = 1
				""", taskId));
		assertEquals(1, count("""
				SELECT count(*) FROM audit_event
				WHERE action = 'CAPTURE_GROUP_ANNOTATION_TASK_REOPENED_FOR_INPUT_CHANGE'
				  AND object_id = ?
				  AND details_json ->> 'previous_status' = 'SUBMITTED'
				""", taskId.toString()));
	}

	@Test
	void changedInputRebuildsPerCameraEligibilityWhilePreservingOldRevision() throws Exception {
		var group = createGroup(PROJECT_ID, 2, 2, "COMPLETE", 2);
		recordGroup(group, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("primary-ineligible-qualified"))
			.andExpect(status().isOk());
		var taskId = taskId(group);
		submit(group, taskId, MEMBER_TOKEN, key("primary-ineligible-first"), 100).andExpect(status().isCreated())
			.andExpect(jsonPath("$.revision_number").value(1))
			.andExpect(jsonPath("$.primary_clip_asset_id").value(nullValue()));

		recordViewQuality(group.clipAssetIds().getFirst(), MEMBER_TOKEN, "REJECTED", "WRONG_SCENE", 0)
			.andExpect(status().isOk());
		recordGroup(group, MEMBER_TOKEN, "NEEDS_REVIEW", "Camera quality changed", 1, key("camera-ineligible-review"))
			.andExpect(status().isOk());
		recordGroup(group, MEMBER_TOKEN, "QUALIFIED_WITH_EXCLUSIONS", "One camera remains eligible", 2,
				key("camera-ineligible-requalified"))
			.andExpect(status().isOk());

		assignTask(group, ADMIN_TOKEN, ProjectMembershipTestData.MEMBER_ACTOR, key("camera-reassign"))
			.andExpect(status().isCreated());
		var second = submit(group, taskId, MEMBER_TOKEN, key("camera-new-input-submit"), 120)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.revision_number").value(2))
			.andExpect(jsonPath("$.primary_clip_asset_id").value(nullValue()))
			.andReturn();
		var secondRevisionId = UUID
			.fromString(JsonPath.read(second.getResponse().getContentAsString(), "$.annotation_revision_id"));
		var secondDocument = this.jdbcTemplate.queryForObject(
				"SELECT annotation_document::text FROM annotation_revision WHERE annotation_revision_id = ?",
				String.class, secondRevisionId);
		assertEquals("EXCLUDED", JsonPath.read(secondDocument, "$.camera_views[0].disposition"));
		assertEquals("SUBMITTED", JsonPath.read(secondDocument, "$.camera_views[1].disposition"));
		assertEquals(null, this.jdbcTemplate.queryForObject(
				"SELECT primary_clip_asset_id FROM annotation_revision WHERE annotation_task_id = ? AND revision_number = 1",
				UUID.class, taskId));
		assertEquals(2, count("SELECT count(*) FROM annotation_revision WHERE annotation_task_id = ?", taskId));
	}

	@Test
	void missingDurationBlocksSubmissionAndBoundsRemainEnforced() throws Exception {
		var group = createGroup(PROJECT_ID, 1, 1, "COMPLETE", 1);
		recordGroup(group, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("duration-quality"))
			.andExpect(status().isOk());
		var task = taskId(group);
		var clip = group.clipAssetIds().getFirst();
		saveCameraDraft(task, clip, MEMBER_TOKEN).andExpect(status().isOk());
		this.jdbcTemplate.update("DELETE FROM media_report WHERE clip_asset_id = ?", clip);
		this.jdbcTemplate.update("DELETE FROM media_job WHERE clip_asset_id = ?", clip);
		submit(group, task, MEMBER_TOKEN, key("unknown-duration"), 100).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("ANNOTATION_MEDIA_DURATION_UNVERIFIED"));
		VerifiedMediaTestData.insert(this.jdbcTemplate, PROJECT_ID, clip, SHA256, 100_000);
		submit(group, task, MEMBER_TOKEN, key("over-duration"), 101).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error_code").value("ANNOTATION_SEGMENT_EXCEEDS_MEDIA_DURATION"));
		submit(group, task, MEMBER_TOKEN, key("valid-duration"), 100).andExpect(status().isCreated());
	}

	@Test
	void approvalRechecksDurationButRejectionCanReturnInvalidInputForRework() throws Exception {
		var group = createGroup(PROJECT_ID, 1, 1, "COMPLETE", 1);
		recordGroup(group, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("review-duration-quality"))
			.andExpect(status().isOk());
		var task = taskId(group);
		saveCameraDraft(task, group.clipAssetIds().getFirst(), MEMBER_TOKEN).andExpect(status().isOk());
		var submitted = submit(group, task, MEMBER_TOKEN, key("review-duration-submit"), 100)
			.andExpect(status().isCreated())
			.andReturn();
		var revision = JsonPath.read(submitted.getResponse().getContentAsString(), "$.annotation_revision_id")
			.toString();
		this.jdbcTemplate.update("DELETE FROM media_report WHERE clip_asset_id = ?", group.clipAssetIds().getFirst());
		this.jdbcTemplate.update("DELETE FROM media_job WHERE clip_asset_id = ?", group.clipAssetIds().getFirst());
		this.mockMvc
			.perform(post("/api/v1/projects/{projectId}/annotation-revisions/{revisionId}/review", PROJECT_ID, revision)
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", key("unknown-duration-approve"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(reviewRequest(UUID.fromString(revision), "APPROVED", null)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("ANNOTATION_MEDIA_DURATION_UNVERIFIED"));
		this.mockMvc
			.perform(post("/api/v1/projects/{projectId}/annotation-revisions/{revisionId}/review", PROJECT_ID, revision)
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", key("unknown-duration-reject"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(reviewRequest(UUID.fromString(revision), "REJECTED", null)))
			.andExpect(status().isCreated());
	}

	@Test
	void administratorCanConfirmTheirOwnSubmission() throws Exception {
		var group = createGroup(PROJECT_ID, 1, 1, "COMPLETE", 1);
		recordGroup(group, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("self-review-quality"))
			.andExpect(status().isOk());
		var assigned = assignTask(group, ADMIN_TOKEN, ProjectMembershipTestData.ADMIN_ACTOR,
				key("self-review-assignment"))
			.andExpect(status().isCreated())
			.andReturn();
		var task = UUID.fromString(JsonPath.read(assigned.getResponse().getContentAsString(), "$.annotation_task_id"));
		saveCameraDraft(task, group.clipAssetIds().getFirst(), ADMIN_TOKEN).andExpect(status().isOk());
		var submitted = submit(group, task, ADMIN_TOKEN, key("self-review-submit"), 100).andExpect(status().isCreated())
			.andReturn();
		var revision = JsonPath.read(submitted.getResponse().getContentAsString(), "$.annotation_revision_id")
			.toString();
		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID).header("Authorization",
					"Bearer " + ADMIN_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].can_review").value(true));
		this.mockMvc
			.perform(post("/api/v1/projects/{projectId}/annotation-revisions/{revisionId}/review", PROJECT_ID, revision)
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", key("self-review-approve"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(reviewRequest(UUID.fromString(revision), "APPROVED", null)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.decision").value("APPROVED"));
		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID).header("Authorization",
					"Bearer " + ADMIN_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].can_review").value(false));
		assertEquals(1,
				count("SELECT count(*) FROM audit_event WHERE action = 'ANNOTATION_REVISION_REVIEWED' "
						+ "AND reason_code = 'SELF_ADMIN_APPROVAL' AND details_json ->> 'annotation_revision_id' = ?",
						revision));
	}

	@Test
	void changedGroupInputAppendsRevisionWhileSameInputDoesNotCreateOne() throws Exception {
		var group = createGroup(PROJECT_ID, 2, 2, "COMPLETE", 2);
		recordGroup(group, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("input-qualified"))
			.andExpect(status().isOk());
		var taskId = taskId(group);
		saveCameraDraft(taskId, group.clipAssetIds().getFirst(), MEMBER_TOKEN).andExpect(status().isOk());
		var first = submit(group, taskId, MEMBER_TOKEN, key("input-first-submit"), 100).andExpect(status().isCreated())
			.andExpect(jsonPath("$.revision_number").value(1))
			.andReturn();
		var firstRevisionId = UUID
			.fromString(JsonPath.read(first.getResponse().getContentAsString(), "$.annotation_revision_id"));
		this.mockMvc
			.perform(post("/api/v1/projects/{projectId}/annotation-revisions/{revisionId}/review", PROJECT_ID,
					firstRevisionId)
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", key("input-first-approve"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(reviewRequest(firstRevisionId, "APPROVED", null)))
			.andExpect(status().isCreated());

		var firstFacts = this.jdbcTemplate.queryForMap("""
				SELECT annotation_revision_id, annotation_document::text AS annotation_document,
				       confirmed_view_set::text AS confirmed_view_set, input_version,
				       submitted_by, primary_clip_asset_id
				FROM annotation_revision
				WHERE annotation_task_id = ? AND revision_number = 1
				""", taskId);
		var assignee = this.jdbcTemplate.queryForObject(
				"SELECT assignee_actor_id FROM annotation_task WHERE annotation_task_id = ?", String.class, taskId);

		recordGroup(group, MEMBER_TOKEN, "NEEDS_REVIEW", "Input needs another check", 1, key("input-downgrade"))
			.andExpect(status().isOk());
		recordGroup(group, MEMBER_TOKEN, "QUALIFIED", "Same input requalified", 2, key("input-same"))
			.andExpect(status().isOk());
		assertEquals("COMPLETED", this.jdbcTemplate.queryForObject(
				"SELECT task_status FROM annotation_task WHERE annotation_task_id = ?", String.class, taskId));
		assertEquals(1, count("SELECT count(*) FROM annotation_revision WHERE annotation_task_id = ?", taskId));
		submit(group, taskId, MEMBER_TOKEN, key("input-same-submit"), 100).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("ANNOTATION_TASK_ALREADY_SUBMITTED"));

		recordViewQuality(group.clipAssetIds().get(1), MEMBER_TOKEN, "REJECTED", "WRONG_SCENE", 0)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.version").value(1));
		recordGroup(group, MEMBER_TOKEN, "NEEDS_REVIEW", "Supporting view changed", 3, key("input-changed-review"))
			.andExpect(status().isOk());
		recordGroup(group, MEMBER_TOKEN, "QUALIFIED_WITH_EXCLUSIONS", "Changed input requalified", 4,
				key("input-changed-qualified"))
			.andExpect(status().isOk());
		assignTask(group, ADMIN_TOKEN, ProjectMembershipTestData.MEMBER_ACTOR, key("input-changed-assignment"))
			.andExpect(status().isCreated());
		assertEquals("ASSIGNED", this.jdbcTemplate.queryForObject(
				"SELECT task_status FROM annotation_task WHERE annotation_task_id = ?", String.class, taskId));
		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID).header("Authorization",
					"Bearer " + ADMIN_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].annotation_revision").isEmpty());

		submit(group, taskId, MEMBER_TOKEN, key("input-second-submit"), 120).andExpect(status().isCreated())
			.andExpect(jsonPath("$.revision_number").value(2))
			.andExpect(jsonPath("$.primary_clip_asset_id").value(nullValue()));
		assertEquals(2, count("SELECT count(*) FROM annotation_revision WHERE annotation_task_id = ?", taskId));
		assertEquals(1,
				count("SELECT count(*) FROM annotation_task WHERE capture_group_id = ?", group.captureGroupId()));
		assertEquals(taskId, taskId(group));
		assertEquals(assignee, this.jdbcTemplate.queryForObject(
				"SELECT assignee_actor_id FROM annotation_task WHERE annotation_task_id = ?", String.class, taskId));

		var firstFactsAfter = this.jdbcTemplate.queryForMap("""
				SELECT annotation_revision_id, annotation_document::text AS annotation_document,
				       confirmed_view_set::text AS confirmed_view_set, input_version,
				       submitted_by, primary_clip_asset_id
				FROM annotation_revision
				WHERE annotation_task_id = ? AND revision_number = 1
				""", taskId);
		assertEquals(firstFacts, firstFactsAfter);
	}

	@Test
	void groupEndpointsEnforceAssigneeAuthorizationAndProjectIsolation() throws Exception {
		var group = createGroup(PROJECT_ID, 1, 1, "COMPLETE", 1);
		recordGroup(group, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("authorization-qualified"))
			.andExpect(status().isOk());
		var taskId = taskId(group);
		saveCameraDraft(taskId, group.clipAssetIds().getFirst(), ADMIN_TOKEN).andExpect(status().isOk());
		saveCameraDraft(taskId, group.clipAssetIds().getFirst(), MEMBER_TOKEN).andExpect(status().isOk());

		var otherGroup = createGroup(OTHER_PROJECT_ID, 1, 1, "COMPLETE", 1);
		this.mockMvc
			.perform(get(groupQualityPath(), PROJECT_ID, otherGroup.captureGroupId()).header("Authorization",
					"Bearer " + MEMBER_TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error_code").value("CAPTURE_GROUP_NOT_FOUND"));
	}

	@Test
	void batchQualityReadReturnsDistinctCurrentFactsInRequestedOrder() throws Exception {
		var reviewedGroup = createGroup(PROJECT_ID, 1, 1, "COMPLETE", 1);
		var unreviewedGroup = createGroup(PROJECT_ID, 1, 1, "COMPLETE", 1);
		recordGroup(reviewedGroup, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("batch-quality-reviewed"))
			.andExpect(status().isOk());

		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/capture-group-quality-reviews", PROJECT_ID)
				.param("capture_group_id", unreviewedGroup.captureGroupId().toString())
				.param("capture_group_id", reviewedGroup.captureGroupId().toString())
				.param("capture_group_id", reviewedGroup.captureGroupId().toString())
				.header("Authorization", "Bearer " + MEMBER_TOKEN))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].capture_group_id").value(unreviewedGroup.captureGroupId().toString()))
			.andExpect(jsonPath("$.items[0].result").value("UNREVIEWED"))
			.andExpect(jsonPath("$.items[1].capture_group_id").value(reviewedGroup.captureGroupId().toString()))
			.andExpect(jsonPath("$.items[1].result").value("QUALIFIED"));
	}

	@Test
	void administratorPublishesAnImmutableLabelSchemaWithoutChangingExistingTasks() throws Exception {
		var existingGroup = createGroup(PROJECT_ID, 1, 1, "COMPLETE", 1);
		recordGroup(existingGroup, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("existing-label-quality"))
			.andExpect(status().isOk());
		assignTask(existingGroup, ADMIN_TOKEN, ProjectMembershipTestData.MEMBER_ACTOR, key("existing-label-task"))
			.andExpect(status().isCreated());
		var existingTaskId = taskId(existingGroup);
		var existingSchemaId = this.jdbcTemplate.queryForObject(
				"SELECT label_schema_id FROM annotation_task WHERE annotation_task_id = ?", UUID.class, existingTaskId);

		this.jdbcTemplate.update("DELETE FROM membership WHERE project_id = ? AND actor_id = ?", PROJECT_ID,
				ProjectMembershipTestData.ADMIN_ACTOR);
		this.mockMvc.perform(get("/api/v1/annotation-label-schema").header("Authorization", "Bearer " + MEMBER_TOKEN))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"));
		this.mockMvc
			.perform(post("/api/v1/annotation-label-schema/labels").header("Authorization", "Bearer " + MEMBER_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"label_id\":\"verify_dose\",\"display_name\":\"核对剂量\",\"description\":\"核对剂量。\"}"))
			.andExpect(status().isForbidden());
		var published = this.mockMvc
			.perform(post("/api/v1/annotation-label-schema/labels").header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("X-Request-Id", key("admin-label"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"label_id\":\"verify_dose\",\"display_name\":\"核对剂量\",\"description\":\"核对本次已配制剂量。\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.schema_version").value("p0-atomic-action.v5"))
			.andExpect(jsonPath("$.labels[?(@.label_id == 'verify_dose')].display_name").value("核对剂量"))
			.andReturn();
		var publishedSchemaId = UUID
			.fromString(JsonPath.read(published.getResponse().getContentAsString(), "$.label_schema_id"));
		assertEquals(1, count("SELECT count(*) FROM audit_event WHERE object_id = ? AND project_id IS NULL "
				+ "AND authorization_reference = 'PLATFORM_ADMIN'", publishedSchemaId.toString()));
		assertTrue(!existingSchemaId.equals(publishedSchemaId));
		assertEquals(existingSchemaId,
				this.jdbcTemplate.queryForObject(
						"SELECT label_schema_id FROM annotation_task WHERE annotation_task_id = ?", UUID.class,
						existingTaskId));

		var newGroup = createGroup(PROJECT_ID, 1, 1, "COMPLETE", 1);
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, PROJECT_ID);
		recordGroup(newGroup, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("new-label-quality"))
			.andExpect(status().isOk());
		assignTask(newGroup, ADMIN_TOKEN, ProjectMembershipTestData.MEMBER_ACTOR, key("new-label-task"))
			.andExpect(status().isCreated());
		assertEquals(publishedSchemaId,
				this.jdbcTemplate.queryForObject(
						"SELECT label_schema_id FROM annotation_task WHERE annotation_task_id = ?", UUID.class,
						taskId(newGroup)));
	}

	private UUID approveRevision(GroupFixture group, String groupQualityResult, String prefix, int endMs)
			throws Exception {
		recordGroup(group, MEMBER_TOKEN, groupQualityResult, BASE_REASON, 0, key(prefix + "-quality"))
			.andExpect(status().isOk());
		var taskId = taskId(group);
		saveCameraDraft(taskId, group.clipAssetIds().getFirst(), MEMBER_TOKEN).andExpect(status().isOk());
		var revision = submit(group, taskId, MEMBER_TOKEN, key(prefix + "-submit"), endMs)
			.andExpect(status().isCreated())
			.andReturn();
		var revisionId = UUID
			.fromString(JsonPath.read(revision.getResponse().getContentAsString(), "$.annotation_revision_id"));
		approveRevisionOnly(revisionId, prefix + "-approve");
		return revisionId;
	}

	private void approveRevisionOnly(UUID revisionId, String prefix) throws Exception {
		this.mockMvc.perform(
				post("/api/v1/projects/{projectId}/annotation-revisions/{revisionId}/review", PROJECT_ID, revisionId)
					.header("Authorization", "Bearer " + ADMIN_TOKEN)
					.header("Idempotency-Key", key(prefix))
					.contentType(MediaType.APPLICATION_JSON)
					.content(reviewRequest(revisionId, "APPROVED", null)))
			.andExpect(status().isCreated());
	}

	private ResultActions recordGroup(GroupFixture group, String token, String result, String reason,
			long expectedRevision, String idempotencyKey) throws Exception {
		return this.mockMvc.perform(
				put(groupQualityPath(), PROJECT_ID, group.captureGroupId()).header("Authorization", "Bearer " + token)
					.header("Idempotency-Key", idempotencyKey)
					.header("X-Request-Id", key("request"))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"result\":\"%s\",\"reason\":\"%s\",\"expected_revision\":%d}".formatted(result, reason,
							expectedRevision)));
	}

	private ResultActions assignTask(GroupFixture group, String token, String assigneeActorId, String idempotencyKey)
			throws Exception {
		return this.mockMvc
			.perform(post("/api/v1/projects/{projectId}/capture-groups/{captureGroupId}/annotation-tasks", PROJECT_ID,
					group.captureGroupId())
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", key("assignment-request"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"assignee_actor_id\":\"%s\"}".formatted(assigneeActorId)));
	}

	private ResultActions saveCameraDraft(UUID taskId, UUID clipAssetId, String token) throws Exception {
		var view = this.jdbcTemplate.queryForMap("""
				SELECT v.camera_number, v.version, t.group_input_version
				FROM annotation_task_view v
				JOIN annotation_task t ON t.annotation_task_id = v.annotation_task_id
				WHERE v.annotation_task_id = ? AND v.clip_asset_id = ?
				""", taskId, clipAssetId);
		var cameraNumber = ((Number) view.get("camera_number")).intValue();
		return this.mockMvc
			.perform(put("/api/v1/projects/{projectId}/annotation-tasks/{taskId}/camera-views/{cameraNumber}",
					PROJECT_ID, taskId, cameraNumber)
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", key("camera-draft"))
				.header("X-Request-Id", key("camera-draft-request"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(("{\"expected_version\":%d,\"expected_input_version\":\"%s\",\"state\":\"DRAFT\","
						+ "\"segments\":[],\"note\":\"Synthetic draft\",\"exclusion_reason\":null}")
					.formatted(((Number) view.get("version")).longValue(), view.get("group_input_version"))));
	}

	private ResultActions submit(GroupFixture group, UUID taskId, String token, String idempotencyKey, int endMs)
			throws Exception {
		var task = this.jdbcTemplate.queryForMap(
				"SELECT version, group_input_version, task_status FROM annotation_task WHERE annotation_task_id = ?",
				taskId);
		var groupResult = this.jdbcTemplate.queryForObject("""
				SELECT result FROM capture_group_quality_review
				WHERE capture_group_id = ? ORDER BY revision DESC LIMIT 1
				""", String.class, group.captureGroupId());
		var correspondenceSegmentRefs = new ArrayList<String>();
		if ("ASSIGNED".equals(task.get("task_status"))
				&& ("QUALIFIED".equals(groupResult) || "QUALIFIED_WITH_EXCLUSIONS".equals(groupResult))) {
			var views = this.jdbcTemplate.queryForList("""
						SELECT v.camera_number, v.version, v.clip_asset_id, v.media_status,
						       coalesce(q.decision, 'UNREVIEWED') AS quality_decision
					FROM annotation_task_view v
					LEFT JOIN quality_review q ON q.clip_asset_id = v.clip_asset_id
					WHERE v.annotation_task_id = ?
					ORDER BY v.camera_number
					""", taskId);
			for (var view : views) {
				var cameraNumber = ((Number) view.get("camera_number")).intValue();
				var clipAssetId = (UUID) view.get("clip_asset_id");
				var included = clipAssetId != null && "INGESTED".equals(view.get("media_status"))
						&& "QUALIFIED".equals(view.get("quality_decision"));
				var segmentId = included ? UUID.randomUUID() : null;
				var state = included ? "SUBMITTED" : "EXCLUDED";
				var segments = included
						? "[{\"segment_id\":\"%s\",\"start_ms\":0,\"end_ms\":%d,\"label\":\"acquire_item\",\"source_candidate_ids\":[]}]"
							.formatted(segmentId, endMs + (cameraNumber - 1L) * 10L)
						: "[]";
				var exclusionReason = included ? "null" : clipAssetId == null
						? "Missing camera slot in synthetic fixture" : "View is not quality-qualified";
				var updateBody = ("{\"expected_version\":%d,\"expected_input_version\":\"%s\",\"state\":\"%s\","
						+ "\"segments\":%s,\"note\":\"\",\"exclusion_reason\":%s}")
					.formatted(((Number) view.get("version")).longValue(), task.get("group_input_version"), state,
							segments, included ? "null" : "\"" + exclusionReason + "\"");
				var update = this.mockMvc
					.perform(put("/api/v1/projects/{projectId}/annotation-tasks/{taskId}/camera-views/{cameraNumber}",
							PROJECT_ID, taskId, cameraNumber)
						.header("Authorization", "Bearer " + token)
						.header("Idempotency-Key", key("camera-submit"))
						.header("X-Request-Id", key("camera-submit-request"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody));
				if (update.andReturn().getResponse().getStatus() >= 400) {
					return update;
				}
				if (included) {
					correspondenceSegmentRefs
						.add("{\"camera_number\":%d,\"segment_id\":\"%s\"}".formatted(cameraNumber, segmentId));
				}
			}
		}
		var correspondences = correspondenceSegmentRefs.isEmpty() ? "[]"
				: "[{\"correspondence_id\":\"%s\",\"status\":\"%s\",\"segments\":[%s]%s}]".formatted(UUID.randomUUID(),
						correspondenceSegmentRefs.size() > 1 ? "CONFIRMED" : "UNCERTAIN",
						String.join(",", correspondenceSegmentRefs), correspondenceSegmentRefs.size() > 1 ? ""
								: ",\"reason\":\"Only one qualified synthetic camera\"");
		var currentTaskVersion = this.jdbcTemplate
			.queryForObject("SELECT version FROM annotation_task WHERE annotation_task_id = ?", Long.class, taskId);
		var body = "{\"expected_task_version\":%d,\"expected_input_version\":\"%s\",\"correspondences\":%s,\"note\":\"Synthetic group annotation\"}"
			.formatted(currentTaskVersion, task.get("group_input_version"), correspondences);
		return this.mockMvc
			.perform(post("/api/v1/projects/{projectId}/annotation-tasks/{taskId}/revisions", PROJECT_ID, taskId)
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", key("submit-request"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private String reviewRequest(UUID revisionId, String decision, String reviewNote) {
		var cameraNumbers = this.jdbcTemplate.queryForList("""
				SELECT (camera_view.value->>'camera_number')::integer
				FROM annotation_revision revision,
				    jsonb_array_elements(revision.annotation_document->'camera_views') AS camera_view(value)
				WHERE revision.annotation_revision_id = ?
				ORDER BY 1
				""", Integer.class, revisionId);
		var returned = "REJECTED".equals(decision) ? this.jdbcTemplate.queryForList("""
				SELECT (camera_view.value->>'camera_number')::integer
				FROM annotation_revision revision,
				    jsonb_array_elements(revision.annotation_document->'camera_views') AS camera_view(value)
				WHERE revision.annotation_revision_id = ? AND camera_view.value->>'included' = 'true'
				ORDER BY 1
				""", Integer.class, revisionId) : List.<Integer>of();
		var reviewed = "REJECTED".equals(decision)
				? cameraNumbers.stream().filter(camera -> !returned.contains(camera)).toList() : cameraNumbers;
		var noteJson = reviewNote == null ? "null"
				: "\"" + reviewNote.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
		return ("{\"decision\":\"%s\",\"review_note\":%s,\"reviewed_camera_numbers\":[%s],"
				+ "\"return_camera_numbers\":[%s]}")
			.formatted(decision, noteJson, reviewed.stream().map(String::valueOf).collect(Collectors.joining(",")),
					returned.stream().map(String::valueOf).collect(Collectors.joining(",")));
	}

	private ResultActions recordViewQuality(UUID clipAssetId, String token, String decision, String reasonCode,
			long expectedVersion) throws Exception {
		return this.mockMvc.perform(
				put("/api/v1/projects/{projectId}/clip-assets/{clipAssetId}/quality-review", PROJECT_ID, clipAssetId)
					.header("Authorization", "Bearer " + token)
					.header("X-Request-Id", key("view-quality-request"))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"decision\":\"%s\",\"reason_code\":\"%s\",\"expected_version\":%d}".formatted(decision,
							reasonCode, expectedVersion)));
	}

	private GroupFixture createGroup(UUID projectId, int expectedViewCount, int boundViewCount, String receiptState,
			int qualifiedViewCount) {
		if (boundViewCount < 1 || boundViewCount > expectedViewCount || qualifiedViewCount < 0
				|| qualifiedViewCount > boundViewCount) {
			throw new IllegalArgumentException("Invalid synthetic group shape.");
		}
		var now = BASE_TIME.plusSeconds(UUID.randomUUID().hashCode() & 0x7fff);
		var batchId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO upload_batch (
				    batch_id, project_id, idempotency_key, request_fingerprint, device_id,
				    initiated_by_actor_id, file_count, total_size_bytes, manifest_sha256, status, expires_at,
				    created_at, updated_at, verified_manifest_sha256, completed_at)
				VALUES (?, ?, ?, ?, 'synthetic-group-device', ?, ?, ?, ?, 'COMPLETED', ?, ?, ?, ?, ?)
				""", batchId, projectId, key("batch"), SHA256, ProjectMembershipTestData.ADMIN_ACTOR, boundViewCount,
				boundViewCount * 1234L, SHA256, Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now),
				Timestamp.from(now), SHA256, Timestamp.from(now));
		this.jdbcTemplate.update("UPDATE upload_batch SET upload_source_type = 'PLATFORM_LOCAL' WHERE batch_id = ?",
				batchId);

		var clipAssetIds = new ArrayList<UUID>();
		var clientFileIds = new ArrayList<UUID>();
		for (var index = 0; index < boundViewCount; index += 1) {
			var uploadFileId = UUID.randomUUID();
			var clientFileId = UUID.randomUUID();
			var clipAssetId = UUID.randomUUID();
			this.jdbcTemplate.update("""
					INSERT INTO upload_file (
					    upload_file_id, batch_id, client_file_id, idempotency_key, request_fingerprint,
					    file_kind, content_type, size_bytes, sha256, part_size_bytes, total_parts,
					    status, created_at, updated_at, storage_bucket, object_key, storage_upload_id,
					    multipart_created_at, completion_started_at, completion_finished_at,
					    verified_sha256, completed_storage_etag)
					VALUES (?, ?, ?, ?, ?, 'VIDEO', 'video/mp4', 1234, ?, 8388608, 1,
					        'UPLOADED', ?, ?, 'synthetic-bucket', ?, ?, ?, ?, ?, ?, 'synthetic-etag')
					""", uploadFileId, batchId, clientFileId, key("file"), SHA256, SHA256, Timestamp.from(now),
					Timestamp.from(now), "objects/" + uploadFileId, "multipart-" + uploadFileId, Timestamp.from(now),
					Timestamp.from(now), Timestamp.from(now), SHA256);
			this.jdbcTemplate.update("""
					INSERT INTO clip_asset (
					    clip_asset_id, project_id, batch_id, source_upload_file_id, client_file_id,
					    content_type, size_bytes, sha256, storage_bucket, object_key, storage_etag,
					    asset_status, deleted_at, deleted_by, delete_reason, created_at, updated_at, version)
					VALUES (?, ?, ?, ?, ?, 'video/mp4', 1234, ?, 'synthetic-bucket', ?, 'synthetic-etag',
					        'INGESTED', NULL, NULL, NULL, ?, ?, 0)
					""", clipAssetId, projectId, batchId, uploadFileId, clientFileId, SHA256, "objects/" + clipAssetId,
					Timestamp.from(now), Timestamp.from(now));
			VerifiedMediaTestData.insert(this.jdbcTemplate, projectId, clipAssetId, SHA256, 10_000_000);
			clipAssetIds.add(clipAssetId);
			clientFileIds.add(clientFileId);
		}

		var cameraSetId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO camera_set (
				    camera_set_id, project_id, camera_set_key, configuration_version,
				    layout_version, request_fingerprint, created_at)
				VALUES (?, ?, ?, 1, 'group-test-v1', ?, ?)
				""", cameraSetId, projectId, key("camera-set"), SHA256, Timestamp.from(now));
		var channelIds = new ArrayList<UUID>();
		for (var index = 0; index < expectedViewCount; index += 1) {
			var channelId = UUID.randomUUID();
			channelIds.add(channelId);
			this.jdbcTemplate.update("""
					INSERT INTO camera_channel (camera_channel_id, project_id, provisioning_key, created_at)
					VALUES (?, ?, ?, ?)
					""", channelId, projectId, key("channel"), Timestamp.from(now));
		}

		var captureGroupId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO capture_session (
				    capture_session_id, project_id, batch_id, device_id, camera_set_id,
				    metadata_upload_file_id, metadata_schema_version, metadata_sha256,
				    expected_camera_count, received_camera_count, sync_group_id,
				    device_time_utc, device_timezone, server_receive_time,
				    assembly_status, quality_status, capture_group_workflow, created_at, updated_at)
				VALUES (?, ?, ?, 'synthetic-group-device', ?, NULL, 'platform-local-upload-v1', NULL,
				        ?, ?, NULL, NULL, NULL, ?, ?, 'PASS', true, ?, ?)
				""", captureGroupId, projectId, batchId, cameraSetId, expectedViewCount, boundViewCount,
				Timestamp.from(now), boundViewCount == expectedViewCount ? "COMPLETE" : "INCOMPLETE",
				Timestamp.from(now), Timestamp.from(now));
		var snapshotId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO camera_set_snapshot (
				    camera_set_snapshot_id, capture_session_id, source_camera_set_id,
				    camera_set_key, configuration_version, layout_version,
				    expected_camera_count, created_at)
				VALUES (?, ?, ?, ?, 1, 'group-test-v1', ?, ?)
				""", snapshotId, captureGroupId, cameraSetId, "snapshot-" + cameraSetId, expectedViewCount,
				Timestamp.from(now));
		for (var index = 0; index < expectedViewCount; index += 1) {
			var snapshotMemberId = UUID.randomUUID();
			this.jdbcTemplate.update("""
					INSERT INTO camera_set_snapshot_member (
					    camera_set_snapshot_member_id, camera_set_snapshot_id, camera_channel_id,
					    camera_index, camera_role, mount_position, enabled, created_at)
					VALUES (?, ?, ?, ?, ?, ?, true, ?)
					""", snapshotMemberId, snapshotId, channelIds.get(index), index, index == 0 ? "OVERVIEW" : "HANDS",
					"front-" + (index + 1), Timestamp.from(now));
			if (index < boundViewCount) {
				this.jdbcTemplate.update("""
						INSERT INTO camera_stream (
						    camera_stream_id, capture_session_id, camera_set_snapshot_member_id,
						    camera_channel_id, camera_index, clip_asset_id, client_file_id,
						    record_start_utc, time_offset_ms, declared_sync_status,
						    stream_status, quality_status, created_at, updated_at)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 'PASS', 'INGESTED', 'PASS', ?, ?)
						""", UUID.randomUUID(), captureGroupId, snapshotMemberId, channelIds.get(index), index,
						clipAssetIds.get(index), clientFileIds.get(index), Timestamp.from(now), Timestamp.from(now),
						Timestamp.from(now));
				var decision = index < qualifiedViewCount ? "QUALIFIED" : "REJECTED";
				var reason = index < qualifiedViewCount ? "MEETS_REQUIREMENTS" : "WRONG_SCENE";
				this.jdbcTemplate.update("""
						INSERT INTO quality_review (
						    quality_review_id, project_id, clip_asset_id, decision, reason_code,
						    reviewed_by, reviewed_at, version)
						VALUES (?, ?, ?, ?, ?, ?, ?, 0)
						""", UUID.randomUUID(), projectId, clipAssetIds.get(index), decision, reason,
						ProjectMembershipTestData.MEMBER_ACTOR, Timestamp.from(now));
			}
		}
		if (!"RECEIVING".equals(receiptState)) {
			this.jdbcTemplate.update("""
					UPDATE capture_session
					SET receipt_state = ?, receipt_finalized_at = ?, updated_at = ?
					WHERE capture_session_id = ?
					""", receiptState, Timestamp.from(now), Timestamp.from(now), captureGroupId);
		}
		return new GroupFixture(captureGroupId, clipAssetIds);
	}

	private UUID taskId(GroupFixture group) throws Exception {
		assignTask(group, ADMIN_TOKEN, ProjectMembershipTestData.MEMBER_ACTOR, key("explicit-task-assignment"))
			.andExpect(status().isCreated());
		return this.jdbcTemplate.queryForObject(
				"SELECT annotation_task_id FROM annotation_task WHERE project_id = ? AND capture_group_id = ?",
				UUID.class, PROJECT_ID, group.captureGroupId());
	}

	private int count(String sql, Object... arguments) {
		return this.jdbcTemplate.queryForObject(sql, Integer.class, arguments);
	}

	private void clearState(UUID projectId) {
		this.jdbcTemplate.update("DELETE FROM capture_group_cleaning WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM annotation_review WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM annotation_revision WHERE project_id = ?", projectId);
		this.jdbcTemplate.execute(
				"ALTER TABLE annotation_task_view_history DISABLE TRIGGER trg_annotation_task_view_history_append_only");
		try {
			this.jdbcTemplate.update("DELETE FROM annotation_task_view_history WHERE project_id = ?", projectId);
		}
		finally {
			this.jdbcTemplate.execute(
					"ALTER TABLE annotation_task_view_history ENABLE TRIGGER trg_annotation_task_view_history_append_only");
		}
		this.jdbcTemplate.update("DELETE FROM annotation_task_view WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM annotation_task WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM capture_group_quality_review WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM quality_review WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM camera_stream WHERE capture_session_id IN "
				+ "(SELECT capture_session_id FROM capture_session WHERE project_id = ?)", projectId);
		this.jdbcTemplate.update("DELETE FROM camera_set_snapshot_member WHERE camera_set_snapshot_id IN "
				+ "(SELECT camera_set_snapshot_id FROM camera_set_snapshot WHERE capture_session_id IN "
				+ "(SELECT capture_session_id FROM capture_session WHERE project_id = ?))", projectId);
		this.jdbcTemplate.update("DELETE FROM camera_set_snapshot WHERE capture_session_id IN "
				+ "(SELECT capture_session_id FROM capture_session WHERE project_id = ?)", projectId);
		this.jdbcTemplate.update("DELETE FROM capture_session WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM camera_set_member WHERE camera_set_id IN "
				+ "(SELECT camera_set_id FROM camera_set WHERE project_id = ?)", projectId);
		this.jdbcTemplate.update("DELETE FROM camera_set WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM camera_channel WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM media_report WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM media_job WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM clip_asset WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM upload_file WHERE batch_id IN "
				+ "(SELECT batch_id FROM upload_batch WHERE project_id = ?)", projectId);
		this.jdbcTemplate.update("DELETE FROM upload_batch WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM membership WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM project WHERE project_id = ?", projectId);
	}

	private static String groupQualityPath() {
		return "/api/v1/projects/{projectId}/capture-groups/{captureGroupId}/quality-review";
	}

	private static String key(String prefix) {
		return prefix + "-" + UUID.randomUUID();
	}

	private record GroupFixture(UUID captureGroupId, List<UUID> clipAssetIds) {

		private GroupFixture {
			clipAssetIds = List.copyOf(clipAssetIds);
		}

	}

}
