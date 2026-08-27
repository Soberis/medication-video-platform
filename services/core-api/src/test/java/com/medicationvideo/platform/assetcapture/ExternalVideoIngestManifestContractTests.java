package com.medicationvideo.platform.assetcapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.medicationvideo.platform.support.ProjectMembershipTestData;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExternalVideoIngestManifestContractTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final UUID OTHER_PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

	private static final String TOKEN = "synthetic-test-bearer-token-do-not-reuse";

	private static final String ADMIN_TOKEN = "synthetic-test-admin-bearer-token-do-not-reuse";

	private static final long PART_SIZE = 8_388_608L;

	private static final String VIDEO_SHA = "a".repeat(64);

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ExternalVideoIngestManifestRepository manifestRepository;

	@Autowired
	private UploadBatchRepository batchRepository;

	@Autowired
	private UploadBatchTerminationService terminationService;

	@BeforeEach
	void ensureProjects() {
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, PROJECT_ID);
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, OTHER_PROJECT_ID);
	}

	@AfterEach
	void removeOtherProjectMembership() {
		this.jdbcTemplate.update("DELETE FROM membership WHERE project_id = ?", OTHER_PROJECT_ID);
	}

	@Test
	void acceptsAnExplicitExternalV1ManifestWithoutGuessingCameraIdentity() throws Exception {
		var key = "edge-group-accept-" + UUID.randomUUID();
		this.accept(validManifest(key))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.schema_version").value("external-video-ingest-v1"))
			.andExpect(jsonPath("$.capture_group_key").value(key))
			.andExpect(jsonPath("$.expected_camera_count").value(2))
			.andExpect(jsonPath("$.video_count").value(2));
	}

	@Test
	void replaysIdenticalManifestContentIdempotently() throws Exception {
		var key = "edge-group-replay-" + UUID.randomUUID();
		var first = this.accept(validManifest(key)).andExpect(status().isAccepted()).andReturn();
		var replay = this.accept(validManifest(key)).andExpect(status().isAccepted()).andReturn();

		assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
		assertEquals(1,
				this.manifestRepository
					.findByProjectIdAndCaptureGroupKeyAndKeySource(PROJECT_ID, key,
							ExternalVideoCaptureGroupKeySource.UPSTREAM_MANIFEST_KEY)
					.stream()
					.count());
	}

	@Test
	void truncatesManifestInstantsToMicrosBeforeComparingReplayContent() throws Exception {
		var key = "edge-group-micros-" + UUID.randomUUID();
		var highPrecision = validManifest(key).replace("2026-08-11T02:00:00Z", "2026-08-11T02:00:00.123456789Z");
		var microsReplay = validManifest(key).replace("2026-08-11T02:00:00Z", "2026-08-11T02:00:00.123456000Z");
		var first = this.accept(highPrecision).andExpect(status().isAccepted()).andReturn();
		var replay = this.accept(microsReplay).andExpect(status().isAccepted()).andReturn();

		assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
		assertEquals("2026-08-11T02:00:00.123456Z",
				this.manifestRepository.findById(manifestId(key, PROJECT_ID)).orElseThrow().getCapturedAt().toString());
	}

	@Test
	void rejectsAnInvalidManifestTimezoneBeforePersistingFacts() throws Exception {
		var key = "edge-group-timezone-" + UUID.randomUUID();
		this.accept(validManifest(key).replace("Asia/Shanghai", "Mars/Base"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error_code").value("EXTERNAL_MANIFEST_TIMEZONE_INVALID"));
		assertEquals(0,
				this.manifestRepository
					.findByProjectIdAndCaptureGroupKeyAndKeySource(PROJECT_ID, key,
							ExternalVideoCaptureGroupKeySource.UPSTREAM_MANIFEST_KEY)
					.stream()
					.count());
	}

	@Test
	void rejectsDifferentContentForTheSameCaptureGroupKeyAsAConflict() throws Exception {
		var key = "edge-group-conflict-" + UUID.randomUUID();
		this.accept(validManifest(key)).andExpect(status().isAccepted());

		this.accept(validManifest(key).replace("synthetic-edge", "other-edge"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("EXTERNAL_MANIFEST_CONFLICT"));
	}

	@Test
	void acceptsExpectedCameraGapsWithoutRenumberingTheDeclaredVideos() throws Exception {
		var key = "edge-group-gap-" + UUID.randomUUID();
		this.accept(gappedManifest(key))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.expected_camera_count").value(5))
			.andExpect(jsonPath("$.video_count").value(3));
	}

	@Test
	void acceptsV2ReceiptStatesAndRequiresExplicitFinalizationForFiveCameras() throws Exception {
		var key = "edge-group-v2-receipt-" + UUID.randomUUID();
		this.acceptV2(v2Manifest(key, List.of(1, 2, 3, 4, 5), List.of(1, 3, 5), false))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.schema_version").value("external-video-ingest-v2"))
			.andExpect(jsonPath("$.expected_camera_numbers.length()").value(5))
			.andExpect(jsonPath("$.expected_camera_numbers[1]").value(2))
			.andExpect(jsonPath("$.received_camera_numbers.length()").value(3))
			.andExpect(jsonPath("$.received_camera_numbers[1]").value(3))
			.andExpect(jsonPath("$.missing_camera_numbers.length()").value(2))
			.andExpect(jsonPath("$.missing_camera_numbers[0]").value(2))
			.andExpect(jsonPath("$.missing_camera_numbers[1]").value(4))
			.andExpect(jsonPath("$.upload_set_finalized").value(false))
			.andExpect(jsonPath("$.receipt_state").value("RECEIVING"));

		this.acceptV2(v2Manifest(key, List.of(1, 2, 3, 4, 5), List.of(1, 3, 5), true))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.received_camera_numbers.length()").value(3))
			.andExpect(jsonPath("$.received_camera_numbers[1]").value(3))
			.andExpect(jsonPath("$.missing_camera_numbers.length()").value(2))
			.andExpect(jsonPath("$.missing_camera_numbers[0]").value(2))
			.andExpect(jsonPath("$.missing_camera_numbers[1]").value(4))
			.andExpect(jsonPath("$.upload_set_finalized").value(true))
			.andExpect(jsonPath("$.receipt_state").value("INCOMPLETE"));

		this.acceptV2(v2Manifest(key, List.of(1, 2, 3, 4, 5), List.of(1, 2, 3, 5), true))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("EXTERNAL_MANIFEST_FINALIZED"));
	}

	@Test
	void acceptsASingleExplicitCameraAsACompleteCaptureGroup() throws Exception {
		var key = "edge-group-v2-single-" + UUID.randomUUID();
		this.acceptV2(v2Manifest(key, List.of(7), List.of(7), true))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.expected_camera_numbers.length()").value(1))
			.andExpect(jsonPath("$.expected_camera_numbers[0]").value(7))
			.andExpect(jsonPath("$.received_camera_numbers.length()").value(1))
			.andExpect(jsonPath("$.received_camera_numbers[0]").value(7))
			.andExpect(jsonPath("$.missing_camera_numbers").isEmpty())
			.andExpect(jsonPath("$.receipt_state").value("COMPLETE"));
	}

	@Test
	void acceptsLateCameraDeclarationsBeforeFinalizationAndRejectsDigestConflicts() throws Exception {
		var key = "edge-group-v2-late-" + UUID.randomUUID();
		this.acceptV2(v2Manifest(key, List.of(1, 2, 3), List.of(1), false))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.receipt_state").value("RECEIVING"));

		this.acceptV2(v2Manifest(key, List.of(1, 2, 3), List.of(1, 3), false))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.received_camera_numbers.length()").value(2))
			.andExpect(jsonPath("$.received_camera_numbers[1]").value(3))
			.andExpect(jsonPath("$.missing_camera_numbers.length()").value(1))
			.andExpect(jsonPath("$.missing_camera_numbers[0]").value(2));

		this.acceptV2(v2Manifest(key, List.of(1, 2, 3), List.of(1, 3), false)
			.replace("\"sha256\": \"" + VIDEO_SHA + "\"", "\"sha256\": \"" + "f".repeat(64) + "\""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("EXTERNAL_MANIFEST_CAMERA_DIGEST_CONFLICT"));
	}

	@Test
	void timeoutExpiresTheBatchButDoesNotFinalizeTheCaptureGroupReceipt() throws Exception {
		var key = "edge-group-v2-timeout-" + UUID.randomUUID();
		this.acceptV2(v2Manifest(key, List.of(1), List.of(1), false)).andExpect(status().isAccepted());
		var manifestId = manifestId(key, PROJECT_ID);
		var batchId = UUID.fromString(jsonField(
				initializeBatch(manifestId, "batch-v2-timeout-" + UUID.randomUUID()).andExpect(status().isCreated())
					.andReturn()
					.getResponse()
					.getContentAsString(),
				"batch_id"));

		this.jdbcTemplate.update(
				"UPDATE upload_batch SET created_at = now() - interval '2 minutes', expires_at = now() - interval '1 minute' "
						+ "WHERE batch_id = ?",
				batchId);
		assertTrue(this.terminationService.expireNextDue());
		var manifest = this.manifestRepository.findById(manifestId).orElseThrow();
		assertFalse(manifest.isUploadSetFinalized());
		assertEquals(ExternalVideoIngestManifestStatus.ACCEPTED, manifest.getStatus());
	}

	@Test
	void rejectsV2RequestsWithoutAnExplicitCaptureGroup() throws Exception {
		this.acceptV2("""
				{
				  "schema_version": "external-video-ingest-v2",
				  "source_system": "synthetic-edge",
				  "source_device_id": "cabinet-001",
				  "videos": [],
				  "upload_set_finalized": false
				}
				""").andExpect(status().isBadRequest());
	}

	@Test
	void rejectsAnUnprovidedCameraNumber() throws Exception {
		this.accept(gappedManifest("edge-group-missing-number-" + UUID.randomUUID()).replace("\"camera_number\": 3",
				"\"camera_number\": null"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void rejectsDuplicateCameraNumbers() throws Exception {
		this.accept(gappedManifest("edge-group-duplicate-number-" + UUID.randomUUID()).replace("\"camera_number\": 5",
				"\"camera_number\": 3"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void rejectsUnknownCameraNumbers() throws Exception {
		this.accept(validManifest("edge-group-unknown-number-" + UUID.randomUUID()).replace("\"camera_number\": 2",
				"\"camera_number\": 3"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void bindsAManifestOnceAndReplaysTheSameBatchSafely() throws Exception {
		var key = "edge-group-bound-" + UUID.randomUUID();
		var manifestJson = validManifest(key);
		this.accept(manifestJson).andExpect(status().isAccepted());
		var manifestId = manifestId(key, PROJECT_ID);

		var firstKey = "batch-bound-first-" + UUID.randomUUID();
		var first = initializeBatch(manifestId, firstKey).andExpect(status().isCreated())
			.andExpect(jsonPath("$.external_manifest_id").value(manifestId.toString()))
			.andReturn();
		var replay = initializeBatch(manifestId, firstKey).andExpect(status().isCreated()).andReturn();

		assertTrue(first.getResponse().getContentAsString().contains("\"external_manifest_id\""));
		assertEquals(jsonField(first.getResponse().getContentAsString(), "batch_id"),
				jsonField(replay.getResponse().getContentAsString(), "batch_id"));
		assertEquals(1,
				this.batchRepository.findAll()
					.stream()
					.filter(batch -> manifestId.equals(batch.getExternalManifestId()))
					.count());

		initializeBatch(manifestId, "batch-bound-second-" + UUID.randomUUID()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("EXTERNAL_MANIFEST_ALREADY_BOUND"));
		assertEquals(ExternalVideoIngestManifestStatus.BOUND,
				this.manifestRepository.findById(manifestId).orElseThrow().getStatus());
	}

	@Test
	void concurrentBatchInitializationCanBindOnlyOneBatch() throws Exception {
		var key = "edge-group-concurrent-" + UUID.randomUUID();
		this.accept(validManifest(key)).andExpect(status().isAccepted());
		var manifestId = manifestId(key, PROJECT_ID);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(
					() -> initializeBatchConcurrently(manifestId, "batch-concurrent-a-" + UUID.randomUUID(), start));
			var second = executor.submit(
					() -> initializeBatchConcurrently(manifestId, "batch-concurrent-b-" + UUID.randomUUID(), start));
			start.countDown();
			var statuses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
			assertTrue(statuses.contains(201));
			assertTrue(statuses.contains(409));
		}

		assertEquals(1,
				this.batchRepository.findAll()
					.stream()
					.filter(batch -> manifestId.equals(batch.getExternalManifestId()))
					.count());
		assertEquals(ExternalVideoIngestManifestStatus.BOUND,
				this.manifestRepository.findById(manifestId).orElseThrow().getStatus());
	}

	@Test
	void concurrentIdenticalManifestAcceptsReplayTheSameCommittedRow() throws Exception {
		var key = "edge-group-concurrent-replay-" + UUID.randomUUID();
		var manifestJson = validManifest(key);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> acceptConcurrently(manifestJson, start));
			var second = executor.submit(() -> acceptConcurrently(manifestJson, start));
			start.countDown();
			assertEquals(202, first.get(20, TimeUnit.SECONDS));
			assertEquals(202, second.get(20, TimeUnit.SECONDS));
		}
		assertEquals(1,
				this.manifestRepository
					.findByProjectIdAndCaptureGroupKeyAndKeySource(PROJECT_ID, key,
							ExternalVideoCaptureGroupKeySource.UPSTREAM_MANIFEST_KEY)
					.stream()
					.count());
	}

	@Test
	void releasesAnAbortedManifestForOneControlledRetry() throws Exception {
		var key = "edge-group-rebind-" + UUID.randomUUID();
		this.accept(validManifest(key)).andExpect(status().isAccepted());
		var manifestId = manifestId(key, PROJECT_ID);
		var firstKey = "batch-rebind-first-" + UUID.randomUUID();
		var first = initializeBatch(manifestId, firstKey).andExpect(status().isCreated()).andReturn();
		var firstBatchId = UUID.fromString(jsonField(first.getResponse().getContentAsString(), "batch_id"));

		this.mockMvc
			.perform(put("/api/v1/upload/{batchId}/abort", firstBatchId).header("Authorization", "Bearer " + TOKEN)
				.header("X-Request-Id", "abort-rebind-" + UUID.randomUUID()))
			.andExpect(status().isOk());
		assertEquals(ExternalVideoIngestManifestStatus.ACCEPTED,
				this.manifestRepository.findById(manifestId).orElseThrow().getStatus());

		initializeBatch(manifestId, "batch-rebind-second-" + UUID.randomUUID()).andExpect(status().isCreated());
		assertEquals(ExternalVideoIngestManifestStatus.BOUND,
				this.manifestRepository.findById(manifestId).orElseThrow().getStatus());
	}

	@Test
	void rejectsAFileNameMismatchAgainstTheBoundManifest() throws Exception {
		var manifestId = acceptedManifestId("edge-group-file-name-" + UUID.randomUUID());
		var batchKey = "batch-file-name-" + UUID.randomUUID();
		initializeBatch(manifestId, batchKey).andExpect(status().isCreated());
		var batch = this.batchRepository.findByProjectIdAndIdempotencyKey(PROJECT_ID, batchKey).orElseThrow();

		registerVideo(batch.getBatchId(), "renamed-camera-1.mp4", 100, VIDEO_SHA).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("EXTERNAL_MANIFEST_VIDEO_MISMATCH"));
	}

	@Test
	void rejectsAFileSizeMismatchAgainstTheBoundManifest() throws Exception {
		var manifestId = acceptedManifestId("edge-group-file-size-" + UUID.randomUUID());
		var batchKey = "batch-file-size-" + UUID.randomUUID();
		initializeBatch(manifestId, batchKey).andExpect(status().isCreated());
		var batch = this.batchRepository.findByProjectIdAndIdempotencyKey(PROJECT_ID, batchKey).orElseThrow();

		registerVideo(batch.getBatchId(), "camera-1.mp4", 101, VIDEO_SHA).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("EXTERNAL_MANIFEST_VIDEO_MISMATCH"));
	}

	@Test
	void rejectsASha256MismatchAgainstTheBoundManifest() throws Exception {
		var manifestId = acceptedManifestId("edge-group-file-sha-" + UUID.randomUUID());
		var batchKey = "batch-file-sha-" + UUID.randomUUID();
		initializeBatch(manifestId, batchKey).andExpect(status().isCreated());
		var batch = this.batchRepository.findByProjectIdAndIdempotencyKey(PROJECT_ID, batchKey).orElseThrow();

		registerVideo(batch.getBatchId(), "camera-1.mp4", 100, "b".repeat(64)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("EXTERNAL_MANIFEST_VIDEO_MISMATCH"));
	}

	@Test
	void rejectsBindingAManifestFromAnotherProject() throws Exception {
		var key = "edge-group-cross-project-" + UUID.randomUUID();
		this.accept(validManifest(key)).andExpect(status().isAccepted());
		var manifestId = manifestId(key, PROJECT_ID);

		this.mockMvc
			.perform(post("/api/v1/upload/init").header("Authorization", "Bearer " + TOKEN)
				.header("Idempotency-Key", "batch-cross-project-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(batchRequest(OTHER_PROJECT_ID, manifestId)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error_code").value("EXTERNAL_MANIFEST_NOT_FOUND"));
	}

	@Test
	void rejectsBindingWhenManifestDeviceDiffersFromBatchDevice() throws Exception {
		var key = "edge-group-device-mismatch-" + UUID.randomUUID();
		this.accept(validManifest(key).replace("\"source_device_id\": \"cabinet-001\"",
				"\"source_device_id\": \"cabinet-002\""))
			.andExpect(status().isAccepted());
		var manifestId = manifestId(key, PROJECT_ID);
		initializeBatch(manifestId, "batch-device-mismatch-" + UUID.randomUUID()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("EXTERNAL_MANIFEST_DEVICE_MISMATCH"));
	}

	@Test
	void failedExternalBatchValidationLeavesTheManifestAccepted() throws Exception {
		var key = "edge-group-no-ghost-bound-" + UUID.randomUUID();
		this.accept(validManifest(key)).andExpect(status().isAccepted());
		var manifestId = manifestId(key, PROJECT_ID);

		this.mockMvc
			.perform(post("/api/v1/upload/init").header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", "batch-no-ghost-bound-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(batchRequest(PROJECT_ID, manifestId).replace("\"HOSPITAL_REMOTE\"", "\"PLATFORM_LOCAL\"")
					.replace("\"cabinet-001\"", "\"PLATFORM_LOCAL\"")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error_code").value("EXTERNAL_MANIFEST_SOURCE_UNSUPPORTED"));

		assertEquals(ExternalVideoIngestManifestStatus.ACCEPTED,
				this.manifestRepository.findById(manifestId).orElseThrow().getStatus());
	}

	private ResultActions accept(String manifest) throws Exception {
		return this.mockMvc.perform(post("/api/v1/projects/{projectId}/external-video-ingest-manifests", PROJECT_ID)
			.header("Authorization", "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(manifest));
	}

	private ResultActions acceptV2(String manifest) throws Exception {
		return this.mockMvc.perform(post("/api/v1/projects/{projectId}/external-video-ingest-manifests/v2", PROJECT_ID)
			.header("Authorization", "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(manifest));
	}

	private ResultActions initializeBatch(UUID manifestId, String idempotencyKey) throws Exception {
		return this.mockMvc.perform(post("/api/v1/upload/init").header("Authorization", "Bearer " + TOKEN)
			.header("Idempotency-Key", idempotencyKey)
			.contentType(MediaType.APPLICATION_JSON)
			.content(batchRequest(PROJECT_ID, manifestId)));
	}

	private int initializeBatchConcurrently(UUID manifestId, String idempotencyKey, CountDownLatch start)
			throws Exception {
		if (start != null) {
			start.await();
		}
		return initializeBatch(manifestId, idempotencyKey).andReturn().getResponse().getStatus();
	}

	private int acceptConcurrently(String manifestJson, CountDownLatch start) throws Exception {
		start.await();
		return accept(manifestJson).andReturn().getResponse().getStatus();
	}

	private ResultActions registerVideo(UUID batchId, String fileName, long size, String sha256) throws Exception {
		return this.mockMvc.perform(put("/api/v1/upload/{batchId}/files/{clientFileId}", batchId,
				UUID.fromString("00000000-0000-0000-0000-000000000101"))
			.header("Authorization", "Bearer " + TOKEN)
			.header("Idempotency-Key", "file-register-" + UUID.randomUUID())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{
					  "file_kind": "VIDEO",
					  "content_type": "video/mp4",
					  "size_bytes": %d,
					  "sha256": "%s",
					  "part_size_bytes": %d,
					  "file_name": "%s"
					}
					""".formatted(size, sha256, PART_SIZE, fileName)));
	}

	private UUID acceptedManifestId(String key) throws Exception {
		this.accept(validManifest(key)).andExpect(status().isAccepted());
		return manifestId(key, PROJECT_ID);
	}

	private UUID manifestId(String key, UUID projectId) {
		return this.manifestRepository
			.findByProjectIdAndCaptureGroupKeyAndKeySource(projectId, key,
					ExternalVideoCaptureGroupKeySource.UPSTREAM_MANIFEST_KEY)
			.orElseThrow()
			.getManifestId();
	}

	private static String batchRequest(UUID projectId, UUID manifestId) {
		return """
				{
				  "project_id": "%s",
				  "device_id": "cabinet-001",
				  "upload_source_type": "HOSPITAL_REMOTE",
				  "file_count": 1,
				  "total_size_bytes": 100,
				  "manifest_sha256": "%s",
				  "external_manifest_id": "%s"
				}
				""".formatted(projectId, VIDEO_SHA, manifestId);
	}

	private static String validManifest(String key) {
		return """
				{
				  "schema_version": "external-video-ingest-v1",
				  "source_system": "synthetic-edge",
				  "source_device_id": "cabinet-001",
				  "capture_group": {
				    "capture_group_key": "%s",
				    "key_source": "UPSTREAM_MANIFEST_KEY",
				    "captured_at": "2026-08-11T02:00:00Z",
				    "timezone": "Asia/Shanghai",
				    "expected_camera_numbers": [1, 2]
				  },
				  "videos": [
				    {
				      "client_file_id": "00000000-0000-0000-0000-000000000101",
				      "camera_number": 1,
				      "file_name": "camera-1.mp4",
				      "declared_size_bytes": 100,
				      "sha256": "%s"
				    },
				    {
				      "client_file_id": "00000000-0000-0000-0000-000000000102",
				      "camera_number": 2,
				      "file_name": "camera-2.mp4",
				      "declared_size_bytes": 100,
				      "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
				    }
				  ]
				}
				""".formatted(key, VIDEO_SHA);
	}

	private static String gappedManifest(String key) {
		return """
				{
				  "schema_version": "external-video-ingest-v1",
				  "source_system": "synthetic-edge",
				  "source_device_id": "cabinet-001",
				  "capture_group": {
				    "capture_group_key": "%s",
				    "key_source": "UPSTREAM_MANIFEST_KEY",
				    "captured_at": "2026-08-11T02:00:00Z",
				    "timezone": "Asia/Shanghai",
				    "expected_camera_numbers": [1, 2, 3, 4, 5]
				  },
				  "videos": [
				    {
				      "client_file_id": "00000000-0000-0000-0000-000000000101",
				      "camera_number": 1,
				      "file_name": "camera-1.mp4",
				      "declared_size_bytes": 100,
				      "sha256": "%s"
				    },
				    {
				      "client_file_id": "00000000-0000-0000-0000-000000000103",
				      "camera_number": 3,
				      "file_name": "camera-3.mp4",
				      "declared_size_bytes": 300,
				      "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
				    },
				    {
				      "client_file_id": "00000000-0000-0000-0000-000000000105",
				      "camera_number": 5,
				      "file_name": "camera-5.mp4",
				      "declared_size_bytes": 500,
				      "sha256": "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
				    }
				  ]
				}
				""".formatted(key, VIDEO_SHA);
	}

	private static String v2Manifest(String key, List<Integer> expectedCameraNumbers,
			List<Integer> receivedCameraNumbers, boolean uploadSetFinalized) {
		var expected = expectedCameraNumbers.stream()
			.map(String::valueOf)
			.collect(java.util.stream.Collectors.joining(", "));
		var videos = receivedCameraNumbers.stream()
			.map(cameraNumber -> """
					{
					  "client_file_id": "00000000-0000-0000-0000-0000000001%02d",
					  "camera_number": %d,
					  "file_name": "camera-%d.mp4",
					  "declared_size_bytes": %d,
					  "sha256": "%s"
					}
					""".formatted(cameraNumber, cameraNumber, cameraNumber, cameraNumber * 100L,
					shaForCamera(cameraNumber)))
			.collect(java.util.stream.Collectors.joining(","));
		return """
				{
				  "schema_version": "external-video-ingest-v2",
				  "source_system": "synthetic-edge",
				  "source_device_id": "cabinet-001",
				  "capture_group": {
				    "capture_group_key": "%s",
				    "key_source": "UPSTREAM_MANIFEST_KEY",
				    "captured_at": "2026-08-11T02:00:00Z",
				    "timezone": "Asia/Shanghai",
				    "expected_camera_numbers": [%s]
				  },
				  "videos": [%s],
				  "upload_set_finalized": %s
				}
				""".formatted(key, expected, videos, uploadSetFinalized);
	}

	private static String shaForCamera(int cameraNumber) {
		return switch (cameraNumber) {
			case 1 -> VIDEO_SHA;
			case 3 -> "c".repeat(64);
			case 5 -> "d".repeat(64);
			default -> "e".repeat(64);
		};
	}

	private static String jsonField(String json, String field) {
		var marker = "\"" + field + "\":\"";
		var start = json.indexOf(marker) + marker.length();
		var end = json.indexOf('"', start);
		return json.substring(start, end);
	}

}
