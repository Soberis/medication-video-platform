package com.medicationvideo.platform.iamproject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectMembershipContractTests {

	private static final String ADMIN_TOKEN =
			"synthetic-test-admin-bearer-token-do-not-reuse";
	private static final String MEMBER_TOKEN =
			"synthetic-test-bearer-token-do-not-reuse";
	private static final String ADMIN_ACTOR = "local-synthetic-admin";
	private static final String MEMBER_ACTOR = "local-synthetic-uploader";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoSpyBean
	private DataProjectWriter projectWriter;

	@MockitoSpyBean
	private AuditEventRecorder auditRecorder;

	@BeforeEach
	void clearProjects() {
		clearProjectData();
	}

	@AfterEach
	void removeProjects() {
		clearProjectData();
	}

	private void clearProjectData() {
		this.jdbcTemplate.update("DELETE FROM project_membership_command");
		this.jdbcTemplate.update("DELETE FROM membership");
		this.jdbcTemplate.update("DELETE FROM project");
	}

	@Test
	void administratorCreatesProjectIdempotentlyWithCreatorMembershipAndAudit() throws Exception {
		var response = createProject(
				ADMIN_TOKEN,
				"project-create-idem-0001",
				"project-create-request-0001",
				"Synthetic Pharmacy Study")
				.andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.display_name").value("Synthetic Pharmacy Study"))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.current_role").value("ADMIN"))
				.andReturn()
				.getResponse()
				.getContentAsString();
		var projectId = UUID.fromString(JsonPath.read(response, "$.project_id"));

		var replay = createProject(
				ADMIN_TOKEN,
				"project-create-idem-0001",
				"project-create-request-0002",
				"Synthetic Pharmacy Study")
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString();
		assertEquals(projectId.toString(), JsonPath.read(replay, "$.project_id"));

		createProject(
				ADMIN_TOKEN,
				"project-create-idem-0001",
				"project-create-request-0003",
				"Different Study")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code")
						.value("PROJECT_IDEMPOTENCY_KEY_REUSED"));

		createProject(
				MEMBER_TOKEN,
				"project-create-member-0001",
				"project-create-request-0004",
				"Forbidden Study")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("PROJECT_ACCESS_DENIED"));

		assertEquals(1, count("SELECT count(*) FROM project WHERE project_id = ?", projectId));
		assertEquals(1, this.jdbcTemplate.queryForObject(
				"""
				SELECT count(*) FROM membership
				WHERE project_id = ? AND actor_id = ? AND role = 'ADMIN' AND status = 'ACTIVE'
				""",
				Integer.class,
				projectId,
				ADMIN_ACTOR));
		assertEquals(1, countAudit(projectId, "PROJECT_CREATED"));
		assertEquals(1, countAudit(projectId, "PROJECT_MEMBERSHIP_GRANTED"));
		this.mockMvc.perform(get("/api/v1/projects")
					.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.can_create_projects").value(true));

		this.mockMvc.perform(get("/api/v1/projects")
					.header("Authorization", "Bearer " + MEMBER_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isEmpty())
				.andExpect(jsonPath("$.can_create_projects").value(false));
	}

	@Test
	void administratorManagesMembershipAndMemberSeesOnlyAuthorizedProjects() throws Exception {
		var firstProjectId = createProjectId(
				"project-membership-study-0001", "Synthetic Membership Study");
		var secondProjectId = createProjectId(
				"project-membership-study-0002", "Synthetic Hidden Study");

		upsertMembership(
				firstProjectId,
				MEMBER_ACTOR,
				"membership-grant-idem-0001",
				"MEMBER")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.actor_id").value(MEMBER_ACTOR))
				.andExpect(jsonPath("$.role").value("MEMBER"))
				.andExpect(jsonPath("$.status").value("ACTIVE"));

		this.mockMvc.perform(get("/api/v1/projects/{projectId}/memberships", firstProjectId)
					.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].actor_id").value(ADMIN_ACTOR))
				.andExpect(jsonPath("$.items[1].actor_id").value(MEMBER_ACTOR));

		upsertMembership(
				firstProjectId,
				MEMBER_ACTOR,
				"membership-grant-idem-0001",
				"ADMIN")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code")
						.value("MEMBERSHIP_IDEMPOTENCY_KEY_REUSED"));

		this.mockMvc.perform(get("/api/v1/projects")
					.header("Authorization", "Bearer " + MEMBER_TOKEN))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].project_id")
						.value(firstProjectId.toString()))
				.andExpect(jsonPath("$.items[0].current_role").value("MEMBER"))
				.andExpect(jsonPath("$.can_create_projects").value(false));

		this.mockMvc.perform(get("/api/v1/projects/{projectId}", firstProjectId)
					.header("Authorization", "Bearer " + MEMBER_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.current_role").value("MEMBER"));
		this.mockMvc.perform(get("/api/v1/projects/{projectId}", secondProjectId)
					.header("Authorization", "Bearer " + MEMBER_TOKEN))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error_code").value("PROJECT_NOT_FOUND"));
		this.mockMvc.perform(get("/api/v1/projects/{projectId}/memberships", firstProjectId)
					.header("Authorization", "Bearer " + MEMBER_TOKEN))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("PROJECT_ACCESS_DENIED"));

		upsertMembership(
				firstProjectId,
				MEMBER_ACTOR,
				"membership-role-idem-0001",
				"ADMIN")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("ADMIN"))
				.andExpect(jsonPath("$.version").value(1));
		assertEquals(1, countAudit(firstProjectId, "PROJECT_MEMBERSHIP_ROLE_CHANGED"));
	}

	@Test
	void membershipRemovalIsIdempotentAndProjectRetainsAnAdministrator() throws Exception {
		var projectId = createProjectId(
				"project-removal-study-0001", "Synthetic Removal Study");
		var secondAdmin = "synthetic-second-admin";
		upsertMembership(
				projectId,
				secondAdmin,
				"membership-admin-idem-0001",
				"ADMIN")
				.andExpect(status().isOk());

		var removed = removeMembership(
				projectId, secondAdmin, "membership-remove-idem-0001")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REMOVED"))
				.andReturn()
				.getResponse()
				.getContentAsString();
		var removedVersion = JsonPath.read(removed, "$.version");
		removeMembership(projectId, secondAdmin, "membership-remove-idem-0001")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REMOVED"))
				.andExpect(jsonPath("$.version").value(removedVersion));
		assertEquals(1, countAudit(projectId, "PROJECT_MEMBERSHIP_REMOVED"));

		removeMembership(projectId, ADMIN_ACTOR, "membership-remove-last-0001")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("LAST_PROJECT_ADMIN"));

		upsertMembership(
				projectId,
				"synthetic-invalid-role",
				"membership-invalid-role-0001",
				"OWNER")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error_code").value("INVALID_REQUEST"));

		var invalidRole = assertThrows(
				DataIntegrityViolationException.class,
				() -> insertMembershipWithRole(projectId, "synthetic-database-role", "OWNER"));
		assertTrue(invalidRole.getMostSpecificCause().getMessage()
				.contains("ck_membership_role"));
	}

	@Test
	void administratorCanRenameAndArchiveAProjectWithoutDestroyingItsRecords() throws Exception {
		var projectId = createProjectId(
				"project-archive-study-0001", "Synthetic Archive Study");
		upsertMembership(
				projectId,
				MEMBER_ACTOR,
				"project-archive-member-0001",
				"MEMBER")
				.andExpect(status().isOk());

		this.mockMvc.perform(put("/api/v1/projects/{projectId}", projectId)
					.header("Authorization", "Bearer " + ADMIN_TOKEN)
					.header("X-Request-Id", "project-rename-request-0001")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"display_name\":\"Synthetic Archive Study Renamed\"}"))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.display_name").value("Synthetic Archive Study Renamed"))
				.andExpect(jsonPath("$.status").value("ACTIVE"));
		assertEquals(1, countAudit(projectId, "PROJECT_RENAMED"));

		this.mockMvc.perform(put("/api/v1/projects/{projectId}", projectId)
					.header("Authorization", "Bearer " + MEMBER_TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"display_name\":\"Forbidden Rename\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("PROJECT_ACCESS_DENIED"));

		this.mockMvc.perform(delete("/api/v1/projects/{projectId}", projectId)
					.header("Authorization", "Bearer " + ADMIN_TOKEN)
					.header("X-Request-Id", "project-archive-request-0001"))
				.andExpect(status().isNoContent())
				.andExpect(header().string("Cache-Control", "no-store"));
		assertEquals("ARCHIVED", this.jdbcTemplate.queryForObject(
				"SELECT status FROM project WHERE project_id = ?", String.class, projectId));
		assertEquals(1, count("SELECT count(*) FROM project WHERE project_id = ?", projectId));
		assertEquals(2, count("SELECT count(*) FROM membership WHERE project_id = ?", projectId));
		assertEquals(1, countAudit(projectId, "PROJECT_ARCHIVED"));

		this.mockMvc.perform(get("/api/v1/projects")
					.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isEmpty());
		this.mockMvc.perform(get("/api/v1/projects/{projectId}", projectId)
					.header("Authorization", "Bearer " + ADMIN_TOKEN))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error_code").value("PROJECT_NOT_FOUND"));

		this.mockMvc.perform(delete("/api/v1/projects/{projectId}", projectId)
					.header("Authorization", "Bearer " + ADMIN_TOKEN)
					.header("X-Request-Id", "project-archive-request-0002"))
				.andExpect(status().isNoContent());
		assertEquals(1, countAudit(projectId, "PROJECT_ARCHIVED"));
	}

	@Test
	void concurrentProjectCreationProducesOneProjectMembershipAndAuditPair() throws Exception {
		var concurrency = 4;
		var insertReady = new CountDownLatch(concurrency);
		var insertOutcomes = new ConcurrentLinkedQueue<Boolean>();
		doAnswer(invocation -> {
			insertReady.countDown();
			if (!insertReady.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException(
						"All concurrent project commands did not reach the insert boundary.");
			}
			var inserted = (boolean) invocation.callRealMethod();
			insertOutcomes.add(inserted);
			return inserted;
		}).when(this.projectWriter).insertIfAbsent(any(DataProject.class));

		var ready = new CountDownLatch(concurrency);
		var start = new CountDownLatch(1);
		var responses = new ConcurrentLinkedQueue<String>();
		try (var executor = Executors.newFixedThreadPool(concurrency)) {
			var futures = new ArrayList<java.util.concurrent.Future<Void>>();
			for (var index = 0; index < concurrency; index++) {
				var requestIndex = index;
				futures.add(executor.submit(() -> {
					ready.countDown();
					if (!start.await(10, TimeUnit.SECONDS)) {
						throw new IllegalStateException(
								"Concurrent project requests did not start in time.");
					}
					responses.add(createProject(
							ADMIN_TOKEN,
							"project-concurrent-0001",
							"project-concurrent-request-" + requestIndex,
							"Synthetic Concurrent Study")
							.andExpect(status().isCreated())
							.andReturn()
							.getResponse()
							.getContentAsString());
					return null;
				}));
			}
			assertTrue(ready.await(10, TimeUnit.SECONDS));
			start.countDown();
			for (var future : futures) {
				future.get(20, TimeUnit.SECONDS);
			}
		}

		assertEquals(concurrency, responses.size());
		var projectIds = responses.stream()
				.map(response -> JsonPath.<String>read(response, "$.project_id"))
				.distinct()
				.toList();
		assertEquals(1, projectIds.size());
		assertEquals(concurrency, insertOutcomes.size());
		assertEquals(1L, insertOutcomes.stream().filter(Boolean::booleanValue).count());
		var projectId = UUID.fromString(projectIds.getFirst());
		assertEquals(1, count("SELECT count(*) FROM project WHERE project_id = ?", projectId));
		assertEquals(1, count("SELECT count(*) FROM membership WHERE project_id = ?", projectId));
		assertEquals(1, countAudit(projectId, "PROJECT_CREATED"));
		assertEquals(1, countAudit(projectId, "PROJECT_MEMBERSHIP_GRANTED"));
	}

	@Test
	void auditFailureRollsBackProjectAndCreatorMembership() throws Exception {
		var auditCalls = new AtomicInteger();
		doAnswer(invocation -> {
			if (auditCalls.incrementAndGet() == 2) {
				throw new IllegalStateException("Synthetic project audit failure.");
			}
			return invocation.callRealMethod();
		}).when(this.auditRecorder).record(any(AuditEventCommand.class));

		createProject(
				ADMIN_TOKEN,
				"project-audit-rollback-0001",
				"project-audit-rollback-request-0001",
				"Synthetic Rollback Study")
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error_code").value("INTERNAL_ERROR"));

		assertEquals(2, auditCalls.get());
		assertEquals(0, count("SELECT count(*) FROM project"));
		assertEquals(0, count("SELECT count(*) FROM membership"));
		assertEquals(0, this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_event WHERE request_id = ?",
				Integer.class,
				"project-audit-rollback-request-0001"));
	}

	private ResultActions createProject(
			String token,
			String idempotencyKey,
			String requestId,
			String displayName) throws Exception {
		return this.mockMvc.perform(post("/api/v1/projects")
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", requestId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"display_name":"%s"}
						""".formatted(displayName)));
	}

	private UUID createProjectId(String idempotencyKey, String displayName) throws Exception {
		var response = createProject(
				ADMIN_TOKEN,
				idempotencyKey,
				idempotencyKey + "-request",
				displayName)
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString();
		return UUID.fromString(JsonPath.read(response, "$.project_id"));
	}

	private ResultActions upsertMembership(
			UUID projectId,
			String actorId,
			String idempotencyKey,
			String role) throws Exception {
		return this.mockMvc.perform(put(
					"/api/v1/projects/{projectId}/memberships/{actorId}",
					projectId,
					actorId)
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", idempotencyKey + "-request")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"role":"%s"}
						""".formatted(role)));
	}

	private ResultActions removeMembership(
			UUID projectId,
			String actorId,
			String idempotencyKey) throws Exception {
		return this.mockMvc.perform(delete(
					"/api/v1/projects/{projectId}/memberships/{actorId}",
					projectId,
					actorId)
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", idempotencyKey + "-request"));
	}

	private int count(String sql, Object... arguments) {
		return this.jdbcTemplate.queryForObject(sql, Integer.class, arguments);
	}

	private int countAudit(UUID projectId, String action) {
		return this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_event WHERE project_id = ? AND action = ?",
				Integer.class,
				projectId,
				action);
	}

	private void insertMembershipWithRole(UUID projectId, String actorId, String role) {
		var now = Timestamp.from(Instant.parse("2026-07-17T11:00:00Z"));
		this.jdbcTemplate.update("""
				INSERT INTO membership (
				    membership_id, project_id, actor_id, role, status,
				    created_by, created_at, updated_by, updated_at, row_version)
				VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, 0)
				""",
				UUID.randomUUID(),
				projectId,
				actorId,
				role,
				ADMIN_ACTOR,
				now,
				ADMIN_ACTOR,
				now);
	}
}
