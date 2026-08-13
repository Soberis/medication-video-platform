package com.medicationvideo.platform.assetcapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;

import java.nio.file.Files;
import java.security.MessageDigest;

import java.time.Instant;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.AfterEach;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.test.context.ActiveProfiles;

import org.springframework.test.web.servlet.MockMvc;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.PositionOutputStream;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;

import com.medicationvideo.platform.support.CaptureSyncSyntheticFixtures;

import com.medicationvideo.platform.support.CaptureSyncTestSupport;

import com.medicationvideo.platform.support.ProjectMembershipTestData;

@SpringBootTest

@AutoConfigureMockMvc

@ActiveProfiles("test")

class CaptureSyncQueryContractTests {

	private static final String USER_TOKEN = "synthetic-test-bearer-token-do-not-reuse";

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000205");

	private static final UUID OTHER_PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000206");

	private static final String SHA256 = "a".repeat(64);

	private static final String RECIPE_DIGEST = "b".repeat(64);

	private static final Instant CREATED_AT = Instant.parse("2026-08-12T10:00:00Z");

	@Autowired

	private MockMvc mockMvc;

	@Autowired

	private JdbcTemplate jdbc;

	@Autowired

	private LocalCaptureSyncStorage storage;

	@Autowired

	private CaptureSyncQueryService queryService;

	@Autowired

	private CaptureSyncProvenanceQueryService provenanceQueryService;

	@Autowired

	private CaptureSyncInputAssemblyService inputAssemblyService;

	private UUID captureSessionId;

	private UUID revisionId;

	@BeforeEach

	void setUp() throws Exception {

		ProjectMembershipTestData.ensureProject(this.jdbc, PROJECT_ID);

		ProjectMembershipTestData.ensureProject(this.jdbc, OTHER_PROJECT_ID);

		CaptureSyncTestSupport.clearCaptureSyncTables(this.jdbc);

		var fixture = CaptureSyncSyntheticFixtures.insertCaptureFixture(this.jdbc, PROJECT_ID, SHA256, RECIPE_DIGEST,

				CREATED_AT);

		var accepted = insertAcceptedRevision(fixture);

		this.captureSessionId = accepted.captureSessionId();

		this.revisionId = accepted.revisionId();

	}

	@AfterEach

	void clearSyntheticFixtures() {

		CaptureSyncTestSupport.clearSyntheticProjectFixtures(this.jdbc, PROJECT_ID, OTHER_PROJECT_ID);

	}

	@Test

	void timelineAndFramesExposeAcceptedRevision() throws Exception {

		this.mockMvc

			.perform(get("/api/v1/projects/{projectId}/capture-sessions/{captureSessionId}/timeline", PROJECT_ID,

					this.captureSessionId)

				.header("Authorization", "Bearer " + USER_TOKEN))

			.andExpect(status().isOk())

			.andExpect(jsonPath("$.sync_status").value("SYNCHRONIZED"))

			.andExpect(jsonPath("$.current_sync_revision_id").value(this.revisionId.toString()));

		this.mockMvc

			.perform(get("/api/v1/projects/{projectId}/capture-sessions/{captureSessionId}/frames", PROJECT_ID,

					this.captureSessionId)

				.header("Authorization", "Bearer " + USER_TOKEN))

			.andExpect(status().isOk())

			.andExpect(jsonPath("$.items[0].sync_frame_index").value(0))

			.andExpect(jsonPath("$.items[0].camera_number").value(1));

	}

	@Test

	void crossProjectTimelineIsHiddenAsNotFound() throws Exception {

		this.mockMvc

			.perform(get("/api/v1/projects/{projectId}/capture-sessions/{captureSessionId}/timeline", OTHER_PROJECT_ID,

					this.captureSessionId)

				.header("Authorization", "Bearer " + USER_TOKEN))

			.andExpect(status().isNotFound())

			.andExpect(jsonPath("$.error_code").value("CAPTURE_SESSION_NOT_FOUND"));

	}

	@Test

	void parquetFramesUseStableCursorPaginationWithoutAFalseTailCursor() {

		var firstPage = this.queryService.listFrames(PROJECT_ID, this.captureSessionId, this.revisionId, null, null,

				null, 1);

		assertEquals(1, firstPage.items().size());

		assertEquals(0, firstPage.items().getFirst().syncFrameIndex());

		assertNotNull(firstPage.nextCursor());

		var secondPage = this.queryService.listFrames(PROJECT_ID, this.captureSessionId, this.revisionId, null, null,

				firstPage.nextCursor(), 1);

		assertEquals(1, secondPage.items().size());

		assertEquals(1, secondPage.items().getFirst().syncFrameIndex());

		assertNotNull(secondPage.nextCursor());

		var lastPage = this.queryService.listFrames(PROJECT_ID, this.captureSessionId, this.revisionId, null, null,

				secondPage.nextCursor(), 1);

		assertEquals(1, lastPage.items().size());

		assertEquals(2, lastPage.items().getFirst().syncFrameIndex());

		assertNull(lastPage.nextCursor());

	}

	@Test

	void provenanceResolvesAnActualFrameSlotAndRejectsAnUnknownSlot() {

		var provenance = this.provenanceQueryService.resolve(PROJECT_ID, this.captureSessionId, this.revisionId, 1, 1);

		assertEquals(1, provenance.sourceFrameIndex());

		assertEquals(33_333_333, provenance.sourcePtsNs());

		assertEquals(0, provenance.sourcePtsOrdinal());

		assertEquals(0, provenance.syncErrorNs());

		assertEquals(SHA256, provenance.originalMp4Sha256());

		var exception = assertThrows(AssetCatalogQueryException.class,

				() -> this.provenanceQueryService.resolve(PROJECT_ID, this.captureSessionId, this.revisionId, 99, 1));

		assertEquals("CAPTURE_SYNC_FRAME_NOT_FOUND", exception.getErrorCode());

	}

	@Test

	void aggregateMediaReportDoesNotBecomeSyntheticPtsOrAFirstFrameAnchor() {

		var exception = assertThrows(AssetCatalogQueryException.class,

				() -> this.inputAssemblyService.assemble(PROJECT_ID, this.captureSessionId));

		assertEquals("CAPTURE_SYNC_INPUT_NOT_READY", exception.getErrorCode());

	}

	private AcceptedFixture insertAcceptedRevision(CaptureSyncSyntheticFixtures.CaptureFixture fixture)
			throws Exception {

		var jobId = UUID.randomUUID();

		var attemptId = UUID.randomUUID();

		var revisionId = UUID.randomUUID();

		var artifactId = UUID.randomUUID();

		var time = Timestamp.from(CREATED_AT);

		this.jdbc.update("""

				INSERT INTO capture_sync_job (

				    capture_sync_job_id, project_id, capture_session_id, camera_set_snapshot_id,

				    expected_camera_count, input_manifest_sha256, input_manifest,

				    sync_profile_key, sync_profile_version, sync_profile_sha256,

				    algorithm_key, algorithm_version, status, idempotency_key,

				    request_fingerprint, requested_by, created_at, updated_at)

				VALUES (?, ?, ?, ?, 1, ?, '{}'::jsonb, 'TEST/G0', 'v1', ?, 'CAPTURE_SYNC', 'v1',

				        'SUCCEEDED', ?, ?, 'synthetic-admin', ?, ?)

				""", jobId, PROJECT_ID, fixture.captureSessionId(), fixture.snapshotId(), SHA256, SHA256,

				"query-contract-001", SHA256, time, time);

		this.jdbc.update("""

				INSERT INTO capture_sync_job_attempt (

				    capture_sync_job_attempt_id, capture_sync_job_id, attempt, message_id, status,

				    lease_token_hash, created_at, updated_at)

				VALUES (?, ?, 1, ?, 'SUCCEEDED', ?, ?, ?)

				""", attemptId, jobId, UUID.randomUUID(), SHA256, time, time);

		this.jdbc.update("""

				INSERT INTO capture_sync_revision (

				    capture_sync_revision_id, capture_sync_job_id, capture_sync_job_attempt_id, project_id,

				    capture_session_id, camera_set_snapshot_id, reference_clock_source, time_basis_source,

				    timeline_rate_numerator, timeline_rate_denominator, timeline_step_numerator_ns,

				    timeline_step_denominator, allowed_tolerance_ns, algorithm_key, algorithm_version,

				    sync_profile_key, sync_profile_version, sync_profile_sha256, input_manifest_sha256,

				    logical_content_sha256, camera_count_expected, camera_count_received, sync_frame_count,

				    present_frame_count, missing_frame_count, invalid_frame_count, processing_duration_ms,

				    created_at, created_by)

				VALUES (?, ?, ?, ?, ?, ?, 'TEST_CLOCK', 'TEST_CAPTURE_TIME', 30, 1, 1000000000, 30, 20000000,

				        'CAPTURE_SYNC', 'v1', 'TEST/G0', 'v1', ?, ?, ?, 1, 1, 3, 3, 0, 0, 1, ?, 'synthetic-admin')

				""", revisionId, jobId, attemptId, PROJECT_ID, fixture.captureSessionId(), fixture.snapshotId(), SHA256,

				SHA256, SHA256, time);

		this.jdbc.update("""

				INSERT INTO capture_sync_stream_revision (

				    capture_sync_stream_revision_id, capture_sync_revision_id, project_id, capture_session_id,

				    camera_set_snapshot_id, camera_set_snapshot_member_id, camera_number, camera_stream_id,

				    clip_asset_id, stream_presence_state, source_input_sha256, source_media_report_id,

				    source_time_base_digest, anchor_source_pts_ns, anchor_capture_timestamp_ns,

				    present_frame_count, missing_frame_count, invalid_frame_count, created_at)

				VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, 'PRESENT', ?, ?, ?, 0, 0, 3, 0, 0, ?)

				""", UUID.randomUUID(), revisionId, PROJECT_ID, fixture.captureSessionId(), fixture.snapshotId(),

				fixture.snapshotMemberId(), fixture.cameraStreamId(), fixture.clipAssetId(), SHA256,

				fixture.mediaReportId(), SHA256, time);

		var object = new CaptureSyncStoragePort.ObjectReference("bucket", "query/" + revisionId + ".parquet");
		var parquetPath = this.storage.resolve(object);
		Files.deleteIfExists(parquetPath);
		Files.createDirectories(parquetPath.getParent());
		writeParquetFixture(parquetPath, revisionId, fixture);
		var canonicalManifest = "{\"capture_id\":\"" + fixture.captureSessionId()
				+ "\",\"physical_layout_status\":\"TEST/G0\",\"schema_version\":\"synchronized-frame.v1\",\"sync_revision_id\":\""
				+ revisionId + "\"}";
		var manifestDigest = sha256(canonicalManifest);
		var manifest = canonicalManifest.substring(0, canonicalManifest.length() - 1)
				+ ",\"artifact_manifest_sha256\":\"" + manifestDigest + "\"}";
		var artifactBytes = Files.size(parquetPath);
		var artifactDigest = sha256(Files.readAllBytes(parquetPath));

		this.jdbc.update("""

				INSERT INTO capture_sync_artifact (

				    capture_sync_artifact_id, capture_sync_revision_id, artifact_role, schema_version,

				    file_name, media_type, size_bytes, sha256, logical_content_sha256,

				    storage_bucket, object_key, artifact_manifest, artifact_manifest_sha256, created_at)

				VALUES (?, ?, 'SYNCHRONIZED_FRAME_PARQUET', 'synchronized-frame.v1', 'synchronized-frames.parquet',

				        'application/vnd.apache.parquet', ?, ?, ?, 'bucket', ?, ?::jsonb, ?, ?)

				""", artifactId, revisionId, artifactBytes, artifactDigest, SHA256, object.objectKey(), manifest,
				manifestDigest, time);

		this.jdbc.update("UPDATE capture_session SET current_sync_revision_id = ? WHERE capture_session_id = ?",

				revisionId, fixture.captureSessionId());

		return new AcceptedFixture(fixture.captureSessionId(), revisionId);

	}

	private static void writeParquetFixture(java.nio.file.Path path, UUID revisionId,
			CaptureSyncSyntheticFixtures.CaptureFixture fixture) throws Exception {
		var schema = MessageTypeParser.parseMessageType("""
				message synchronized_frame {
					required binary schema_version (UTF8);
					required binary capture_id (UTF8);
					required binary sync_revision_id (UTF8);
					required int64 sync_frame_index;
					required int64 capture_timestamp_ns;
					required int32 camera_number;
					optional binary camera_stream_id (UTF8);
					optional binary video_id (UTF8);
					optional int64 source_frame_index;
					optional int64 source_pts_ns;
					optional int32 source_pts_ordinal;
					required binary frame_state (UTF8);
					optional binary frame_reason_code (UTF8);
					optional int64 sync_error_ns;
				}
				""");
		var factory = new SimpleGroupFactory(schema);
		try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(localOutputFile(path))
			.withType(schema)
			.withRowGroupSize(1)
			.build()) {
			for (long frameIndex = 0; frameIndex < 3; frameIndex++) {
				var row = factory.newGroup();
				row.add("schema_version", "synchronized-frame.v1");
				row.add("capture_id", fixture.captureSessionId().toString());
				row.add("sync_revision_id", revisionId.toString());
				row.add("sync_frame_index", frameIndex);
				row.add("capture_timestamp_ns", frameIndex * 33_333_333L);
				row.add("camera_number", 1);
				row.add("camera_stream_id", fixture.cameraStreamId().toString());
				row.add("video_id", fixture.clipAssetId().toString());
				row.add("source_frame_index", frameIndex);
				row.add("source_pts_ns", frameIndex * 33_333_333L);
				row.add("source_pts_ordinal", 0);
				row.add("frame_state", "PRESENT");
				row.add("sync_error_ns", 0L);
				writer.write(row);
			}
		}
	}

	private static OutputFile localOutputFile(java.nio.file.Path path) {
		return new OutputFile() {

			@Override
			public PositionOutputStream create(long blockSizeHint) throws java.io.IOException {
				if (Files.exists(path)) {
					throw new java.io.IOException("fixture output already exists");
				}
				return createOrOverwrite(blockSizeHint);
			}

			@Override
			public PositionOutputStream createOrOverwrite(long blockSizeHint) throws java.io.IOException {
				return new PositionOutputStream() {

					private final java.io.OutputStream output = Files.newOutputStream(path);

					private long position;

					@Override
					public long getPos() {
						return this.position;
					}

					@Override
					public void write(int value) throws java.io.IOException {
						this.output.write(value);
						this.position++;
					}

					@Override
					public void write(byte[] bytes, int offset, int length) throws java.io.IOException {
						this.output.write(bytes, offset, length);
						this.position += length;
					}

					@Override
					public void close() throws java.io.IOException {
						this.output.close();
					}

				};
			}

			@Override
			public boolean supportsBlockSize() {
				return false;
			}

			@Override
			public long defaultBlockSize() {
				return 0;
			}

			@Override
			public String getPath() {
				return path.toString();
			}

		};
	}

	private static String sha256(String value) throws Exception {
		return java.util.HexFormat.of()
			.formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}

	private static String sha256(byte[] value) throws Exception {
		return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
	}

	private record AcceptedFixture(UUID captureSessionId, UUID revisionId) {

	}

}
