package com.medicationvideo.platform.assetcapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(UploadMultipartContractTests.FakeStorageConfiguration.class)
class UploadMultipartContractTests {

	private static final String TOKEN = "synthetic-test-bearer-token-do-not-reuse";
	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final long PART_SIZE = 8_388_608L;
	private static final String SHA256_A = "a".repeat(64);
	private static final String SHA256_B = "b".repeat(64);
	private static final String SHA256_C = "c".repeat(64);

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
	private FakeMultipartObjectStorage objectStorage;

	@Autowired
	private UploadFileCompletionStateService completionStateService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clearUploads() {
		this.clipAssetRepository.deleteAll();
		this.partRepository.deleteAll();
		this.fileRepository.deleteAll();
		this.batchRepository.deleteAll();
		this.objectStorage.reset();
	}

	@Test
	void createsExactlyOneRecoverableSessionWithoutStartingTheBatch() throws Exception {
		var batchId = initializeBatch(1, PART_SIZE * 2, "multipart-batch-key-0001");
		var clientFileId = UUID.fromString("20000000-0000-0000-0000-000000000001");
		registerFile(batchId, clientFileId, PART_SIZE * 2, "multipart-file-key-0001");

		var first = startMultipart(batchId, clientFileId)
				.andExpect(status().isCreated())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.upload_file_id").isNotEmpty())
				.andExpect(jsonPath("$.batch_id").value(batchId.toString()))
				.andExpect(jsonPath("$.client_file_id").value(clientFileId.toString()))
				.andExpect(jsonPath("$.total_parts").value(2))
				.andExpect(jsonPath("$.status").value("SESSION_CREATED"))
				.andExpect(jsonPath("$.multipart_created_at").isNotEmpty())
				.andExpect(jsonPath("$.storage_upload_id").doesNotExist())
				.andExpect(jsonPath("$.object_key").doesNotExist())
				.andReturn();

		var replay = startMultipart(batchId, clientFileId)
				.andExpect(status().isCreated())
				.andReturn();

		assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
		assertEquals(1, this.objectStorage.createCalls);
		assertEquals(UploadBatchStatus.INITIATED, this.batchRepository.findById(batchId).orElseThrow().getStatus());
		assertEquals(
				"synthetic-upload-1",
				this.jdbcTemplate.queryForObject(
						"SELECT storage_upload_id FROM upload_file WHERE batch_id = ?",
						String.class,
						batchId));
	}

	@Test
	void authorizesAndReauthorizesOnePartThenMovesTheBatchToUploading() throws Exception {
		var batchId = initializeBatch(1, PART_SIZE + 1, "multipart-batch-key-0002");
		var clientFileId = UUID.fromString("20000000-0000-0000-0000-000000000002");
		registerFile(batchId, clientFileId, PART_SIZE + 1, "multipart-file-key-0002");
		startMultipart(batchId, clientFileId).andExpect(status().isCreated());

		authorizePart(batchId, clientFileId, 1, SHA256_B)
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.part_number").value(1))
				.andExpect(jsonPath("$.expected_size_bytes").value(PART_SIZE))
				.andExpect(jsonPath("$.sha256").value(SHA256_B))
				.andExpect(jsonPath("$.status").value("AUTHORIZED"))
				.andExpect(jsonPath("$.upload_url").value(org.hamcrest.Matchers.startsWith("https://storage.invalid/")))
				.andExpect(jsonPath("$.expires_at").isNotEmpty());

		authorizePart(batchId, clientFileId, 1, SHA256_B)
				.andExpect(status().isOk());

		assertEquals(2, this.objectStorage.authorizeCalls);
		assertEquals(1, this.partRepository.count());
		assertEquals(UploadBatchStatus.UPLOADING, this.batchRepository.findById(batchId).orElseThrow().getStatus());
		assertEquals(
				UploadFileStatus.UPLOADING,
				this.fileRepository.findByBatchIdAndClientFileId(batchId, clientFileId).orElseThrow().getStatus());
		assertEquals(
				0,
				this.jdbcTemplate.queryForObject(
						"SELECT count(*) FROM information_schema.columns "
								+ "WHERE table_name IN ('upload_file', 'upload_part') AND column_name = 'upload_url'",
						Integer.class));
	}

	@Test
	void requiresTheExactBatchManifestBeforeCreatingStorageState() throws Exception {
		var batchId = initializeBatch(2, PART_SIZE + 1, "multipart-batch-key-0003");
		var clientFileId = UUID.fromString("20000000-0000-0000-0000-000000000003");
		registerFile(batchId, clientFileId, PART_SIZE, "multipart-file-key-0003");

		startMultipart(batchId, clientFileId)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("UPLOAD_BATCH_MANIFEST_INCOMPLETE"));

		assertEquals(0, this.objectStorage.createCalls);
		assertFalse(this.fileRepository.findByBatchIdAndClientFileId(batchId, clientFileId)
				.orElseThrow()
				.hasMultipartSession());
	}

	@Test
	void protectsPartBoundsAndImmutableChecksum() throws Exception {
		var batchId = initializeBatch(1, PART_SIZE + 1, "multipart-batch-key-0004");
		var clientFileId = UUID.fromString("20000000-0000-0000-0000-000000000004");
		registerFile(batchId, clientFileId, PART_SIZE + 1, "multipart-file-key-0004");

		authorizePart(batchId, clientFileId, 1, SHA256_B)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("UPLOAD_MULTIPART_SESSION_REQUIRED"));

		startMultipart(batchId, clientFileId).andExpect(status().isCreated());
		authorizePart(batchId, clientFileId, 3, SHA256_B)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("UPLOAD_PART_OUT_OF_RANGE"));

		authorizePart(batchId, clientFileId, 2, SHA256_B)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.expected_size_bytes").value(1));
		authorizePart(batchId, clientFileId, 2, SHA256_C)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("UPLOAD_PART_HASH_CONFLICT"));

		assertEquals(1, this.partRepository.count());
	}

	@Test
	void confirmsOnlyAStorageBackedPartAndKeepsConfirmationIdempotent() throws Exception {
		var batchId = initializeBatch(1, PART_SIZE, "multipart-batch-key-0006");
		var clientFileId = UUID.fromString("20000000-0000-0000-0000-000000000006");
		registerFile(batchId, clientFileId, PART_SIZE, "multipart-file-key-0006");
		startMultipart(batchId, clientFileId).andExpect(status().isCreated());
		authorizePart(batchId, clientFileId, 1, SHA256_B).andExpect(status().isOk());

		confirmPart(batchId, clientFileId, 1)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("UPLOAD_PART_NOT_FOUND_IN_STORAGE"));

		this.objectStorage.storePart(1, PART_SIZE, "synthetic-etag-1");
		var first = confirmPart(batchId, clientFileId, 1)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.part_number").value(1))
				.andExpect(jsonPath("$.expected_size_bytes").value(PART_SIZE))
				.andExpect(jsonPath("$.uploaded_size_bytes").value(PART_SIZE))
				.andExpect(jsonPath("$.sha256").value(SHA256_B))
				.andExpect(jsonPath("$.status").value("UPLOADED"))
				.andExpect(jsonPath("$.storage_etag").value("synthetic-etag-1"))
				.andExpect(jsonPath("$.confirmed_at").isNotEmpty())
				.andReturn();

		var replay = confirmPart(batchId, clientFileId, 1)
				.andExpect(status().isOk())
				.andReturn();

		assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
		authorizePart(batchId, clientFileId, 1, SHA256_B)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("UPLOAD_PART_ALREADY_CONFIRMED"));
		assertEquals(
				UploadPartStatus.UPLOADED,
				this.partRepository.findByUploadFileIdAndPartNumber(
						this.fileRepository.findByBatchIdAndClientFileId(batchId, clientFileId)
								.orElseThrow()
								.getUploadFileId(),
						1)
						.orElseThrow()
						.getStatus());
	}

	@Test
	void reconcilesStoragePartsForResumeAndReturnsCurrentProgress() throws Exception {
		var batchId = initializeBatch(1, PART_SIZE * 2, "multipart-batch-key-0007");
		var clientFileId = UUID.fromString("20000000-0000-0000-0000-000000000007");
		registerFile(batchId, clientFileId, PART_SIZE * 2, "multipart-file-key-0007");
		startMultipart(batchId, clientFileId).andExpect(status().isCreated());
		authorizePart(batchId, clientFileId, 1, SHA256_B).andExpect(status().isOk());
		authorizePart(batchId, clientFileId, 2, SHA256_C).andExpect(status().isOk());
		this.objectStorage.storePart(1, PART_SIZE, "synthetic-etag-1");

		reconcileParts(batchId, clientFileId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total_parts").value(2))
				.andExpect(jsonPath("$.tracked_parts").value(2))
				.andExpect(jsonPath("$.uploaded_parts").value(1))
				.andExpect(jsonPath("$.parts[0].part_number").value(1))
				.andExpect(jsonPath("$.parts[0].status").value("UPLOADED"))
				.andExpect(jsonPath("$.parts[1].part_number").value(2))
				.andExpect(jsonPath("$.parts[1].status").value("AUTHORIZED"));
		getPartProgress(batchId, clientFileId)
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.uploaded_parts").value(1));

		this.objectStorage.storePart(2, PART_SIZE, "synthetic-etag-2");
		reconcileParts(batchId, clientFileId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.uploaded_parts").value(2));
	}

	@Test
	void rejectsStoredPartSizeMismatchAndUntrackedParts() throws Exception {
		var batchId = initializeBatch(1, PART_SIZE * 2, "multipart-batch-key-0008");
		var clientFileId = UUID.fromString("20000000-0000-0000-0000-000000000008");
		registerFile(batchId, clientFileId, PART_SIZE * 2, "multipart-file-key-0008");
		startMultipart(batchId, clientFileId).andExpect(status().isCreated());
		authorizePart(batchId, clientFileId, 1, SHA256_B).andExpect(status().isOk());
		this.objectStorage.storePart(1, PART_SIZE - 1, "synthetic-etag-wrong-size");

		confirmPart(batchId, clientFileId, 1)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("UPLOAD_PART_SIZE_MISMATCH"));

		this.objectStorage.removePart(1);
		this.objectStorage.storePart(2, PART_SIZE, "synthetic-etag-untracked");
		reconcileParts(batchId, clientFileId)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("UPLOAD_PART_STORAGE_DIVERGED"));
	}

	@Test
	void reportsStorageFailuresAsRetryableWithoutAdvancingState() throws Exception {
		var batchId = initializeBatch(1, PART_SIZE, "multipart-batch-key-0005");
		var clientFileId = UUID.fromString("20000000-0000-0000-0000-000000000005");
		registerFile(batchId, clientFileId, PART_SIZE, "multipart-file-key-0005");

		this.objectStorage.failCreate = true;
		startMultipart(batchId, clientFileId)
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error_code").value("OBJECT_STORAGE_UNAVAILABLE"))
				.andExpect(jsonPath("$.retryable").value(true));
		assertFalse(this.fileRepository.findByBatchIdAndClientFileId(batchId, clientFileId)
				.orElseThrow()
				.hasMultipartSession());

		this.objectStorage.failCreate = false;
		startMultipart(batchId, clientFileId).andExpect(status().isCreated());
		this.objectStorage.failAuthorize = true;
		authorizePart(batchId, clientFileId, 1, SHA256_A)
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.retryable").value(true));

		assertEquals(0, this.partRepository.count());
		assertEquals(UploadBatchStatus.INITIATED, this.batchRepository.findById(batchId).orElseThrow().getStatus());
		assertEquals(
				UploadFileStatus.SESSION_CREATED,
				this.fileRepository.findByBatchIdAndClientFileId(batchId, clientFileId).orElseThrow().getStatus());

		this.objectStorage.failAuthorize = false;
		authorizePart(batchId, clientFileId, 1, SHA256_A).andExpect(status().isOk());
		this.objectStorage.failInspect = true;
		confirmPart(batchId, clientFileId, 1)
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.retryable").value(true));
		reconcileParts(batchId, clientFileId)
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.retryable").value(true));
	}

	@Test
	void completesAndVerifiesAFileIdempotently() throws Exception {
		var batchId = initializeBatch(1, PART_SIZE * 2, "multipart-batch-key-0009");
		var clientFileId = UUID.fromString("20000000-0000-0000-0000-000000000009");
		registerFile(batchId, clientFileId, PART_SIZE * 2, "multipart-file-key-0009");
		startMultipart(batchId, clientFileId).andExpect(status().isCreated());
		authorizePart(batchId, clientFileId, 1, SHA256_B).andExpect(status().isOk());
		authorizePart(batchId, clientFileId, 2, SHA256_C).andExpect(status().isOk());
		this.objectStorage.storePart(1, PART_SIZE, "synthetic-etag-1");
		this.objectStorage.storePart(2, PART_SIZE, "synthetic-etag-2");

		var first = completeFile(batchId, clientFileId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.batch_id").value(batchId.toString()))
				.andExpect(jsonPath("$.client_file_id").value(clientFileId.toString()))
				.andExpect(jsonPath("$.size_bytes").value(PART_SIZE * 2))
				.andExpect(jsonPath("$.declared_sha256").value(SHA256_A))
				.andExpect(jsonPath("$.verified_sha256").value(SHA256_A))
				.andExpect(jsonPath("$.status").value("UPLOADED"))
				.andExpect(jsonPath("$.storage_etag").value("synthetic-completed-etag"))
				.andExpect(jsonPath("$.completion_started_at").isNotEmpty())
				.andExpect(jsonPath("$.completed_at").isNotEmpty())
				.andReturn();

		var replay = completeFile(batchId, clientFileId)
				.andExpect(status().isOk())
				.andReturn();

		assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
		assertEquals(1, this.objectStorage.completeCalls);
		assertEquals(1, this.objectStorage.hashCalls);
		assertEquals(
				UploadFileStatus.UPLOADED,
				this.fileRepository.findByBatchIdAndClientFileId(batchId, clientFileId).orElseThrow().getStatus());
		assertEquals(
				2,
				this.partRepository.findAllByUploadFileIdOrderByPartNumber(
						this.fileRepository.findByBatchIdAndClientFileId(batchId, clientFileId)
								.orElseThrow()
								.getUploadFileId())
						.stream()
						.filter(part -> part.getStatus() == UploadPartStatus.UPLOADED)
						.count());
	}

	@Test
	void rejectsIncompletePartsAndReturnsTheFileToUploading() throws Exception {
		var batchId = initializeBatch(1, PART_SIZE * 2, "multipart-batch-key-0010");
		var clientFileId = UUID.fromString("20000000-0000-0000-0000-000000000010");
		registerFile(batchId, clientFileId, PART_SIZE * 2, "multipart-file-key-0010");
		startMultipart(batchId, clientFileId).andExpect(status().isCreated());
		authorizePart(batchId, clientFileId, 1, SHA256_B).andExpect(status().isOk());
		authorizePart(batchId, clientFileId, 2, SHA256_C).andExpect(status().isOk());
		this.objectStorage.storePart(1, PART_SIZE, "synthetic-etag-1");

		completeFile(batchId, clientFileId)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("UPLOAD_FILE_PARTS_INCOMPLETE"));

		assertEquals(0, this.objectStorage.completeCalls);
		assertEquals(
				UploadFileStatus.UPLOADING,
				this.fileRepository.findByBatchIdAndClientFileId(batchId, clientFileId).orElseThrow().getStatus());
	}

	@Test
	void persistsFinalChecksumFailureAndRejectsItsReplay() throws Exception {
		var batchId = initializeBatch(1, PART_SIZE, "multipart-batch-key-0011");
		var clientFileId = UUID.fromString("20000000-0000-0000-0000-000000000011");
		registerFile(batchId, clientFileId, PART_SIZE, "multipart-file-key-0011");
		startMultipart(batchId, clientFileId).andExpect(status().isCreated());
		authorizePart(batchId, clientFileId, 1, SHA256_B).andExpect(status().isOk());
		this.objectStorage.storePart(1, PART_SIZE, "synthetic-etag-1");
		this.objectStorage.completedSha256 = SHA256_B;

		completeFile(batchId, clientFileId)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("UPLOAD_FILE_CHECKSUM_MISMATCH"));
		completeFile(batchId, clientFileId)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("UPLOAD_FILE_CHECKSUM_MISMATCH"));

		var file = this.fileRepository.findByBatchIdAndClientFileId(batchId, clientFileId).orElseThrow();
		assertEquals(UploadFileStatus.FAILED, file.getStatus());
		assertEquals(SHA256_B, file.getVerifiedSha256());
		assertEquals("UPLOAD_FILE_CHECKSUM_MISMATCH", file.getCompletionErrorCode());
		assertEquals(1, this.objectStorage.completeCalls);
		assertEquals(1, this.objectStorage.hashCalls);
	}

	@Test
	void persistsFinalObjectSizeFailureWithoutStreamingIt() throws Exception {
		var batchId = initializeBatch(1, PART_SIZE, "multipart-batch-key-0014");
		var clientFileId = UUID.fromString("20000000-0000-0000-0000-000000000014");
		registerFile(batchId, clientFileId, PART_SIZE, "multipart-file-key-0014");
		startMultipart(batchId, clientFileId).andExpect(status().isCreated());
		authorizePart(batchId, clientFileId, 1, SHA256_B).andExpect(status().isOk());
		this.objectStorage.completedObject = new MultipartObjectStorage.CompletedObject(
				PART_SIZE - 1,
				"synthetic-wrong-size-etag");

		completeFile(batchId, clientFileId)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("UPLOAD_FILE_SIZE_MISMATCH"));

		var file = this.fileRepository.findByBatchIdAndClientFileId(batchId, clientFileId).orElseThrow();
		assertEquals(UploadFileStatus.FAILED, file.getStatus());
		assertEquals("UPLOAD_FILE_SIZE_MISMATCH", file.getCompletionErrorCode());
		assertEquals(0, this.objectStorage.completeCalls);
		assertEquals(0, this.objectStorage.hashCalls);
	}

	@Test
	void recoversWhenStorageCompletedBeforeTheFirstResponseFailed() throws Exception {
		var batchId = initializeBatch(1, PART_SIZE, "multipart-batch-key-0012");
		var clientFileId = UUID.fromString("20000000-0000-0000-0000-000000000012");
		registerFile(batchId, clientFileId, PART_SIZE, "multipart-file-key-0012");
		startMultipart(batchId, clientFileId).andExpect(status().isCreated());
		authorizePart(batchId, clientFileId, 1, SHA256_B).andExpect(status().isOk());
		this.objectStorage.storePart(1, PART_SIZE, "synthetic-etag-1");
		this.objectStorage.failAfterComplete = true;

		completeFile(batchId, clientFileId)
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.retryable").value(true));

		this.objectStorage.failAfterComplete = false;
		completeFile(batchId, clientFileId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UPLOADED"));

		assertEquals(1, this.objectStorage.completeCalls);
		assertEquals(1, this.objectStorage.hashCalls);
	}

	@Test
	void rejectsConcurrentCompletionWhileItsLeaseIsActive() throws Exception {
		var batchId = initializeBatch(1, PART_SIZE, "multipart-batch-key-0013");
		var clientFileId = UUID.fromString("20000000-0000-0000-0000-000000000013");
		registerFile(batchId, clientFileId, PART_SIZE, "multipart-file-key-0013");
		startMultipart(batchId, clientFileId).andExpect(status().isCreated());
		authorizePart(batchId, clientFileId, 1, SHA256_B).andExpect(status().isOk());
		this.completionStateService.begin(PROJECT_ID, batchId, clientFileId);

		authorizePart(batchId, clientFileId, 1, SHA256_B)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("UPLOAD_FILE_NOT_UPLOADABLE"));
		completeFile(batchId, clientFileId)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("UPLOAD_FILE_COMPLETION_IN_PROGRESS"))
				.andExpect(jsonPath("$.retryable").value(true));
	}

	@Test
	void appliesTheMultipartMigrationAndConstraints() {
		var migrationCount = this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version IN ('3', '4', '5') AND success = true",
				Integer.class);
		var constraintCount = this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM information_schema.table_constraints "
						+ "WHERE table_name = 'upload_part' "
						+ "AND constraint_name IN ("
						+ "'uk_upload_part_file_number', 'ck_upload_part_sha256', 'ck_upload_part_confirmation')",
				Integer.class);
		var fileConstraintCount = this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM information_schema.table_constraints "
						+ "WHERE table_name = 'upload_file' "
						+ "AND constraint_name = 'ck_upload_file_completion_fields'",
				Integer.class);

		assertEquals(3, migrationCount);
		assertEquals(3, constraintCount);
		assertEquals(1, fileConstraintCount);
	}

	private UUID initializeBatch(int fileCount, long totalSizeBytes, String idempotencyKey) throws Exception {
		var request = """
				{
				  "project_id": "%s",
				  "device_id": "synthetic-device-multipart",
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

	private void registerFile(UUID batchId, UUID clientFileId, long sizeBytes, String idempotencyKey)
			throws Exception {
		var request = """
				{
				  "file_kind": "VIDEO",
				  "content_type": "video/mp4",
				  "size_bytes": %d,
				  "sha256": "%s",
				  "part_size_bytes": %d
				}
				""".formatted(sizeBytes, SHA256_A, PART_SIZE);
		this.mockMvc.perform(put("/api/v1/upload/{batchId}/files/{clientFileId}", batchId, clientFileId)
					.header("Authorization", "Bearer " + TOKEN)
					.header("Idempotency-Key", idempotencyKey)
					.contentType(MediaType.APPLICATION_JSON)
					.content(request))
				.andExpect(status().isCreated());
	}

	private org.springframework.test.web.servlet.ResultActions startMultipart(UUID batchId, UUID clientFileId)
			throws Exception {
		return this.mockMvc.perform(put(
				"/api/v1/upload/{batchId}/files/{clientFileId}/multipart",
				batchId,
				clientFileId)
				.header("Authorization", "Bearer " + TOKEN));
	}

	private org.springframework.test.web.servlet.ResultActions authorizePart(
			UUID batchId,
			UUID clientFileId,
			int partNumber,
			String sha256) throws Exception {
		return this.mockMvc.perform(post(
				"/api/v1/upload/{batchId}/files/{clientFileId}/parts/{partNumber}/authorization",
				batchId,
				clientFileId,
				partNumber)
				.header("Authorization", "Bearer " + TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"sha256\":\"%s\"}".formatted(sha256)));
	}

	private org.springframework.test.web.servlet.ResultActions confirmPart(
			UUID batchId,
			UUID clientFileId,
			int partNumber) throws Exception {
		return this.mockMvc.perform(put(
				"/api/v1/upload/{batchId}/files/{clientFileId}/parts/{partNumber}/confirmation",
				batchId,
				clientFileId,
				partNumber)
				.header("Authorization", "Bearer " + TOKEN));
	}

	private org.springframework.test.web.servlet.ResultActions getPartProgress(UUID batchId, UUID clientFileId)
			throws Exception {
		return this.mockMvc.perform(get(
				"/api/v1/upload/{batchId}/files/{clientFileId}/parts",
				batchId,
				clientFileId)
				.header("Authorization", "Bearer " + TOKEN));
	}

	private org.springframework.test.web.servlet.ResultActions reconcileParts(UUID batchId, UUID clientFileId)
			throws Exception {
		return this.mockMvc.perform(put(
				"/api/v1/upload/{batchId}/files/{clientFileId}/parts/reconciliation",
				batchId,
				clientFileId)
				.header("Authorization", "Bearer " + TOKEN));
	}

	private org.springframework.test.web.servlet.ResultActions completeFile(UUID batchId, UUID clientFileId)
			throws Exception {
		return this.mockMvc.perform(put(
				"/api/v1/upload/{batchId}/files/{clientFileId}/completion",
				batchId,
				clientFileId)
				.header("Authorization", "Bearer " + TOKEN));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeStorageConfiguration {

		@Bean
		@Primary
		FakeMultipartObjectStorage fakeMultipartObjectStorage() {
			return new FakeMultipartObjectStorage();
		}
	}

	static class FakeMultipartObjectStorage implements MultipartObjectStorage {

		private int createCalls;
		private int authorizeCalls;
		private int completeCalls;
		private int hashCalls;
		private final Map<Integer, StoredPart> storedParts = new HashMap<>();
		private CompletedObject completedObject;
		private String completedSha256 = SHA256_A;
		private boolean failCreate;
		private boolean failAuthorize;
		private boolean failInspect;
		private boolean failAfterComplete;

		@Override
		public CreatedUpload create(String objectKey, String contentType) {
			this.createCalls++;
			if (this.failCreate) {
				throw new MultipartObjectStorageException("Synthetic create failure.", null);
			}
			return new CreatedUpload("synthetic-bucket", objectKey, "synthetic-upload-" + this.createCalls);
		}

		@Override
		public URI authorizePart(CreatedUpload upload, int partNumber, Duration lifetime) {
			this.authorizeCalls++;
			if (this.failAuthorize) {
				throw new MultipartObjectStorageException("Synthetic authorize failure.", null);
			}
			return URI.create("https://storage.invalid/upload-part?signature=synthetic-" + this.authorizeCalls);
		}

		@Override
		public Optional<StoredPart> findPart(CreatedUpload upload, int partNumber) {
			if (this.failInspect) {
				throw new MultipartObjectStorageException("Synthetic inspect failure.", null);
			}
			return Optional.ofNullable(this.storedParts.get(partNumber));
		}

		@Override
		public List<StoredPart> listParts(CreatedUpload upload) {
			if (this.failInspect) {
				throw new MultipartObjectStorageException("Synthetic list failure.", null);
			}
			return this.storedParts.values().stream()
					.sorted(Comparator.comparingInt(StoredPart::partNumber))
					.toList();
		}

		@Override
		public CompletedObject complete(CreatedUpload upload, List<StoredPart> parts) {
			this.completeCalls++;
			var sizeBytes = parts.stream().mapToLong(StoredPart::sizeBytes).sum();
			this.completedObject = new CompletedObject(sizeBytes, "synthetic-completed-etag");
			this.storedParts.clear();
			if (this.failAfterComplete) {
				throw new MultipartObjectStorageException("Synthetic post-complete failure.", null);
			}
			return this.completedObject;
		}

		@Override
		public Optional<CompletedObject> findCompletedObject(CreatedUpload upload) {
			if (this.failInspect) {
				throw new MultipartObjectStorageException("Synthetic completed-object inspect failure.", null);
			}
			return Optional.ofNullable(this.completedObject);
		}

		@Override
		public String calculateSha256(CreatedUpload upload) {
			this.hashCalls++;
			return this.completedSha256;
		}

		@Override
		public byte[] readCompletedObject(ObjectReference object, int maxBytes) {
			return new byte[0];
		}

		@Override
		public void abort(CreatedUpload upload) {
		}

		void storePart(int partNumber, long sizeBytes, String eTag) {
			this.storedParts.put(partNumber, new StoredPart(partNumber, sizeBytes, eTag));
		}

		void removePart(int partNumber) {
			this.storedParts.remove(partNumber);
		}

		void reset() {
			this.createCalls = 0;
			this.authorizeCalls = 0;
			this.completeCalls = 0;
			this.hashCalls = 0;
			this.storedParts.clear();
			this.completedObject = null;
			this.completedSha256 = SHA256_A;
			this.failCreate = false;
			this.failAuthorize = false;
			this.failInspect = false;
			this.failAfterComplete = false;
		}
	}
}
