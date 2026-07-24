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
				.andExpect(jsonPath("$.assignee_actor_id").value(ProjectMembershipTestData.MEMBER_ACTOR))
				.andExpect(jsonPath("$.status").value("ASSIGNED"));

		this.mockMvc.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID)
				.header("Authorization", "Bearer " + MEMBER_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].clip_asset_id").value(clipAssetId.toString()));

		assertEquals(1, count("""
				SELECT count(*) FROM audit_event
				WHERE action = 'ANNOTATION_TASK_CREATED'
				  AND object_type = 'AnnotationTask'
				  AND details_json ->> 'clip_asset_id' = ?
				""", clipAssetId.toString()));
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
				{"segments":[{"start_ms":120,"end_ms":780,"label":"拿取药品"}],"note":"合成验证标注"}
				""")
				.andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.annotation_task_id").value(taskId.toString()))
				.andExpect(jsonPath("$.clip_asset_id").value(clipAssetId.toString()))
				.andExpect(jsonPath("$.revision_number").value(1))
				.andExpect(jsonPath("$.annotation_schema_version").value("annotation-document.v1"))
				.andReturn().getResponse().getContentAsString();
		var revisionId = com.jayway.jsonpath.JsonPath.<String>read(response, "$.annotation_revision_id");

		submit(MEMBER_TOKEN, taskId, idempotencyKey,
				"""
				{"segments":[{"start_ms":120,"end_ms":780,"label":"拿取药品"}],"note":"合成验证标注"}
				""")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.annotation_revision_id").value(revisionId));
		submit(ADMIN_TOKEN, taskId, "annotation-revision-admin-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":1,\"end_ms\":2,\"label\":\"x\"}]}")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("ANNOTATION_TASK_NOT_ASSIGNEE"));
		submit(MEMBER_TOKEN, taskId, idempotencyKey,
				"{\"segments\":[{\"start_ms\":1,\"end_ms\":2,\"label\":\"changed\"}]}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("ANNOTATION_REVISION_IDEMPOTENCY_KEY_REUSED"));
		submit(MEMBER_TOKEN, taskId, "annotation-revision-new-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":1,\"end_ms\":2,\"label\":\"x\"}]}")
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
				"{\"segments\":[{\"start_ms\":100,\"end_ms\":100,\"label\":\"无效\"}]}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error_code").value("ANNOTATION_SEGMENT_TIME_RANGE_INVALID"));
		assertEquals("ASSIGNED", this.jdbcTemplate.queryForObject(
				"SELECT task_status FROM annotation_task WHERE annotation_task_id = ?", String.class, taskId));
		assertEquals(0, count("SELECT count(*) FROM annotation_revision WHERE annotation_task_id = ?", taskId));
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
				"{\"segments\":[{\"start_ms\":100,\"end_ms\":501,\"label\":\"超出时长\"}]}")
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
		var taskResponse = create(ADMIN_TOKEN, clipAssetId, ProjectMembershipTestData.MEMBER_ACTOR,
				"annotation-task-review-" + UUID.randomUUID())
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var taskId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(taskResponse, "$.annotation_task_id"));
		var revisionResponse = submit(MEMBER_TOKEN, taskId, "annotation-revision-review-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":120,\"end_ms\":780,\"label\":\"拿取药品\"}]}")
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var revisionId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(revisionResponse, "$.annotation_revision_id"));
		var idempotencyKey = "annotation-review-" + UUID.randomUUID();

		this.mockMvc.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].annotation_revision.annotation_revision_id").value(revisionId.toString()))
				.andExpect(jsonPath("$.items[0].annotation_revision.annotation_document.segments.length()").value(1))
				.andExpect(jsonPath("$.items[0].can_review").value(true));

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
				.andExpect(jsonPath("$.items[0].annotation_revision.review.decision").value("APPROVED"))
				.andExpect(jsonPath("$.items[0].can_review").value(false));

		assertEquals(1, count("SELECT count(*) FROM annotation_review WHERE annotation_revision_id = ?", revisionId));
		assertEquals(1, count("""
				SELECT count(*) FROM audit_event
				WHERE action = 'ANNOTATION_REVISION_REVIEWED'
				  AND object_type = 'AnnotationReview'
				  AND object_id = ?
				""", reviewId));
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
				"{\"segments\":[{\"start_ms\":1,\"end_ms\":2,\"label\":\"拿取药品\"}]}")
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
				"{\"segments\":[{\"start_ms\":100,\"end_ms\":200,\"label\":\"错误标签\"}]}")
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
				.andExpect(jsonPath("$.items[0].annotation_revision.review.decision").value("REJECTED"));

		submit(MEMBER_TOKEN, taskId, "annotation-revision-second-" + UUID.randomUUID(),
				"{\"segments\":[{\"start_ms\":120,\"end_ms\":360,\"label\":\"拿取药品\"}]}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.revision_number").value(2));
		assertEquals(2, count("SELECT count(*) FROM annotation_revision WHERE annotation_task_id = ?", taskId));
		assertEquals(1, count("SELECT count(*) FROM annotation_review WHERE annotation_revision_id = ?", firstRevisionId));
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
				.andExpect(jsonPath("$.items[0].assignee_actor_id").value(ProjectMembershipTestData.MEMBER_ACTOR));
		this.mockMvc.perform(get("/api/v1/projects/{projectId}/annotation-tasks", PROJECT_ID)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2));
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

	private void insertVerifiedMediaReport(UUID projectId, UUID clipAssetId, long durationMicroseconds) {
		var mediaJobId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO media_job (
				    media_job_id, project_id, clip_asset_id, job_type, input_digest, input_size_bytes,
				    content_type, status, idempotency_key, request_fingerprint, created_at, updated_at,
				    version, probe_profile_key, recipe_digest)
				VALUES (?, ?, ?, 'MEDIA_PROBE', ?, 1234, 'video/mp4', 'SUCCEEDED', ?, ?, ?, ?, 0,
				        'media-probe-baseline-v1',
				        '8900937958d0fb81a9c9bf2d9e81ac3e57f35d838d5a4da52b58ccf0f5cd5765')
				""",
				mediaJobId, projectId, clipAssetId, SHA256, "annotation-media-job-" + mediaJobId,
				SHA256, Timestamp.from(BASE_TIME), Timestamp.from(BASE_TIME));
		this.jdbcTemplate.update("""
				INSERT INTO media_report (
				    media_report_id, media_job_id, project_id, clip_asset_id, report_schema_version,
				    input_digest, recipe_digest, software_digest, container_format, duration_microseconds,
				    video_stream_count, primary_video_stream_index, video_codec, width, height,
				    time_base_numerator, time_base_denominator, variable_frame_rate, audio_stream_count,
				    decode_verified, input_digest_verified, warning_codes, created_at)
				VALUES (?, ?, ?, ?, 'media-report.v1', ?,
				        '8900937958d0fb81a9c9bf2d9e81ac3e57f35d838d5a4da52b58ccf0f5cd5765',
				        'sha256:207391c770bee4a8dfe3530ef5f31af07904950f9bc2dcb822fedb086222164a',
				        'mov,mp4,m4a,3gp,3g2,mj2', ?, 1, 0, 'h264', 640, 360, 1, 90000, false, 0,
				        true, true, '[]'::jsonb, ?)
				""",
				UUID.randomUUID(), mediaJobId, projectId, clipAssetId, SHA256, durationMicroseconds,
				Timestamp.from(BASE_TIME));
	}

	private int count(String sql, Object... arguments) {
		return this.jdbcTemplate.queryForObject(sql, Integer.class, arguments);
	}

	private void clearState(UUID projectId) {
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
		this.jdbcTemplate.update("DELETE FROM membership WHERE project_id = ?", projectId);
		this.jdbcTemplate.update("DELETE FROM project WHERE project_id = ?", projectId);
	}
}
