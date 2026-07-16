package com.medicationvideo.platform.assetcapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UploadFileContractTests {

	private static final String TOKEN = "synthetic-test-bearer-token-do-not-reuse";
	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID OTHER_PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final String SHA256_A = "a".repeat(64);
	private static final String SHA256_B = "b".repeat(64);
	private static final long MIN_PART_SIZE = 8_388_608L;
	private static final long MAX_PART_SIZE = 67_108_864L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UploadBatchRepository batchRepository;

	@Autowired
	private UploadFileRepository fileRepository;

	@Autowired
	private UploadPartRepository partRepository;

	@Autowired
	private ClipAssetRepository clipAssetRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clearUploads() {
		this.clipAssetRepository.deleteAll();
		this.partRepository.deleteAll();
		this.fileRepository.deleteAll();
		this.batchRepository.deleteAll();
	}

	@Test
	void registersAndExactlyReplaysALargeFileWithoutStartingUpload() throws Exception {
		var sizeBytes = 5_368_709_121L;
		var batchId = initializeBatch(1, sizeBytes, "batch-init-file-0001");
		var clientFileId = UUID.fromString("10000000-0000-0000-0000-000000000001");
		var request = registrationRequest(sizeBytes, SHA256_A, MAX_PART_SIZE);

		var first = register(batchId, clientFileId, "file-register-key-0001", request)
				.andExpect(status().isCreated())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.upload_file_id").isNotEmpty())
				.andExpect(jsonPath("$.batch_id").value(batchId.toString()))
				.andExpect(jsonPath("$.client_file_id").value(clientFileId.toString()))
				.andExpect(jsonPath("$.file_kind").value("VIDEO"))
				.andExpect(jsonPath("$.size_bytes").value(sizeBytes))
				.andExpect(jsonPath("$.part_size_bytes").value(MAX_PART_SIZE))
				.andExpect(jsonPath("$.total_parts").value(81))
				.andExpect(jsonPath("$.status").value("REGISTERED"))
				.andReturn();

		var replay = register(batchId, clientFileId, "file-register-key-0001", request)
				.andExpect(status().isCreated())
				.andReturn();

		assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
		assertEquals(1, this.fileRepository.count());
		assertEquals(UploadBatchStatus.INITIATED, this.batchRepository.findById(batchId).orElseThrow().getStatus());
	}

	@Test
	void rejectsConflictingClientFileAndIdempotencyKeys() throws Exception {
		var batchId = initializeBatch(3, 30L, "batch-init-file-0002");
		var firstClientFileId = UUID.fromString("10000000-0000-0000-0000-000000000002");
		var secondClientFileId = UUID.fromString("10000000-0000-0000-0000-000000000003");

		register(batchId, firstClientFileId, "file-register-key-0002", registrationRequest(10L, SHA256_A, MIN_PART_SIZE))
				.andExpect(status().isCreated());

		register(batchId, firstClientFileId, "file-register-key-0003", registrationRequest(11L, SHA256_B, MIN_PART_SIZE))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("UPLOAD_FILE_ID_REUSED"));

		register(batchId, secondClientFileId, "file-register-key-0002", registrationRequest(10L, SHA256_A, MIN_PART_SIZE))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("IDEMPOTENCY_KEY_REUSED"));

		assertEquals(1, this.fileRepository.count());
	}

	@Test
	void enforcesDeclaredFileCountAndTotalSize() throws Exception {
		var countLimitedBatch = initializeBatch(1, 20L, "batch-init-file-0003");
		register(
				countLimitedBatch,
				UUID.fromString("10000000-0000-0000-0000-000000000004"),
				"file-register-key-0004",
				registrationRequest(10L, SHA256_A, MIN_PART_SIZE))
				.andExpect(status().isCreated());
		register(
				countLimitedBatch,
				UUID.fromString("10000000-0000-0000-0000-000000000005"),
				"file-register-key-0005",
				registrationRequest(10L, SHA256_B, MIN_PART_SIZE))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("UPLOAD_BATCH_LIMIT_EXCEEDED"));

		var sizeLimitedBatch = initializeBatch(2, 9L, "batch-init-file-0004");
		register(
				sizeLimitedBatch,
				UUID.fromString("10000000-0000-0000-0000-000000000006"),
				"file-register-key-0006",
				registrationRequest(10L, SHA256_A, MIN_PART_SIZE))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("UPLOAD_BATCH_LIMIT_EXCEEDED"));
	}

	@Test
	void serializesConcurrentRegistrationsAtTheBatchQuota() throws Exception {
		var batchId = initializeBatch(1, 100L, "batch-init-file-0005");
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> {
				start.await();
				return register(
						batchId,
						UUID.fromString("10000000-0000-0000-0000-000000000007"),
						"file-register-key-0007",
						registrationRequest(100L, SHA256_A, MIN_PART_SIZE))
						.andReturn().getResponse().getStatus();
			});
			var second = executor.submit(() -> {
				start.await();
				return register(
						batchId,
						UUID.fromString("10000000-0000-0000-0000-000000000008"),
						"file-register-key-0008",
						registrationRequest(100L, SHA256_B, MIN_PART_SIZE))
						.andReturn().getResponse().getStatus();
			});

			start.countDown();
			var statuses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
			assertTrue(statuses.contains(201));
			assertTrue(statuses.contains(409));
		}

		assertEquals(1, this.fileRepository.count());
	}

	@Test
	void hidesBatchesOwnedByAnotherProject() throws Exception {
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var otherBatch = UploadBatch.initiate(
				OTHER_PROJECT_ID,
				"other-project-key-0001",
				SHA256_A,
				"synthetic-device-02",
				1,
				10L,
				SHA256_A,
				now.plusSeconds(3600),
				now);
		this.batchRepository.saveAndFlush(otherBatch);

		register(
				otherBatch.getBatchId(),
				UUID.fromString("10000000-0000-0000-0000-000000000009"),
				"file-register-key-0009",
				registrationRequest(10L, SHA256_A, MIN_PART_SIZE))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error_code").value("UPLOAD_BATCH_NOT_FOUND"));
	}

	@Test
	void validatesPartPlanningAndMaximumPartCount() throws Exception {
		var invalidBatch = initializeBatch(1, 10L, "batch-init-file-0006");
		register(
				invalidBatch,
				UUID.fromString("10000000-0000-0000-0000-000000000010"),
				"file-register-key-0010",
				registrationRequest(10L, SHA256_A, 1L))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error_code").value("INVALID_REQUEST"));

		var tooLarge = MAX_PART_SIZE * 10_000L + 1L;
		var tooManyPartsBatch = initializeBatch(1, tooLarge, "batch-init-file-0007");
		register(
				tooManyPartsBatch,
				UUID.fromString("10000000-0000-0000-0000-000000000011"),
				"file-register-key-0011",
				registrationRequest(tooLarge, SHA256_A, MAX_PART_SIZE))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("UPLOAD_FILE_TOO_MANY_PARTS"));
	}

	@Test
	void appliesTheUploadFileMigrationAndUniquenessConstraints() {
		var migrationCount = this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version = '2' AND success = true",
				Integer.class);
		var constraintCount = this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM information_schema.table_constraints "
						+ "WHERE table_name = 'upload_file' "
						+ "AND constraint_name IN "
						+ "('uk_upload_file_batch_client', 'uk_upload_file_batch_idempotency')",
				Integer.class);

		assertEquals(1, migrationCount);
		assertEquals(2, constraintCount);
	}

	private UUID initializeBatch(int fileCount, long totalSizeBytes, String idempotencyKey) throws Exception {
		var request = """
				{
				  "project_id": "%s",
				  "device_id": "synthetic-device-01",
				  "file_count": %d,
				  "total_size_bytes": %d,
				  "manifest_sha256": "%s"
				}
				""".formatted(PROJECT_ID, fileCount, totalSizeBytes, SHA256_A);

		this.mockMvc.perform(post("/api/v1/upload/init")
					.header("Authorization", "Bearer " + TOKEN)
					.header("Idempotency-Key", idempotencyKey)
					.contentType(MediaType.APPLICATION_JSON)
					.content(request))
				.andExpect(status().isCreated());

		return this.batchRepository.findByProjectIdAndIdempotencyKey(PROJECT_ID, idempotencyKey)
				.orElseThrow()
				.getBatchId();
	}

	private org.springframework.test.web.servlet.ResultActions register(
			UUID batchId,
			UUID clientFileId,
			String idempotencyKey,
			String request) throws Exception {
		return this.mockMvc.perform(put("/api/v1/upload/{batchId}/files/{clientFileId}", batchId, clientFileId)
				.header("Authorization", "Bearer " + TOKEN)
				.header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(request));
	}

	private static String registrationRequest(long sizeBytes, String sha256, long partSizeBytes) {
		return """
				{
				  "file_kind": "VIDEO",
				  "content_type": "video/mp4",
				  "size_bytes": %d,
				  "sha256": "%s",
				  "part_size_bytes": %d
				}
				""".formatted(sizeBytes, sha256, partSizeBytes);
	}
}
