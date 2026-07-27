package com.medicationvideo.platform.assetcapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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

import com.medicationvideo.platform.support.ProjectMembershipTestData;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(CaptureSessionIngestContractTests.FakeStorageConfiguration.class)
class CaptureSessionIngestContractTests {

	private static final String TOKEN = "synthetic-test-bearer-token-do-not-reuse";
	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID OTHER_PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final UUID VIDEO_ONE_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
	private static final UUID VIDEO_TWO_ID = UUID.fromString("30000000-0000-0000-0000-000000000002");
	private static final UUID METADATA_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
	private static final long PART_SIZE = 8_388_608L;
	private static final String VIDEO_SHA_ONE = "a".repeat(64);
	private static final String VIDEO_SHA_TWO = "b".repeat(64);

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
	private CameraChannelRepository channelRepository;

	@Autowired
	private CameraSetConfigurationRepository cameraSetRepository;

	@Autowired
	private CameraSetMemberRepository cameraSetMemberRepository;

	@Autowired
	private CaptureSessionRepository sessionRepository;

	@Autowired
	private CameraSetSnapshotRepository snapshotRepository;

	@Autowired
	private CameraSetSnapshotMemberRepository snapshotMemberRepository;

	@Autowired
	private CameraStreamRepository streamRepository;

	@Autowired
	private UploadBatchCompletionService batchCompletionService;

	@Autowired
	private FakeObjectStorage objectStorage;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clearState() {
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, PROJECT_ID);
		deleteAllState();
		this.objectStorage.reset();
	}

	@AfterEach
	void cleanState() {
		deleteAllState();
	}

	@Test
	void atomicallyFreezesServerConfigurationAndBindsEveryVideoIdempotently() throws Exception {
		var configuration = cameraSet(PROJECT_ID, true, true);
		var captureSessionId = UUID.randomUUID();
		var metadata = metadata(
				captureSessionId,
				configuration.cameraSetId(),
				2,
				List.of(
						new StreamSpec(VIDEO_ONE_ID, configuration.firstChannelId(), 0, true, 0, "PASS"),
						new StreamSpec(VIDEO_TWO_ID, configuration.secondChannelId(), 1, true, 125, "WARNING")));
		var batch = completedBatch(PROJECT_ID, metadata, 2);
		this.objectStorage.bytes = metadata;

		var first = ingest(batch.getBatchId())
				.andExpect(status().isCreated())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.capture_session_id").value(captureSessionId.toString()))
				.andExpect(jsonPath("$.batch_id").value(batch.getBatchId().toString()))
				.andExpect(jsonPath("$.device_id").value("synthetic-edge-01"))
				.andExpect(jsonPath("$.metadata_schema_version").value("capture-session-metadata-v1"))
				.andExpect(jsonPath("$.expected_camera_count").value(2))
				.andExpect(jsonPath("$.received_camera_count").value(0))
				.andExpect(jsonPath("$.assembly_status").value("ASSEMBLING"))
				.andExpect(jsonPath("$.quality_status").value("PENDING"))
				.andExpect(jsonPath("$.camera_set_snapshot.source_camera_set_id")
						.value(configuration.cameraSetId().toString()))
				.andExpect(jsonPath("$.camera_set_snapshot.layout_version").value("layout-2026-01"))
				.andExpect(jsonPath("$.camera_set_snapshot.members.length()").value(2))
				.andExpect(jsonPath("$.streams.length()").value(2))
				.andExpect(jsonPath("$.streams[0].camera_role").value("OVERVIEW"))
				.andExpect(jsonPath("$.streams[0].stream_status").value("BOUND"))
				.andExpect(jsonPath("$.streams[1].camera_role").value("HANDS"))
				.andExpect(jsonPath("$.streams[1].time_offset_ms").value(125))
				.andExpect(jsonPath("$.streams[1].sync_status").value("WARNING"))
				.andReturn();

		this.objectStorage.failRead = true;
		var replay = ingest(batch.getBatchId())
				.andExpect(status().isCreated())
				.andReturn();

		assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
		assertEquals(1, this.objectStorage.readCalls);
		assertEquals(1, this.sessionRepository.count());
		assertEquals(1, this.snapshotRepository.count());
		assertEquals(2, this.snapshotMemberRepository.count());
		assertEquals(2, this.streamRepository.count());
	}

	@Test
	void exposesAReadOnlyDatasetAdmissionPreviewWithCurrentBlockingReasons() throws Exception {
		var configuration = cameraSet(PROJECT_ID, true, false);
		var captureSessionId = UUID.randomUUID();
		var metadata = metadata(
				captureSessionId,
				configuration.cameraSetId(),
				1,
				List.of(new StreamSpec(VIDEO_ONE_ID, configuration.firstChannelId(), 0, true, 0, "PASS")));
		var batch = completedBatch(PROJECT_ID, metadata, 1);
		this.objectStorage.bytes = metadata;
		ingest(batch.getBatchId()).andExpect(status().isCreated());

		this.mockMvc.perform(get("/api/v1/projects/{projectId}/capture-sessions/{captureSessionId}/dataset-admission-preview",
						PROJECT_ID, captureSessionId)
				.header("Authorization", "Bearer " + TOKEN))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.capture_session_id").value(captureSessionId.toString()))
				.andExpect(jsonPath("$.eligible").value(false))
				.andExpect(jsonPath("$.blocking_reasons[2]").value("SESSION_QUALITY_NOT_PASS"))
				.andExpect(jsonPath("$.streams[0].clip_asset_id").isNotEmpty())
				.andExpect(jsonPath("$.streams[0].post_cleaning_decision").value("MISSING"))
				.andExpect(jsonPath("$.streams[0].blocking_reasons").isArray());
	}

	@Test
	void rejectsIngestBeforeBatchCompletionWithoutReadingStorage() throws Exception {
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var batch = UploadBatch.initiate(
				PROJECT_ID,
				"capture-incomplete-" + UUID.randomUUID(),
				VIDEO_SHA_ONE,
				"synthetic-edge-01",
				UploadSourceType.HOSPITAL_REMOTE,
				null,
				"synthetic-test-actor",
				1,
				12,
				VIDEO_SHA_ONE,
				now.plusSeconds(3600),
				now);
		this.batchRepository.saveAndFlush(batch);

		ingest(batch.getBatchId())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("CAPTURE_SESSION_BATCH_NOT_COMPLETED"));

		assertEquals(0, this.objectStorage.readCalls);
		assertEquals(0, this.sessionRepository.count());
	}

	@Test
	void rejectsUnknownOrMalformedMetadataFields() throws Exception {
		var configuration = cameraSet(PROJECT_ID, true, false);
		var valid = metadata(
				UUID.randomUUID(),
				configuration.cameraSetId(),
				1,
				List.of(new StreamSpec(VIDEO_ONE_ID, configuration.firstChannelId(), 0, true, 0, "PASS")));
		var withUnknownField = new String(valid, StandardCharsets.UTF_8)
				.replaceFirst("\\{", "{\"unexpected\":\"forbidden\",")
				.getBytes(StandardCharsets.UTF_8);
		var batch = completedBatch(PROJECT_ID, withUnknownField, 1);
		this.objectStorage.bytes = withUnknownField;

		ingest(batch.getBatchId())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("CAPTURE_SESSION_METADATA_INVALID"));

		assertEquals(0, this.sessionRepository.count());
	}

	@Test
	void rejectsMissingRequiredStreamMetadata() throws Exception {
		var configuration = cameraSet(PROJECT_ID, true, false);
		var valid = new String(metadata(
				UUID.randomUUID(),
				configuration.cameraSetId(),
				1,
				List.of(new StreamSpec(VIDEO_ONE_ID, configuration.firstChannelId(), 0, true, 0, "PASS"))),
				StandardCharsets.UTF_8);
		var missingCameraIndex = valid.replace("  \"camera_index\": 0,\n", "");
		var metadata = missingCameraIndex.getBytes(StandardCharsets.UTF_8);
		var batch = completedBatch(PROJECT_ID, metadata, 1);
		this.objectStorage.bytes = metadata;

		ingest(batch.getBatchId())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("CAPTURE_SESSION_METADATA_INVALID"));

		assertEquals(0, this.sessionRepository.count());
	}

	@Test
	void rejectsEdgeCameraConfigurationOverrides() throws Exception {
		var configuration = cameraSet(PROJECT_ID, true, true);
		var metadata = metadata(
				UUID.randomUUID(),
				configuration.cameraSetId(),
				1,
				List.of(
						new StreamSpec(VIDEO_ONE_ID, configuration.firstChannelId(), 0, true, 0, "PASS"),
						new StreamSpec(VIDEO_TWO_ID, configuration.secondChannelId(), 1, true, 0, "PASS")));
		var batch = completedBatch(PROJECT_ID, metadata, 2);
		this.objectStorage.bytes = metadata;

		ingest(batch.getBatchId())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("CAPTURE_SESSION_CAMERA_SET_MISMATCH"));

		assertEquals(0, this.sessionRepository.count());
	}

	@Test
	void requiresEveryVideoAssetToBeBoundExactlyOnce() throws Exception {
		var configuration = cameraSet(PROJECT_ID, true, true);
		var metadata = metadata(
				UUID.randomUUID(),
				configuration.cameraSetId(),
				2,
				List.of(new StreamSpec(VIDEO_ONE_ID, configuration.firstChannelId(), 0, true, 0, "PASS")));
		var batch = completedBatch(PROJECT_ID, metadata, 2);
		this.objectStorage.bytes = metadata;

		ingest(batch.getBatchId())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("CAPTURE_SESSION_VIDEO_BINDING_MISMATCH"));

		assertEquals(0, this.streamRepository.count());
		assertEquals(0, this.snapshotRepository.count());
		assertEquals(0, this.sessionRepository.count());
	}

	@Test
	void hidesBatchesAndCameraSetsFromOtherProjects() throws Exception {
		var otherConfiguration = cameraSet(OTHER_PROJECT_ID, true, false);
		var metadata = metadata(
				UUID.randomUUID(),
				otherConfiguration.cameraSetId(),
				1,
				List.of(new StreamSpec(VIDEO_ONE_ID, otherConfiguration.firstChannelId(), 0, true, 0, "PASS")));
		var batch = completedBatch(PROJECT_ID, metadata, 1);
		this.objectStorage.bytes = metadata;

		ingest(batch.getBatchId())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("CAPTURE_SESSION_CAMERA_SET_NOT_FOUND"));

		var otherBatch = completedBatch(OTHER_PROJECT_ID, metadata, 1);
		ingest(otherBatch.getBatchId())
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error_code").value("UPLOAD_BATCH_NOT_FOUND"));
	}

	@Test
	void appliesAtomicCaptureAssemblyMigrationAndConstraints() {
		var migrationCount = this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version = '8' AND success = true",
				Integer.class);
		var constraintCount = this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM information_schema.table_constraints "
						+ "WHERE constraint_name IN "
						+ "('uk_capture_session_batch', 'uk_camera_set_snapshot_session', "
						+ "'uk_camera_set_snapshot_member_channel', 'uk_camera_set_snapshot_member_index', "
						+ "'uk_camera_stream_session_channel', 'uk_camera_stream_session_index', "
						+ "'uk_camera_stream_clip_asset')",
				Integer.class);

		assertEquals(1, migrationCount);
		assertEquals(7, constraintCount);
	}

	private CameraConfiguration cameraSet(UUID projectId, boolean firstEnabled, boolean secondEnabled) {
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var first = this.channelRepository.saveAndFlush(
				CameraChannel.provision(projectId, "channel-" + UUID.randomUUID(), now));
		CameraChannel second = null;
		if (secondEnabled) {
			second = this.channelRepository.saveAndFlush(
					CameraChannel.provision(projectId, "channel-" + UUID.randomUUID(), now));
		}
		var cameraSet = this.cameraSetRepository.saveAndFlush(CameraSetConfiguration.create(
				projectId,
				"set-" + UUID.randomUUID(),
				1,
				"layout-2026-01",
				"c".repeat(64),
				now));
		this.cameraSetMemberRepository.saveAndFlush(CameraSetMember.create(
				cameraSet.getCameraSetId(),
				new CameraSetMemberRequest(
						first.getCameraChannelId(),
						0,
						"OVERVIEW",
						"front-center",
						firstEnabled),
				now));
		if (second != null) {
			this.cameraSetMemberRepository.saveAndFlush(CameraSetMember.create(
					cameraSet.getCameraSetId(),
					new CameraSetMemberRequest(
							second.getCameraChannelId(),
							1,
							"HANDS",
							"top-down",
							secondEnabled),
					now));
		}
		return new CameraConfiguration(
				cameraSet.getCameraSetId(),
				first.getCameraChannelId(),
				second == null ? null : second.getCameraChannelId());
	}

	private UploadBatch completedBatch(UUID projectId, byte[] metadata, int videoCount) {
		var entries = new java.util.ArrayList<ManifestEntry>();
		entries.add(new ManifestEntry(VIDEO_ONE_ID, UploadFileKind.VIDEO, "video/mp4", 12, VIDEO_SHA_ONE));
		if (videoCount == 2) {
			entries.add(new ManifestEntry(VIDEO_TWO_ID, UploadFileKind.VIDEO, "video/mp4", 13, VIDEO_SHA_TWO));
		}
		entries.add(new ManifestEntry(
				METADATA_ID,
				UploadFileKind.METADATA,
				"application/json",
				metadata.length,
				sha256(metadata)));
		var manifestEntries = List.copyOf(entries);
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var batch = UploadBatch.initiate(
				projectId,
				"capture-complete-" + UUID.randomUUID(),
				VIDEO_SHA_ONE,
				"synthetic-edge-01",
				UploadSourceType.HOSPITAL_REMOTE,
				null,
				"synthetic-test-actor",
				manifestEntries.size(),
				manifestEntries.stream().mapToLong(ManifestEntry::sizeBytes).sum(),
				manifestSha256(manifestEntries),
				now.plusSeconds(3600),
				now);
		batch.markUploading(now);
		this.batchRepository.saveAndFlush(batch);

		for (var index = 0; index < manifestEntries.size(); index++) {
			var entry = manifestEntries.get(index);
			var file = UploadFile.register(
					batch.getBatchId(),
					entry.clientFileId(),
					"capture-file-" + entry.clientFileId(),
					VIDEO_SHA_ONE,
					new UploadFileRegistrationRequest(
							entry.kind(),
							entry.contentType(),
							entry.sizeBytes(),
							entry.sha256(),
							PART_SIZE),
					1,
					now.plus(index, ChronoUnit.MICROS));
			file.attachMultipartSession(
					new MultipartObjectStorage.CreatedUpload(
							"synthetic-bucket",
							"capture/" + file.getUploadFileId(),
							"upload-" + file.getUploadFileId()),
					now);
			file.markUploading(now);
			var attempt = UUID.randomUUID();
			file.startCompletion(attempt, now.plusSeconds(60), now);
			file.markUploaded(
					attempt,
					new MultipartObjectStorage.CompletedObject(entry.sizeBytes(), "etag-" + file.getUploadFileId()),
					entry.sha256(),
					now.plusSeconds(1));
			this.fileRepository.saveAndFlush(file);
		}
		this.batchCompletionService.complete(projectId, batch.getBatchId());
		return this.batchRepository.findById(batch.getBatchId()).orElseThrow();
	}

	private static byte[] metadata(
			UUID captureSessionId,
			UUID cameraSetId,
			int expectedCameraCount,
			List<StreamSpec> streams) {
		var streamJson = streams.stream()
				.map(stream -> """
						{
						  "client_file_id": "%s",
						  "camera_channel_id": "%s",
						  "camera_index": %d,
						  "enabled": %s,
						  "record_start_utc": "2026-07-15T01:02:03Z",
						  "time_offset_ms": %d,
						  "sync_status": "%s"
						}
						""".formatted(
						stream.clientFileId(),
						stream.cameraChannelId(),
						stream.cameraIndex(),
						stream.enabled(),
						stream.timeOffsetMs(),
						stream.syncStatus()))
				.collect(Collectors.joining(","));
		return """
				{
				  "schema_version": "capture-session-metadata-v1",
				  "capture_session_id": "%s",
				  "device_id": "synthetic-edge-01",
				  "camera_set_id": "%s",
				  "expected_camera_count": %d,
				  "camera_layout_version": "layout-2026-01",
				  "sync_group_id": "sync-group-01",
				  "device_time_utc": "2026-07-15T01:02:04Z",
				  "device_timezone": "Asia/Shanghai",
				  "streams": [%s]
				}
				""".formatted(captureSessionId, cameraSetId, expectedCameraCount, streamJson)
				.getBytes(StandardCharsets.UTF_8);
	}

	private org.springframework.test.web.servlet.ResultActions ingest(UUID batchId) throws Exception {
		return this.mockMvc.perform(put("/api/v1/upload/{batchId}/capture-session", batchId)
				.header("Authorization", "Bearer " + TOKEN));
	}

	private void deleteAllState() {
		this.streamRepository.deleteAll();
		this.snapshotMemberRepository.deleteAll();
		this.snapshotRepository.deleteAll();
		this.sessionRepository.deleteAll();
		this.clipAssetRepository.deleteAll();
		this.partRepository.deleteAll();
		this.fileRepository.deleteAll();
		this.batchRepository.deleteAll();
		this.cameraSetMemberRepository.deleteAll();
		this.cameraSetRepository.deleteAll();
		this.channelRepository.deleteAll();
	}

	private static String manifestSha256(List<ManifestEntry> entries) {
		var canonical = new StringBuilder(UploadBatchManifest.FORMAT).append('\n');
		entries.stream()
				.sorted(Comparator.comparing(entry -> entry.clientFileId().toString()))
				.forEach(entry -> canonical
						.append(entry.clientFileId()).append('\t')
						.append(entry.kind()).append('\t')
						.append(entry.contentType()).append('\t')
						.append(entry.sizeBytes()).append('\t')
						.append(entry.sha256()).append('\t')
						.append(PART_SIZE).append('\n'));
		return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeStorageConfiguration {

		@Bean
		@Primary
		FakeObjectStorage fakeObjectStorage() {
			return new FakeObjectStorage();
		}
	}

	static class FakeObjectStorage implements MultipartObjectStorage {

		private byte[] bytes = new byte[0];
		private int readCalls;
		private boolean failRead;

		@Override
		public CreatedUpload create(String objectKey, String contentType) {
			throw new UnsupportedOperationException();
		}

		@Override
		public URI authorizePart(CreatedUpload upload, int partNumber, Duration lifetime) {
			throw new UnsupportedOperationException();
		}

		@Override
		public URI authorizeRead(ObjectReference object, Duration lifetime) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Optional<StoredPart> findPart(CreatedUpload upload, int partNumber) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<StoredPart> listParts(CreatedUpload upload) {
			throw new UnsupportedOperationException();
		}

		@Override
		public CompletedObject complete(CreatedUpload upload, List<StoredPart> parts) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Optional<CompletedObject> findCompletedObject(CreatedUpload upload) {
			throw new UnsupportedOperationException();
		}

		@Override
		public String calculateSha256(CreatedUpload upload) {
			throw new UnsupportedOperationException();
		}

		@Override
		public byte[] readCompletedObject(ObjectReference object, int maxBytes) {
			this.readCalls++;
			if (this.failRead) {
				throw new MultipartObjectStorageException("Synthetic read failure.", null);
			}
			return this.bytes.clone();
		}

		@Override
		public void abort(CreatedUpload upload) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void delete(ObjectReference object) {
			throw new UnsupportedOperationException();
		}

		void reset() {
			this.bytes = new byte[0];
			this.readCalls = 0;
			this.failRead = false;
		}
	}

	private record CameraConfiguration(UUID cameraSetId, UUID firstChannelId, UUID secondChannelId) {
	}

	private record StreamSpec(
			UUID clientFileId,
			UUID cameraChannelId,
			int cameraIndex,
			boolean enabled,
			long timeOffsetMs,
			String syncStatus) {
	}

	private record ManifestEntry(
			UUID clientFileId,
			UploadFileKind kind,
			String contentType,
			long sizeBytes,
			String sha256) {
	}
}
