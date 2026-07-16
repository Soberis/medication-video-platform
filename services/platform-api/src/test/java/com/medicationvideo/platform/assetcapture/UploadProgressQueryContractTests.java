package com.medicationvideo.platform.assetcapture;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UploadProgressQueryContractTests {

	private static final String TOKEN = "synthetic-test-bearer-token-do-not-reuse";
	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID OTHER_PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
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

	@BeforeEach
	void clearUploads() {
		this.clipAssetRepository.deleteAll();
		this.partRepository.deleteAll();
		this.fileRepository.deleteAll();
		this.batchRepository.deleteAll();
	}

	@Test
	void readsPersistedBatchAndFileProgressWithoutStorageSideEffects() throws Exception {
		var fixture = persistProgressFixture(PROJECT_ID);

		this.mockMvc.perform(get("/api/v1/upload/{batchId}", fixture.batchId())
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(jsonPath("$.batch_id").value(fixture.batchId().toString()))
				.andExpect(jsonPath("$.project_id").value(PROJECT_ID.toString()))
				.andExpect(jsonPath("$.file_count").value(2))
				.andExpect(jsonPath("$.registered_file_count").value(2))
				.andExpect(jsonPath("$.uploaded_file_count").value(0))
				.andExpect(jsonPath("$.failed_file_count").value(1))
				.andExpect(jsonPath("$.total_size_bytes").value(PART_SIZE * 3))
				.andExpect(jsonPath("$.uploaded_size_bytes").value(PART_SIZE * 2))
				.andExpect(jsonPath("$.tracked_part_count").value(3))
				.andExpect(jsonPath("$.uploaded_part_count").value(2))
				.andExpect(jsonPath("$.status").value("UPLOADING"));

		this.mockMvc.perform(get("/api/v1/upload/{batchId}/files", fixture.batchId())
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(jsonPath("$.batch_id").value(fixture.batchId().toString()))
				.andExpect(jsonPath("$.registered_file_count").value(2))
				.andExpect(jsonPath("$.files.length()").value(2))
				.andExpect(jsonPath("$.files[0].client_file_id").value(fixture.uploadingClientFileId().toString()))
				.andExpect(jsonPath("$.files[0].status").value("UPLOADING"))
				.andExpect(jsonPath("$.files[0].tracked_parts").value(2))
				.andExpect(jsonPath("$.files[0].uploaded_parts").value(1))
				.andExpect(jsonPath("$.files[0].uploaded_size_bytes").value(PART_SIZE))
				.andExpect(jsonPath("$.files[0].verified_sha256").value(nullValue()))
				.andExpect(jsonPath("$.files[0].completion_error_code").value(nullValue()))
				.andExpect(jsonPath("$.files[1].client_file_id").value(fixture.failedClientFileId().toString()))
				.andExpect(jsonPath("$.files[1].status").value("FAILED"))
				.andExpect(jsonPath("$.files[1].tracked_parts").value(1))
				.andExpect(jsonPath("$.files[1].uploaded_parts").value(1))
				.andExpect(jsonPath("$.files[1].verified_sha256").value(SHA256_C))
				.andExpect(jsonPath("$.files[1].completion_error_code").value("UPLOAD_FILE_SHA256_MISMATCH"))
				.andExpect(jsonPath("$.files[1].completed_at").isNotEmpty());
	}

	@Test
	void returnsAnEmptyFileListBeforeManifestRegistration() throws Exception {
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var batch = UploadBatch.initiate(
				PROJECT_ID,
				"progress-empty-batch-0001",
				SHA256_A,
				"synthetic-device-01",
				1,
				PART_SIZE,
				SHA256_A,
				now.plusSeconds(3600),
				now);
		this.batchRepository.saveAndFlush(batch);

		this.mockMvc.perform(get("/api/v1/upload/{batchId}/files", batch.getBatchId())
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.registered_file_count").value(0))
				.andExpect(jsonPath("$.files").isEmpty());
	}

	@Test
	void hidesBatchesOutsideTheAuthenticatedProject() throws Exception {
		var fixture = persistProgressFixture(OTHER_PROJECT_ID);

		this.mockMvc.perform(get("/api/v1/upload/{batchId}", fixture.batchId())
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.error_code").value("UPLOAD_BATCH_NOT_FOUND"));

		this.mockMvc.perform(get("/api/v1/upload/{batchId}/files", fixture.batchId())
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error_code").value("UPLOAD_BATCH_NOT_FOUND"));
	}

	private Fixture persistProgressFixture(UUID projectId) {
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var batch = UploadBatch.initiate(
				projectId,
				"progress-batch-key-0001",
				SHA256_A,
				"synthetic-device-01",
				2,
				PART_SIZE * 3,
				SHA256_A,
				now.plusSeconds(3600),
				now);
		batch.markUploading(now.plus(1, ChronoUnit.MICROS));
		this.batchRepository.saveAndFlush(batch);

		var uploadingClientFileId = UUID.fromString("20000000-0000-0000-0000-000000000001");
		var uploading = UploadFile.register(
				batch.getBatchId(),
				uploadingClientFileId,
				"progress-file-key-0001",
				SHA256_A,
				new UploadFileRegistrationRequest(
						UploadFileKind.VIDEO,
						"video/mp4",
						PART_SIZE * 2,
						SHA256_A,
						PART_SIZE),
				2,
				now.plus(2, ChronoUnit.MICROS));
		uploading.attachMultipartSession(
				new MultipartObjectStorage.CreatedUpload("uploads", "progress/uploading", "uploading-id"),
				now.plus(3, ChronoUnit.MICROS));
		uploading.markUploading(now.plus(4, ChronoUnit.MICROS));
		this.fileRepository.saveAndFlush(uploading);

		var uploadingPart1 = UploadPart.authorize(
				uploading.getUploadFileId(),
				1,
				PART_SIZE,
				SHA256_A,
				now.plusSeconds(300),
				now.plus(5, ChronoUnit.MICROS));
		uploadingPart1.confirm(
				new MultipartObjectStorage.StoredPart(1, PART_SIZE, "etag-uploading-1"),
				now.plus(6, ChronoUnit.MICROS));
		var uploadingPart2 = UploadPart.authorize(
				uploading.getUploadFileId(),
				2,
				PART_SIZE,
				SHA256_B,
				now.plusSeconds(300),
				now.plus(7, ChronoUnit.MICROS));
		this.partRepository.saveAllAndFlush(java.util.List.of(uploadingPart1, uploadingPart2));

		var failedClientFileId = UUID.fromString("20000000-0000-0000-0000-000000000002");
		var failed = UploadFile.register(
				batch.getBatchId(),
				failedClientFileId,
				"progress-file-key-0002",
				SHA256_B,
				new UploadFileRegistrationRequest(
						UploadFileKind.METADATA,
						"application/json",
						PART_SIZE,
						SHA256_B,
						PART_SIZE),
				1,
				now.plus(8, ChronoUnit.MICROS));
		failed.attachMultipartSession(
				new MultipartObjectStorage.CreatedUpload("uploads", "progress/failed", "failed-id"),
				now.plus(9, ChronoUnit.MICROS));
		failed.markUploading(now.plus(10, ChronoUnit.MICROS));
		var attemptId = UUID.fromString("30000000-0000-0000-0000-000000000001");
		failed.startCompletion(attemptId, now.plusSeconds(60), now.plus(11, ChronoUnit.MICROS));
		failed.markCompletionFailed(
				attemptId,
				new MultipartObjectStorage.CompletedObject(PART_SIZE, "etag-failed"),
				SHA256_C,
				"UPLOAD_FILE_SHA256_MISMATCH",
				now.plus(12, ChronoUnit.MICROS));
		this.fileRepository.saveAndFlush(failed);

		var failedPart = UploadPart.authorize(
				failed.getUploadFileId(),
				1,
				PART_SIZE,
				SHA256_B,
				now.plusSeconds(300),
				now.plus(13, ChronoUnit.MICROS));
		failedPart.confirm(
				new MultipartObjectStorage.StoredPart(1, PART_SIZE, "etag-failed-1"),
				now.plus(14, ChronoUnit.MICROS));
		this.partRepository.saveAndFlush(failedPart);

		return new Fixture(batch.getBatchId(), uploadingClientFileId, failedClientFileId);
	}

	private record Fixture(UUID batchId, UUID uploadingClientFileId, UUID failedClientFileId) {
	}
}
