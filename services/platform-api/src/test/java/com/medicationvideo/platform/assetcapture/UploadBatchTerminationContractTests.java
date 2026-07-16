package com.medicationvideo.platform.assetcapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(UploadBatchTerminationContractTests.StorageConfiguration.class)
class UploadBatchTerminationContractTests {

	private static final String USER_TOKEN = "synthetic-test-bearer-token-do-not-reuse";
	private static final String ADMIN_TOKEN = "synthetic-test-admin-bearer-token-do-not-reuse";
	private static final UUID PROJECT_ID =
			UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final long PART_SIZE = 8L * 1024 * 1024;
	private static final String SHA256 = "a".repeat(64);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private UploadBatchRepository batchRepository;

	@Autowired
	private UploadFileRepository fileRepository;

	@Autowired
	private UploadBatchTerminationService terminationService;

	@Autowired
	private UploadCleanupProcessor cleanupProcessor;

	@Autowired
	private UploadCleanupTaskStore cleanupStore;

	@Autowired
	private FakeCleanupObjectStorage objectStorage;

	private final List<UUID> createdBatchIds = new ArrayList<>();

	@BeforeEach
	void resetStorage() {
		this.objectStorage.reset();
	}

	@AfterEach
	void deleteCreatedBatches() {
		for (var batchId : this.createdBatchIds) {
			this.jdbcTemplate.update("DELETE FROM upload_cleanup_task WHERE batch_id = ?", batchId);
			this.jdbcTemplate.update("""
					DELETE FROM upload_part
					WHERE upload_file_id IN (
					    SELECT upload_file_id FROM upload_file WHERE batch_id = ?)
					""", batchId);
			this.jdbcTemplate.update("DELETE FROM upload_file WHERE batch_id = ?", batchId);
			this.jdbcTemplate.update("DELETE FROM upload_batch WHERE batch_id = ?", batchId);
		}
		this.createdBatchIds.clear();
	}

	@Test
	void initiatorAbortsIdempotentlyAndCleanupIsObservable() throws Exception {
		var batchId = initializeBatch();
		var clientFileId = UUID.randomUUID();
		registerFile(batchId, clientFileId);
		startMultipart(batchId, clientFileId);

		var first = abort(batchId, USER_TOKEN, "abort-request-0001")
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.status").value("ABORTED"))
				.andExpect(jsonPath("$.termination_reason_code").value("USER_REQUESTED"))
				.andExpect(jsonPath("$.cleanup_status").value("PENDING"))
				.andExpect(jsonPath("$.cleanup_task_count").value(1))
				.andReturn()
				.getResponse()
				.getContentAsString();

		var replay = abort(batchId, USER_TOKEN, "abort-request-0002")
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
		assertEquals(first, replay);
		assertEquals(1, auditCount(batchId, "UPLOAD_BATCH_ABORT"));
		assertEquals("ABORTED", fileStatus(batchId));

		this.cleanupProcessor.processAvailable();
		this.mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
					.get("/api/v1/upload/{batchId}", batchId)
					.header("Authorization", "Bearer " + USER_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cleanup_status").value("SUCCEEDED"))
				.andExpect(jsonPath("$.completed_cleanup_task_count").value(1))
				.andExpect(jsonPath("$.failed_cleanup_task_count").value(0));
		assertEquals(1, this.objectStorage.abortCalls);
		assertEquals(0, this.objectStorage.deleteCalls);
	}

	@Test
	void onlyInitiatorOrAdministratorCanAbort() throws Exception {
		var batch = directBatch("different-initiator", Instant.now().plusSeconds(3600));

		abort(batch.getBatchId(), USER_TOKEN, "abort-request-unauthorized")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("PROJECT_ACCESS_DENIED"));

		abort(batch.getBatchId(), ADMIN_TOKEN, "abort-request-admin")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ABORTED"))
				.andExpect(jsonPath("$.cleanup_status").value("NOT_REQUIRED"));
		assertEquals(1, auditCount(batch.getBatchId(), "UPLOAD_BATCH_ABORT"));
	}

	@Test
	void activeFileCompletionBlocksAbort() throws Exception {
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var batch = directBatch("local-synthetic-uploader", now.plusSeconds(3600));
		batch.markUploading(now);
		this.batchRepository.saveAndFlush(batch);
		var file = directFile(batch, now);
		file.markUploading(now);
		file.startCompletion(UUID.randomUUID(), now.plusSeconds(60), now);
		this.fileRepository.saveAndFlush(file);

		abort(batch.getBatchId(), USER_TOKEN, "abort-request-completing")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("UPLOAD_FILE_COMPLETION_IN_PROGRESS"))
				.andExpect(jsonPath("$.retryable").value(true));
		assertEquals("UPLOADING", batchStatus(batch.getBatchId()));
		assertEquals(0, cleanupTaskCount(batch.getBatchId()));
	}

	@Test
	void schedulerExpiresDueBatchAndDeletesCompletedAbandonedObject() {
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var batch = directBatch("local-synthetic-uploader", now.minusSeconds(60));
		var file = directFile(batch, now.minusSeconds(120));
		this.objectStorage.completedObjects.add(file.getObjectKey());

		assertTrue(this.terminationService.expireNextDue());
		assertEquals("EXPIRED", batchStatus(batch.getBatchId()));
		assertEquals("EXPIRED", fileStatus(batch.getBatchId()));
		assertEquals(1, cleanupTaskCount(batch.getBatchId()));
		assertEquals(1, auditCount(batch.getBatchId(), "UPLOAD_BATCH_EXPIRE"));

		this.cleanupProcessor.processAvailable();
		assertEquals(0, this.objectStorage.abortCalls);
		assertEquals(1, this.objectStorage.deleteCalls);
		assertEquals("SUCCEEDED", cleanupTaskStatus(batch.getBatchId()));
	}

	@Test
	void abortAfterTtlRecordsPolicyExpiryInsteadOfUserAbort() throws Exception {
		var batch = directBatch("local-synthetic-uploader", Instant.now().minusSeconds(60));

		abort(batch.getBatchId(), USER_TOKEN, "abort-request-after-ttl")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("EXPIRED"))
				.andExpect(jsonPath("$.termination_reason_code").value("UPLOAD_BATCH_TTL_EXPIRED"));

		assertEquals(0, auditCount(batch.getBatchId(), "UPLOAD_BATCH_ABORT"));
		assertEquals(1, auditCount(batch.getBatchId(), "UPLOAD_BATCH_EXPIRE"));
		assertEquals(
				"SYSTEM",
				this.jdbcTemplate.queryForObject(
						"SELECT actor_type FROM audit_event WHERE object_id = ? AND action = 'UPLOAD_BATCH_EXPIRE'",
						String.class,
						batch.getBatchId().toString()));
	}

	@Test
	void cleanupFailureReachesObservableBoundedTerminalState() {
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var batch = directBatch("local-synthetic-uploader", now.minusSeconds(60));
		directFile(batch, now.minusSeconds(120));
		assertTrue(this.terminationService.expireNextDue());

		this.objectStorage.failCleanup = true;
		var oneAttemptProcessor = new UploadCleanupProcessor(
				this.cleanupStore,
				this.objectStorage,
				1,
				1,
				Duration.ofMinutes(1),
				Duration.ofSeconds(1));
		oneAttemptProcessor.processAvailable();

		assertEquals("FAILED", cleanupTaskStatus(batch.getBatchId()));
		assertEquals(
				"OBJECT_STORAGE_CLEANUP_FAILED",
				this.jdbcTemplate.queryForObject(
						"SELECT last_error_code FROM upload_cleanup_task WHERE batch_id = ?",
						String.class,
						batch.getBatchId()));
		assertEquals(1, cleanupAttemptCount(batch.getBatchId()));
		assertEquals(UploadCleanupStatus.FAILED, this.cleanupStore.summarize(batch.getBatchId()).status());
	}

	private UUID initializeBatch() throws Exception {
		var response = this.mockMvc.perform(post("/api/v1/upload/init")
					.header("Authorization", "Bearer " + USER_TOKEN)
					.header("Idempotency-Key", "termination-batch-" + UUID.randomUUID())
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "project_id": "%s",
							  "device_id": "synthetic-edge-termination",
							  "file_count": 1,
							  "total_size_bytes": %d,
							  "manifest_sha256": "%s"
							}
							""".formatted(PROJECT_ID, PART_SIZE, SHA256)))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString();
		var batchId = UUID.fromString(JsonPath.read(response, "$.batch_id"));
		this.createdBatchIds.add(batchId);
		return batchId;
	}

	private void registerFile(UUID batchId, UUID clientFileId) throws Exception {
		this.mockMvc.perform(put("/api/v1/upload/{batchId}/files/{clientFileId}", batchId, clientFileId)
					.header("Authorization", "Bearer " + USER_TOKEN)
					.header("Idempotency-Key", "termination-file-" + UUID.randomUUID())
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "file_kind": "VIDEO",
							  "content_type": "video/mp4",
							  "size_bytes": %d,
							  "sha256": "%s",
							  "part_size_bytes": %d
							}
							""".formatted(PART_SIZE, SHA256, PART_SIZE)))
				.andExpect(status().isCreated());
	}

	private void startMultipart(UUID batchId, UUID clientFileId) throws Exception {
		this.mockMvc.perform(put(
					"/api/v1/upload/{batchId}/files/{clientFileId}/multipart",
					batchId,
					clientFileId)
					.header("Authorization", "Bearer " + USER_TOKEN))
				.andExpect(status().isCreated());
	}

	private org.springframework.test.web.servlet.ResultActions abort(
			UUID batchId, String token, String requestId) throws Exception {
		return this.mockMvc.perform(put("/api/v1/upload/{batchId}/abort", batchId)
				.header("Authorization", "Bearer " + token)
				.header("X-Request-Id", requestId));
	}

	private UploadBatch directBatch(String actorId, Instant expiresAt) {
		var createdAt = expiresAt.minusSeconds(3600);
		var batch = UploadBatch.initiate(
				PROJECT_ID,
				"termination-direct-" + UUID.randomUUID(),
				SHA256,
				"synthetic-edge-termination",
				actorId,
				1,
				PART_SIZE,
				SHA256,
				expiresAt,
				createdAt);
		this.batchRepository.saveAndFlush(batch);
		this.createdBatchIds.add(batch.getBatchId());
		return batch;
	}

	private UploadFile directFile(UploadBatch batch, Instant now) {
		var request = new UploadFileRegistrationRequest(
				UploadFileKind.VIDEO, "video/mp4", PART_SIZE, SHA256, PART_SIZE);
		var file = UploadFile.register(
				batch.getBatchId(),
				UUID.randomUUID(),
				"termination-direct-file-" + UUID.randomUUID(),
				SHA256,
				request,
				1,
				now);
		file.attachMultipartSession(
				new MultipartObjectStorage.CreatedUpload(
						"synthetic-bucket",
						"termination/" + file.getUploadFileId(),
						"upload-" + file.getUploadFileId()),
				now);
		return this.fileRepository.saveAndFlush(file);
	}

	private int auditCount(UUID batchId, String action) {
		return this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_event WHERE object_id = ? AND action = ?",
				Integer.class,
				batchId.toString(),
				action);
	}

	private int cleanupTaskCount(UUID batchId) {
		return this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM upload_cleanup_task WHERE batch_id = ?",
				Integer.class,
				batchId);
	}

	private String cleanupTaskStatus(UUID batchId) {
		return this.jdbcTemplate.queryForObject(
				"SELECT status FROM upload_cleanup_task WHERE batch_id = ?",
				String.class,
				batchId);
	}

	private int cleanupAttemptCount(UUID batchId) {
		return this.jdbcTemplate.queryForObject(
				"SELECT attempt_count FROM upload_cleanup_task WHERE batch_id = ?",
				Integer.class,
				batchId);
	}

	private String batchStatus(UUID batchId) {
		return this.jdbcTemplate.queryForObject(
				"SELECT status FROM upload_batch WHERE batch_id = ?", String.class, batchId);
	}

	private String fileStatus(UUID batchId) {
		return this.jdbcTemplate.queryForObject(
				"SELECT status FROM upload_file WHERE batch_id = ?", String.class, batchId);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class StorageConfiguration {

		@Bean
		@Primary
		FakeCleanupObjectStorage fakeCleanupObjectStorage() {
			return new FakeCleanupObjectStorage();
		}
	}

	static class FakeCleanupObjectStorage implements MultipartObjectStorage {

		private final Set<String> completedObjects = new HashSet<>();
		private int abortCalls;
		private int deleteCalls;
		private boolean failCleanup;

		@Override
		public CreatedUpload create(String objectKey, String contentType) {
			return new CreatedUpload("synthetic-bucket", objectKey, "upload-" + UUID.randomUUID());
		}

		@Override
		public URI authorizePart(CreatedUpload upload, int partNumber, Duration lifetime) {
			return URI.create("https://storage.invalid/part");
		}

		@Override
		public URI authorizeRead(ObjectReference object, Duration lifetime) {
			return URI.create("https://storage.invalid/read");
		}

		@Override
		public Optional<StoredPart> findPart(CreatedUpload upload, int partNumber) {
			return Optional.empty();
		}

		@Override
		public List<StoredPart> listParts(CreatedUpload upload) {
			return List.of();
		}

		@Override
		public CompletedObject complete(CreatedUpload upload, List<StoredPart> parts) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Optional<CompletedObject> findCompletedObject(CreatedUpload upload) {
			if (this.failCleanup) {
				throw new MultipartObjectStorageException("Synthetic cleanup failure.", null);
			}
			return this.completedObjects.contains(upload.objectKey())
					? Optional.of(new CompletedObject(PART_SIZE, "synthetic-etag"))
					: Optional.empty();
		}

		@Override
		public String calculateSha256(CreatedUpload upload) {
			throw new UnsupportedOperationException();
		}

		@Override
		public byte[] readCompletedObject(ObjectReference object, int maxBytes) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void abort(CreatedUpload upload) {
			this.abortCalls++;
		}

		@Override
		public void delete(ObjectReference object) {
			this.deleteCalls++;
			this.completedObjects.remove(object.objectKey());
		}

		void reset() {
			this.completedObjects.clear();
			this.abortCalls = 0;
			this.deleteCalls = 0;
			this.failCleanup = false;
		}
	}
}
