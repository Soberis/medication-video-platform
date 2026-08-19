package com.medicationvideo.platform.assetcapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.json.JsonMapper;

import com.medicationvideo.platform.support.ProjectMembershipTestData;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UploadBatchContractTests {

	private static final String TOKEN = "synthetic-test-bearer-token-do-not-reuse";

	private static final String ADMIN_TOKEN = "synthetic-test-admin-bearer-token-do-not-reuse";

	private static final String PROJECT_ID = "00000000-0000-0000-0000-000000000001";

	private static final String OTHER_PROJECT_ID = "00000000-0000-0000-0000-000000000002";

	private static final String MANIFEST_SHA256 = "a".repeat(64);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UploadBatchRepository repository;

	@Autowired
	private UploadFileRepository fileRepository;

	@Autowired
	private UploadPartRepository partRepository;

	@Autowired
	private ClipAssetRepository clipAssetRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private Environment environment;

	@Autowired
	private JsonMapper jsonMapper;

	@BeforeEach
	void clearUploadBatches() {
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, java.util.UUID.fromString(PROJECT_ID));
		this.clipAssetRepository.deleteAll();
		this.partRepository.deleteAll();
		this.fileRepository.deleteAll();
		this.repository.deleteAll();
	}

	@Test
	void appliesTheSnakeCaseJsonContract() throws Exception {
		assertEquals("SNAKE_CASE", this.environment.getProperty("spring.jackson.property-naming-strategy"));
		assertEquals("false", this.environment.getProperty("spring.jpa.open-in-view"));

		var request = this.jsonMapper.readValue(validRequest(PROJECT_ID, 1L), UploadBatchInitRequest.class);
		assertEquals("synthetic-device-01", request.deviceId());

		var errorJson = this.jsonMapper
			.writeValueAsString(new ApiErrorResponse("INVALID_REQUEST", "invalid", false, "request-1", null));
		assertTrue(errorJson.contains("\"error_code\""));
	}

	@Test
	void initializesAndIdempotentlyReplaysTheSameBatch() throws Exception {
		var request = validRequest(PROJECT_ID, 5_368_709_120L);
		var first = this.mockMvc
			.perform(post("/api/v1/upload/init").header("Authorization", "Bearer " + TOKEN)
				.header("Idempotency-Key", "batch-init-key-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
			.andExpect(status().isCreated())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.batch_id").isNotEmpty())
			.andExpect(jsonPath("$.project_id").value(PROJECT_ID))
			.andExpect(jsonPath("$.device_id").value("synthetic-device-01"))
			.andExpect(jsonPath("$.file_count").value(2))
			.andExpect(jsonPath("$.total_size_bytes").value(5_368_709_120L))
			.andExpect(jsonPath("$.manifest_sha256").value(MANIFEST_SHA256))
			.andExpect(jsonPath("$.status").value("INITIATED"))
			.andExpect(jsonPath("$.expires_at").isNotEmpty())
			.andExpect(jsonPath("$.created_at").isNotEmpty())
			.andReturn();

		var replay = this.mockMvc
			.perform(post("/api/v1/upload/init").header("Authorization", "Bearer " + TOKEN)
				.header("Idempotency-Key", "batch-init-key-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
			.andExpect(status().isCreated())
			.andReturn();

		assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
		assertEquals(1, this.repository.count());
	}

	@Test
	void initializesOnePlatformLocalVideoBatch() throws Exception {
		var request = """
				{
				  "project_id": "%s",
				  "device_id": "PLATFORM_LOCAL",
				  "upload_source_type": "PLATFORM_LOCAL",
				  "file_count": 1,
				  "total_size_bytes": 8388608,
				  "manifest_sha256": "%s"
				}
				""".formatted(PROJECT_ID, MANIFEST_SHA256);

		this.mockMvc
			.perform(post("/api/v1/upload/init").header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", "platform-local-init-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.device_id").value("PLATFORM_LOCAL"))
			.andExpect(jsonPath("$.upload_source_type").value("PLATFORM_LOCAL"))
			.andExpect(jsonPath("$.file_count").value(1));
	}

	@Test
	void rejectsPlatformLocalUploadForProjectMember() throws Exception {
		var request = """
				{
				  "project_id": "%s",
				  "device_id": "PLATFORM_LOCAL",
				  "upload_source_type": "PLATFORM_LOCAL",
				  "file_count": 1,
				  "total_size_bytes": 8388608,
				  "manifest_sha256": "%s"
				}
				""".formatted(PROJECT_ID, MANIFEST_SHA256);

		this.mockMvc
			.perform(post("/api/v1/upload/init").header("Authorization", "Bearer " + TOKEN)
				.header("Idempotency-Key", "platform-local-member-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error_code").value("PROJECT_ACCESS_DENIED"));

		assertEquals(0, this.repository.count());
	}

	@Test
	void rejectsProjectMemberFromFollowingAnExistingPlatformLocalBatch() throws Exception {
		var request = """
				{
				  "project_id": "%s",
				  "device_id": "PLATFORM_LOCAL",
				  "upload_source_type": "PLATFORM_LOCAL",
				  "file_count": 1,
				  "total_size_bytes": 8388608,
				  "manifest_sha256": "%s"
				}
				""".formatted(PROJECT_ID, MANIFEST_SHA256);
		var response = this.mockMvc
			.perform(post("/api/v1/upload/init").header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", "platform-local-follow-up-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		var batchId = JsonPath.<String>read(response, "$.batch_id");

		this.mockMvc.perform(get("/api/v1/upload/{batchId}", batchId).header("Authorization", "Bearer " + TOKEN))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error_code").value("PROJECT_ACCESS_DENIED"));
	}

	@Test
	void rejectsMultipleFilesInOnePlatformLocalBatch() throws Exception {
		var request = """
				{
				  "project_id": "%s",
				  "device_id": "PLATFORM_LOCAL",
				  "upload_source_type": "PLATFORM_LOCAL",
				  "file_count": 2,
				  "total_size_bytes": 16777216,
				  "manifest_sha256": "%s"
				}
				""".formatted(PROJECT_ID, MANIFEST_SHA256);

		this.mockMvc
			.perform(post("/api/v1/upload/init").header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", "platform-local-init-0002")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error_code").value("PLATFORM_LOCAL_SINGLE_VIDEO_REQUIRED"));
	}

	@Test
	void rejectsReusingAnIdempotencyKeyForDifferentContent() throws Exception {
		this.mockMvc
			.perform(post("/api/v1/upload/init").header("Authorization", "Bearer " + TOKEN)
				.header("Idempotency-Key", "batch-init-key-0002")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest(PROJECT_ID, 5_368_709_120L)))
			.andExpect(status().isCreated());

		this.mockMvc
			.perform(post("/api/v1/upload/init").header("Authorization", "Bearer " + TOKEN)
				.header("Idempotency-Key", "batch-init-key-0002")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest(PROJECT_ID, 5_368_709_121L)))
			.andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.error_code").value("IDEMPOTENCY_KEY_REUSED"))
			.andExpect(jsonPath("$.retryable").value(false))
			.andExpect(jsonPath("$.request_id").isNotEmpty());

		assertEquals(1, this.repository.count());
	}

	@Test
	void requiresAuthenticationAndProjectScope() throws Exception {
		this.mockMvc
			.perform(post("/api/v1/upload/init").header("Idempotency-Key", "batch-init-key-0003")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest(PROJECT_ID, 1L)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error_code").value("UNAUTHENTICATED"));

		this.mockMvc
			.perform(post("/api/v1/upload/init").header("Authorization", "Bearer " + TOKEN)
				.header("Idempotency-Key", "batch-init-key-0004")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest(OTHER_PROJECT_ID, 1L)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error_code").value("PROJECT_NOT_FOUND"));

		assertEquals(0, this.repository.count());
	}

	@Test
	void acceptsAnExplicitProjectMembershipInsteadOfTheTokenDefaultProject() throws Exception {
		var authorizedProjectId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000003");
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, authorizedProjectId);
		try {
			this.mockMvc
				.perform(post("/api/v1/upload/init").header("Authorization", "Bearer " + TOKEN)
					.header("Idempotency-Key", "batch-other-project-0001")
					.contentType(MediaType.APPLICATION_JSON)
					.content(validRequest(authorizedProjectId.toString(), 1L)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.project_id").value(authorizedProjectId.toString()));
		}
		finally {
			this.repository.deleteAll();
			this.jdbcTemplate.update("DELETE FROM membership WHERE project_id = ?", authorizedProjectId);
			this.jdbcTemplate.update("DELETE FROM project WHERE project_id = ?", authorizedProjectId);
		}
	}

	@Test
	void rejectsInvalidBatchMetadata() throws Exception {
		var request = """
				{
				  "project_id": "%s",
				  "device_id": "contains spaces",
				  "file_count": 0,
				  "total_size_bytes": 0,
				  "manifest_sha256": "not-a-sha256"
				}
				""".formatted(PROJECT_ID);

		this.mockMvc
			.perform(post("/api/v1/upload/init").header("Authorization", "Bearer " + TOKEN)
				.header("Idempotency-Key", "short")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error_code").value("INVALID_REQUEST"));

		assertEquals(0, this.repository.count());
	}

	@Test
	void appliesTheFlywayMigrationWithTheIdempotencyConstraint() {
		var migrationCount = this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success = true", Integer.class);
		var constraintCount = this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM information_schema.table_constraints " + "WHERE table_name = 'upload_batch' "
						+ "AND constraint_name = 'uk_upload_batch_project_idempotency'",
				Integer.class);

		assertEquals(1, migrationCount);
		assertEquals(1, constraintCount);
	}

	private static String validRequest(String projectId, long totalSizeBytes) {
		return """
				{
				  "project_id": "%s",
				  "device_id": "synthetic-device-01",
				  "file_count": 2,
				  "total_size_bytes": %d,
				  "manifest_sha256": "%s"
				}
				""".formatted(projectId, totalSizeBytes, MANIFEST_SHA256);
	}

}
