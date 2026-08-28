package com.medicationvideo.platform.qualitycleaning;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
	void qualifiedWithExclusionsForFiveViewsCreatesExactlyOneGroupTaskAndRetriesIdempotently() throws Exception {
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

		var taskId = taskId(group);
		assertEquals(1,
				count("SELECT count(*) FROM annotation_task WHERE capture_group_id = ?", group.captureGroupId()));
		assertEquals(0, count("SELECT count(*) FROM annotation_task WHERE project_id = ? AND capture_group_id IS NULL",
				PROJECT_ID));
		var task = this.jdbcTemplate.queryForMap("""
				SELECT clip_asset_id, capture_group_id, assignee_actor_id, created_by,
				       primary_clip_asset_id, group_quality_revision
				FROM annotation_task
				WHERE annotation_task_id = ?
				""", taskId);
		assertEquals(null, task.get("clip_asset_id"));
		assertEquals(group.captureGroupId(), task.get("capture_group_id"));
		assertEquals(ProjectMembershipTestData.MEMBER_ACTOR, task.get("assignee_actor_id"));
		assertEquals(ProjectMembershipTestData.MEMBER_ACTOR, task.get("created_by"));
		assertEquals(null, task.get("primary_clip_asset_id"));
		assertEquals(1L, ((Number) task.get("group_quality_revision")).longValue());

		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID).header("Authorization",
					"Bearer " + MEMBER_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].can_submit").value(false))
			.andExpect(jsonPath("$.items[0].can_select_primary").value(true));
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
		assertEquals(1,
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
	void groupRevisionChainPreservesTimebaseAndViewSetAndFreezesPrimary() throws Exception {
		var group = createGroup(PROJECT_ID, 3, 3, "COMPLETE", 3);
		recordGroup(group, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("revision-qualified"))
			.andExpect(status().isOk());
		var taskId = taskId(group);
		selectPrimary(taskId, group.clipAssetIds().get(1), MEMBER_TOKEN).andExpect(status().isOk())
			.andExpect(jsonPath("$.capture_group_id").value(group.captureGroupId().toString()))
			.andExpect(jsonPath("$.primary_clip_asset_id").value(group.clipAssetIds().get(1).toString()))
			.andExpect(jsonPath("$.clip_asset_id").isEmpty());

		var first = submit(group, taskId, MEMBER_TOKEN, key("revision-first"), 10).andExpect(status().isCreated())
			.andExpect(jsonPath("$.revision_number").value(1))
			.andExpect(jsonPath("$.clip_asset_id").value(group.clipAssetIds().get(1).toString()))
			.andExpect(jsonPath("$.capture_group_id").value(group.captureGroupId().toString()))
			.andExpect(jsonPath("$.primary_clip_asset_id").value(group.clipAssetIds().get(1).toString()))
			.andExpect(jsonPath("$.confirmed_view_set.schema_version").value("capture-group-view-set.v1"))
			.andExpect(jsonPath("$.input_version").value(startsWith("capture-group-view-set.v1:")))
			.andReturn();
		var firstRevisionId = UUID
			.fromString(JsonPath.read(first.getResponse().getContentAsString(), "$.annotation_revision_id"));

		selectPrimary(taskId, group.clipAssetIds().get(2), MEMBER_TOKEN).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("ANNOTATION_PRIMARY_VIEW_FROZEN"));

		this.mockMvc
			.perform(post("/api/v1/projects/{projectId}/annotation-revisions/{revisionId}/review", PROJECT_ID,
					firstRevisionId)
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", key("revision-reject"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"decision\":\"REJECTED\",\"review_note\":\"Synthetic rework\"}"))
			.andExpect(status().isCreated());

		submit(group, taskId, MEMBER_TOKEN, key("revision-second"), 20).andExpect(status().isCreated())
			.andExpect(jsonPath("$.revision_number").value(2))
			.andExpect(jsonPath("$.primary_clip_asset_id").value(group.clipAssetIds().get(1).toString()))
			.andExpect(jsonPath("$.confirmed_view_set.schema_version").value("capture-group-view-set.v1"));
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
		selectPrimary(taskId, group.clipAssetIds().getFirst(), MEMBER_TOKEN).andExpect(status().isOk());
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
					.content("{\"decision\":\"APPROVED\",\"review_note\":\"Synthetic review\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.capture_group_id").value(group.captureGroupId().toString()))
			.andExpect(jsonPath("$.revision").value(1))
			.andExpect(jsonPath("$.primary_clip_asset_id").value(group.clipAssetIds().getFirst().toString()))
			.andExpect(jsonPath("$.qualified_views.length()").value(3))
			.andExpect(jsonPath("$.rejected_missing_views.length()").value(2))
			.andExpect(jsonPath("$.candidate_evidence.length()").value(0));

		assertEquals(1, count("SELECT count(*) FROM annotation_review WHERE annotation_revision_id = ?", revisionId));
		assertEquals(1,
				count("SELECT count(*) FROM capture_group_cleaning WHERE annotation_revision_id = ?", revisionId));
		assertEquals("PASS",
				this.jdbcTemplate.queryForObject(
						"SELECT outcome FROM capture_group_cleaning WHERE annotation_revision_id = ?", String.class,
						revisionId));
		var evidence = this.jdbcTemplate.queryForObject(
				"SELECT view_evidence::text FROM capture_group_cleaning WHERE annotation_revision_id = ?", String.class,
				revisionId);
		var excluded = this.jdbcTemplate.queryForObject(
				"SELECT excluded_views::text FROM capture_group_cleaning WHERE annotation_revision_id = ?",
				String.class, revisionId);
		assertEquals(5, JsonPath.<Integer>read(evidence, "$.length()"));
		assertEquals(2, JsonPath.<Integer>read(excluded, "$.length()"));
		assertEquals(true, JsonPath.<Boolean>read(excluded, "$[0].retained"));
		assertEquals(false, JsonPath.<Boolean>read(excluded, "$[0].downstream_included"));

		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/training-data/v3/preview", PROJECT_ID).header("Authorization",
					"Bearer " + ADMIN_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.schema_version").value("training-data.v3"))
			.andExpect(jsonPath("$.eligible_group_count").value(1))
			.andExpect(jsonPath("$.event_count").value(1))
			.andExpect(jsonPath("$.split_unit").value("capture_group"));

		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/training-data/v3/download", PROJECT_ID).header("Authorization",
					"Bearer " + ADMIN_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.schema_version").value("training-data.v3"))
			.andExpect(jsonPath("$.events.length()").value(1))
			.andExpect(jsonPath("$.events[0].capture_group_id").value(group.captureGroupId().toString()))
			.andExpect(jsonPath("$.events[0].cleaning_outcome").value("PASS"))
			.andExpect(jsonPath("$.events[0].primary_interval[0].start_ms").value(0))
			.andExpect(jsonPath("$.events[0].excluded_views.length()").value(2))
			.andExpect(jsonPath("$.events[0].mapping_provenance.length()").value(0))
			.andExpect(jsonPath("$.events[0].participating_views[1].interval_source").value("EVENT_LEVEL_EVIDENCE"))
			.andExpect(jsonPath("$.events[0].participating_views[1].action_aligned_interval").value(false))
			.andExpect(jsonPath("$.events[0].participating_views[1].interval").doesNotExist());
	}

	@Test
	void rejectedGroupReviewDoesNotCreateACleaningPass() throws Exception {
		var group = createGroup(PROJECT_ID, 1, 1, "COMPLETE", 1);
		recordGroup(group, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("review-rejected-quality"))
			.andExpect(status().isOk());
		var taskId = taskId(group);
		selectPrimary(taskId, group.clipAssetIds().getFirst(), MEMBER_TOKEN).andExpect(status().isOk());
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
					.content("{\"decision\":\"REJECTED\",\"review_note\":\"Needs rework\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.decision").value("REJECTED"))
			.andExpect(jsonPath("$.capture_group_id").value(group.captureGroupId().toString()));

		assertEquals(0,
				count("SELECT count(*) FROM capture_group_cleaning WHERE annotation_revision_id = ?", revisionId));
		assertEquals("ASSIGNED", this.jdbcTemplate.queryForObject(
				"SELECT task_status FROM annotation_task WHERE annotation_task_id = ?", String.class, taskId));
	}

	@Test
	void callerValidatedSupportMappingIsRejectedAndCannotCreateActionAlignedTime() throws Exception {
		var group = createGroup(PROJECT_ID, 2, 2, "COMPLETE", 2);
		recordGroup(group, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("mapping-quality"))
			.andExpect(status().isOk());
		var taskId = taskId(group);
		selectPrimary(taskId, group.clipAssetIds().getFirst(), MEMBER_TOKEN).andExpect(status().isOk());
		var revision = submit(group, taskId, MEMBER_TOKEN, key("mapping-submit"), 400).andExpect(status().isCreated())
			.andReturn();
		var revisionId = UUID
			.fromString(JsonPath.read(revision.getResponse().getContentAsString(), "$.annotation_revision_id"));
		this.mockMvc.perform(
				post("/api/v1/projects/{projectId}/annotation-revisions/{revisionId}/review", PROJECT_ID, revisionId)
					.header("Authorization", "Bearer " + ADMIN_TOKEN)
					.header("Idempotency-Key", key("mapping-approve"))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"decision\":\"APPROVED\"}"))
			.andExpect(status().isCreated());

		this.mockMvc.perform(
				post("/api/v1/projects/{projectId}/annotation-revisions/{revisionId}/capture-group-cleaning-rechecks",
						PROJECT_ID, revisionId)
					.header("Authorization", "Bearer " + ADMIN_TOKEN)
					.header("X-Request-Id", key("mapping-recheck-request"))
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"support_mappings":[{"clip_asset_id":"%s","validated":true,
							"mapping_version":"capture-map.v1","provenance":"synthetic-validated-mapping",
							"intervals":[{"start_ms":120,"end_ms":420}]}]}
							""".formatted(group.clipAssetIds().get(1))))
			.andExpect(status().is(422))
			.andExpect(jsonPath("$.error_code").value("ANNOTATION_GROUP_CLEANING_SUPPORT_MAPPINGS_UNSUPPORTED"));

		assertEquals(1,
				count("SELECT count(*) FROM capture_group_cleaning WHERE annotation_revision_id = ?", revisionId));

		this.mockMvc
			.perform(post(
					"/api/v1/projects/{projectId}/annotation-revisions/{revisionId}/capture-group-cleaning-rechecks",
					PROJECT_ID, revisionId)
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("X-Request-Id", key("mapping-empty-recheck-request"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"support_mappings\":[]}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("PASS"))
			.andExpect(jsonPath("$.primary_interval[0].start_ms").value(0))
			.andExpect(jsonPath("$.primary_interval[0].end_ms").value(400))
			.andExpect(jsonPath("$.mapping_provenance.length()").value(0))
			.andExpect(jsonPath("$.participating_views[1].interval_source").value("EVENT_LEVEL_EVIDENCE"))
			.andExpect(jsonPath("$.participating_views[1].action_aligned_interval").value(false))
			.andExpect(jsonPath("$.participating_views[1].interval").doesNotExist());
	}

	@Test
	void qualityDowngradeBlocksNewRevisionAndRequalificationKeepsTheTask() throws Exception {
		var group = createGroup(PROJECT_ID, 2, 2, "COMPLETE", 2);
		recordGroup(group, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("downgrade-qualified"))
			.andExpect(status().isOk());
		var taskId = taskId(group);
		selectPrimary(taskId, group.clipAssetIds().getFirst(), MEMBER_TOKEN).andExpect(status().isOk());

		recordGroup(group, MEMBER_TOKEN, "NEEDS_REVIEW", "View quality changed", 1, key("downgrade-review"))
			.andExpect(status().isOk());
		recordGroup(group, MEMBER_TOKEN, "REJECTED", "View quality rejected", 2, key("downgrade-rejected"))
			.andExpect(status().isOk());
		submit(group, taskId, MEMBER_TOKEN, key("downgrade-blocked"), 10).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("ANNOTATION_TASK_GROUP_QUALITY_NOT_QUALIFIED"));
		assertEquals(0, count("SELECT count(*) FROM annotation_revision WHERE annotation_task_id = ?", taskId));

		recordGroup(group, MEMBER_TOKEN, "QUALIFIED", "View quality rechecked", 3, key("downgrade-requalified"))
			.andExpect(status().isOk());
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
		selectPrimary(taskId, group.clipAssetIds().getFirst(), MEMBER_TOKEN).andExpect(status().isOk());
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

		assertEquals("ASSIGNED", this.jdbcTemplate.queryForObject(
				"SELECT task_status FROM annotation_task WHERE annotation_task_id = ?", String.class, taskId));
		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID).header("Authorization",
					"Bearer " + MEMBER_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].status").value("ASSIGNED"))
			.andExpect(jsonPath("$.items[0].can_submit").value(true))
			.andExpect(jsonPath("$.items[0].annotation_revision").isEmpty());

		this.mockMvc
			.perform(post("/api/v1/projects/{projectId}/annotation-revisions/{revisionId}/review", PROJECT_ID,
					firstRevisionId)
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", key("stale-review"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"decision\":\"APPROVED\"}"))
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
	void frozenPrimaryBecomingIneligibleBlocksSubmissionWithoutFallback() throws Exception {
		var group = createGroup(PROJECT_ID, 2, 2, "COMPLETE", 2);
		recordGroup(group, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("primary-ineligible-qualified"))
			.andExpect(status().isOk());
		var taskId = taskId(group);
		var primaryClipAssetId = group.clipAssetIds().getFirst();
		selectPrimary(taskId, primaryClipAssetId, MEMBER_TOKEN).andExpect(status().isOk());
		submit(group, taskId, MEMBER_TOKEN, key("primary-ineligible-first"), 100).andExpect(status().isCreated())
			.andExpect(jsonPath("$.revision_number").value(1));

		recordViewQuality(primaryClipAssetId, MEMBER_TOKEN, "REJECTED", "WRONG_SCENE", 0).andExpect(status().isOk());
		recordGroup(group, MEMBER_TOKEN, "NEEDS_REVIEW", "Primary view changed", 1, key("primary-ineligible-review"))
			.andExpect(status().isOk());
		recordGroup(group, MEMBER_TOKEN, "QUALIFIED_WITH_EXCLUSIONS", "Primary view no longer eligible", 2,
				key("primary-ineligible-requalified"))
			.andExpect(status().isOk());

		submit(group, taskId, MEMBER_TOKEN, key("primary-ineligible-second"), 120).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("ANNOTATION_PRIMARY_VIEW_NOT_ELIGIBLE"));
		selectPrimary(taskId, group.clipAssetIds().getLast(), MEMBER_TOKEN).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("ANNOTATION_PRIMARY_VIEW_FROZEN"));
		assertEquals(primaryClipAssetId, this.jdbcTemplate.queryForObject(
				"SELECT primary_clip_asset_id FROM annotation_task WHERE annotation_task_id = ?", UUID.class, taskId));
		assertEquals(1, count("SELECT count(*) FROM annotation_revision WHERE annotation_task_id = ?", taskId));
	}

	@Test
	void changedGroupInputAppendsRevisionWhileSameInputDoesNotCreateOne() throws Exception {
		var group = createGroup(PROJECT_ID, 2, 2, "COMPLETE", 2);
		recordGroup(group, MEMBER_TOKEN, "QUALIFIED", BASE_REASON, 0, key("input-qualified"))
			.andExpect(status().isOk());
		var taskId = taskId(group);
		selectPrimary(taskId, group.clipAssetIds().getFirst(), MEMBER_TOKEN).andExpect(status().isOk());
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
				.content("{\"decision\":\"APPROVED\"}"))
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
		assertEquals("ASSIGNED", this.jdbcTemplate.queryForObject(
				"SELECT task_status FROM annotation_task WHERE annotation_task_id = ?", String.class, taskId));
		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID).header("Authorization",
					"Bearer " + ADMIN_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].annotation_revision").isEmpty());

		submit(group, taskId, MEMBER_TOKEN, key("input-second-submit"), 120).andExpect(status().isCreated())
			.andExpect(jsonPath("$.revision_number").value(2))
			.andExpect(jsonPath("$.primary_clip_asset_id").value(group.clipAssetIds().getFirst().toString()));
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
		selectPrimary(taskId, group.clipAssetIds().getFirst(), ADMIN_TOKEN).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error_code").value("ANNOTATION_TASK_NOT_ASSIGNEE"));

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

	private ResultActions selectPrimary(UUID taskId, UUID clipAssetId, String token) throws Exception {
		return this.mockMvc
			.perform(put("/api/v1/projects/{projectId}/annotation-tasks/{taskId}/primary-view", PROJECT_ID, taskId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"clip_asset_id\":\"%s\"}".formatted(clipAssetId)));
	}

	private ResultActions submit(GroupFixture group, UUID taskId, String token, String idempotencyKey, int endMs)
			throws Exception {
		return this.mockMvc
			.perform(post("/api/v1/projects/{projectId}/annotation-tasks/{taskId}/revisions", PROJECT_ID, taskId)
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", key("submit-request"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"segments\":[{\"start_ms\":0,\"end_ms\":%d,\"label\":\"prepare_workspace\"}]}"
					.formatted(endMs)));
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

	private UUID taskId(GroupFixture group) {
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
