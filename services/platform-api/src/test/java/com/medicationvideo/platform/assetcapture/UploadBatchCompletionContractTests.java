package com.medicationvideo.platform.assetcapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

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
class UploadBatchCompletionContractTests {

	private static final String TOKEN = "synthetic-test-bearer-token-do-not-reuse";
	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID OTHER_PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final UUID VIDEO_CLIENT_FILE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID METADATA_CLIENT_FILE_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
	private static final UUID EVENT_LOG_CLIENT_FILE_ID = UUID.fromString("10000000-0000-0000-0000-000000000003");
	private static final String SHA256_A = "a".repeat(64);
	private static final String SHA256_B = "b".repeat(64);
	private static final long PART_SIZE = 8_388_608L;

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
	private CaptureEventLogRepository captureEventLogRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clearUploads() {
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, PROJECT_ID);
		this.captureEventLogRepository.deleteAll();
		this.clipAssetRepository.deleteAll();
		this.partRepository.deleteAll();
		this.fileRepository.deleteAll();
		this.batchRepository.deleteAll();
	}

	@Test
	void usesTheDocumentedUploadFileManifestV1CanonicalForm() {
		var request = new UploadFileRegistrationRequest(
				UploadFileKind.VIDEO,
				"video/mp4",
				12L,
				SHA256_A,
				PART_SIZE);
		var file = UploadFile.register(
				UUID.fromString("20000000-0000-0000-0000-000000000001"),
				VIDEO_CLIENT_FILE_ID,
				"manifest-vector-key-0001",
				SHA256_A,
				request,
				1,
				Instant.EPOCH);

		assertEquals(
				"3517d8657adcc46ad94e4e13beed6a74e067f251770636da84189448944098a7",
				UploadBatchManifest.sha256(List.of(file)));
	}

	@Test
	void completesAndIdempotentlyBindsOnlyUploadedVideos() throws Exception {
		var entries = List.of(
				new ManifestEntry(VIDEO_CLIENT_FILE_ID, UploadFileKind.VIDEO, "video/mp4", 12L, SHA256_A, PART_SIZE),
				new ManifestEntry(
						METADATA_CLIENT_FILE_ID,
						UploadFileKind.METADATA,
						"application/json",
						5L,
						SHA256_B,
						PART_SIZE),
				new ManifestEntry(
						EVENT_LOG_CLIENT_FILE_ID,
						UploadFileKind.EVENT_LOG,
						"application/x-ndjson",
						6L,
						SHA256_A,
						PART_SIZE));
		var batch = uploadedBatch(PROJECT_ID, entries);

		var first = complete(batch.getBatchId())
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.batch_id").value(batch.getBatchId().toString()))
				.andExpect(jsonPath("$.project_id").value(PROJECT_ID.toString()))
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.file_count").value(3))
				.andExpect(jsonPath("$.total_size_bytes").value(23))
				.andExpect(jsonPath("$.manifest_sha256").value(batch.getManifestSha256()))
				.andExpect(jsonPath("$.verified_manifest_sha256").value(batch.getManifestSha256()))
				.andExpect(jsonPath("$.clip_asset_count").value(1))
				.andExpect(jsonPath("$.clip_assets[0].clip_asset_id").isNotEmpty())
				.andExpect(jsonPath("$.clip_assets[0].client_file_id").value(VIDEO_CLIENT_FILE_ID.toString()))
				.andExpect(jsonPath("$.clip_assets[0].sha256").value(SHA256_A))
				.andExpect(jsonPath("$.clip_assets[0].status").value("UPLOADED"))
				.andExpect(jsonPath("$.completed_at").isNotEmpty())
				.andReturn();

		var replay = complete(batch.getBatchId())
				.andExpect(status().isOk())
				.andReturn();

		assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
		assertEquals(1, this.clipAssetRepository.count());
		assertEquals(1, this.captureEventLogRepository.count());
		var eventLog = this.captureEventLogRepository.findAll().getFirst();
		assertEquals(EVENT_LOG_CLIENT_FILE_ID, eventLog.getClientFileId());
		assertEquals("synthetic-edge-01", eventLog.getDeviceId());
		assertEquals("application/x-ndjson", eventLog.getContentType());
		assertEquals(
				UploadBatchStatus.COMPLETED,
				this.batchRepository.findById(batch.getBatchId()).orElseThrow().getStatus());
	}

	@Test
	void rejectsIncompleteFilesWithoutCreatingAssets() throws Exception {
		var entry = new ManifestEntry(VIDEO_CLIENT_FILE_ID, UploadFileKind.VIDEO, "video/mp4", 12L, SHA256_A, PART_SIZE);
		var batch = batchWithFiles(PROJECT_ID, List.of(entry), false, manifestSha256(List.of(entry)));

		complete(batch.getBatchId())
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.error_code").value("UPLOAD_BATCH_FILES_INCOMPLETE"))
				.andExpect(jsonPath("$.retryable").value(false));

		assertEquals(0, this.clipAssetRepository.count());
		assertEquals(0, this.captureEventLogRepository.count());
		assertEquals(UploadBatchStatus.UPLOADING, this.batchRepository.findById(batch.getBatchId()).orElseThrow().getStatus());
	}

	@Test
	void rejectsAManifestDigestMismatch() throws Exception {
		var entry = new ManifestEntry(VIDEO_CLIENT_FILE_ID, UploadFileKind.VIDEO, "video/mp4", 12L, SHA256_A, PART_SIZE);
		var batch = batchWithFiles(PROJECT_ID, List.of(entry), true, "c".repeat(64));

		complete(batch.getBatchId())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("UPLOAD_BATCH_MANIFEST_CHECKSUM_MISMATCH"));

		assertEquals(0, this.clipAssetRepository.count());
	}

	@Test
	void requiresAtLeastOneVideoFile() throws Exception {
		var entry = new ManifestEntry(
				METADATA_CLIENT_FILE_ID,
				UploadFileKind.METADATA,
				"application/json",
				5L,
				SHA256_B,
				PART_SIZE);
		var batch = uploadedBatch(PROJECT_ID, List.of(entry));

		complete(batch.getBatchId())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("UPLOAD_BATCH_VIDEO_REQUIRED"));
	}

	@Test
	void hidesBatchesOwnedByAnotherProject() throws Exception {
		var entry = new ManifestEntry(VIDEO_CLIENT_FILE_ID, UploadFileKind.VIDEO, "video/mp4", 12L, SHA256_A, PART_SIZE);
		var batch = uploadedBatch(OTHER_PROJECT_ID, List.of(entry));

		complete(batch.getBatchId())
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error_code").value("UPLOAD_BATCH_NOT_FOUND"));
	}

	@Test
	void appliesTheBatchCompletionAndClipAssetMigration() {
		var migrationCount = this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version = '6' AND success = true",
				Integer.class);
		var constraintCount = this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM information_schema.table_constraints "
						+ "WHERE table_name = 'clip_asset' "
						+ "AND constraint_name IN "
						+ "('fk_clip_asset_batch', 'fk_clip_asset_source_upload_file', "
						+ "'uk_clip_asset_source_upload_file')",
				Integer.class);

		assertEquals(1, migrationCount);
		assertEquals(3, constraintCount);
	}

	private UploadBatch uploadedBatch(UUID projectId, List<ManifestEntry> entries) {
		return batchWithFiles(projectId, entries, true, manifestSha256(entries));
	}

	private UploadBatch batchWithFiles(
			UUID projectId,
			List<ManifestEntry> entries,
			boolean uploaded,
			String declaredManifestSha256) {
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var totalSize = entries.stream().mapToLong(ManifestEntry::sizeBytes).sum();
		var batch = UploadBatch.initiate(
				projectId,
				"batch-complete-key-" + UUID.randomUUID(),
				SHA256_A,
				"synthetic-edge-01",
				"synthetic-test-actor",
				entries.size(),
				totalSize,
				declaredManifestSha256,
				now.plusSeconds(3600),
				now);
		batch.markUploading(now);
		this.batchRepository.saveAndFlush(batch);

		for (var index = 0; index < entries.size(); index++) {
			var entry = entries.get(index);
			var request = new UploadFileRegistrationRequest(
					entry.fileKind(),
					entry.contentType(),
					entry.sizeBytes(),
					entry.sha256(),
					entry.partSizeBytes());
			var file = UploadFile.register(
					batch.getBatchId(),
					entry.clientFileId(),
					"file-complete-key-" + entry.clientFileId(),
					SHA256_A,
					request,
					1,
					now.plus(index, ChronoUnit.MICROS));
			file.attachMultipartSession(
					new MultipartObjectStorage.CreatedUpload(
							"synthetic-bucket",
							"synthetic/" + file.getUploadFileId(),
							"synthetic-upload-" + file.getUploadFileId()),
					now);
			file.markUploading(now);
			if (uploaded) {
				var attemptId = UUID.randomUUID();
				file.startCompletion(attemptId, now.plusSeconds(60), now);
				file.markUploaded(
						attemptId,
						new MultipartObjectStorage.CompletedObject(entry.sizeBytes(), "etag-" + file.getUploadFileId()),
						entry.sha256(),
						now.plusSeconds(1));
			}
			this.fileRepository.saveAndFlush(file);
		}
		return batch;
	}

	private org.springframework.test.web.servlet.ResultActions complete(UUID batchId) throws Exception {
		return this.mockMvc.perform(put("/api/v1/upload/{batchId}/completion", batchId)
				.header("Authorization", "Bearer " + TOKEN));
	}

	private static String manifestSha256(List<ManifestEntry> entries) {
		var canonical = new StringBuilder(UploadBatchManifest.FORMAT).append('\n');
		entries.stream()
				.sorted(Comparator.comparing(entry -> entry.clientFileId().toString()))
				.forEach(entry -> canonical
						.append(entry.clientFileId()).append('\t')
						.append(entry.fileKind()).append('\t')
						.append(entry.contentType()).append('\t')
						.append(entry.sizeBytes()).append('\t')
						.append(entry.sha256()).append('\t')
						.append(entry.partSizeBytes()).append('\n'));
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private record ManifestEntry(
			UUID clientFileId,
			UploadFileKind fileKind,
			String contentType,
			long sizeBytes,
			String sha256,
			long partSizeBytes) {
	}
}
