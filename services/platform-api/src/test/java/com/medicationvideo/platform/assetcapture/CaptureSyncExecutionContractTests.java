package com.medicationvideo.platform.assetcapture;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import java.time.Instant;

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

import com.medicationvideo.platform.support.CaptureSyncSyntheticFixtures;

import com.medicationvideo.platform.support.CaptureSyncTestSupport;

import com.medicationvideo.platform.support.ProjectMembershipTestData;

@SpringBootTest

@AutoConfigureMockMvc

@ActiveProfiles("test")

class CaptureSyncExecutionContractTests {

	private static final String WORKER_TOKEN = "synthetic-media-worker-bearer-token-do-not-reuse";

	private static final String SOFTWARE_DIGEST = "sha256:207391c770bee4a8dfe3530ef5f31af07904950f9bc2dcb822fedb086222164a";

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000204");

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

	private CaptureSyncJobService jobService;

	@Autowired

	private SyncProfileRegistry profileRegistry;

	private UUID jobId;

	private String leaseToken;

	private UUID attemptMessageId;

	private CaptureSyncSyntheticFixtures.CaptureFixture fixture;

	@BeforeEach

	void setUp() throws Exception {

		ProjectMembershipTestData.ensureProject(this.jdbc, PROJECT_ID);

		CaptureSyncTestSupport.clearCaptureSyncTables(this.jdbc);

		this.fixture = CaptureSyncSyntheticFixtures.insertCaptureFixture(this.jdbc, PROJECT_ID, SHA256, RECIPE_DIGEST,

				CREATED_AT);

		var profile = this.profileRegistry.testProfile();

		var input = new CaptureSyncInput(PROJECT_ID, this.fixture.captureSessionId(), this.fixture.snapshotId(),

				"sync-group", SHA256, List.of(new ExpectedCamera(this.fixture.snapshotMemberId(), 1)),

				List.of(new SyncStream(1, this.fixture.cameraStreamId(), this.fixture.clipAssetId(), SHA256,

						this.fixture.mediaReportId(), SHA256,

						new StreamTimeMapping(0, 0, null, "TEST", "SYNTHETIC", 1, true),

						List.of(SourceFrame.valid(0, 0, 0, 0)))));

		var job = this.jobService.create(input, profile, "execution-contract-001", "synthetic-admin");

		this.jobId = job.getCaptureSyncJobId();

		this.attemptMessageId = this.jdbc.queryForObject("""

				SELECT message_id

				FROM capture_sync_job_attempt

				WHERE capture_sync_job_id = ?

				""", UUID.class, this.jobId);

		this.leaseToken = this.jobService.acquire(PROJECT_ID, this.jobId, 1, "worker-1",

				java.time.Duration.ofMinutes(30));

		var object = new CaptureSyncStoragePort.ObjectReference("local-test", this.jobId + ".parquet");

		this.storage.registerForTest(object, "parquet-bytes".getBytes(StandardCharsets.UTF_8));

	}

	@Test

	void workerAcceptsSynchronizationResultWithWorkerToken() throws Exception {

		var digests = this.jdbc.queryForMap("""

				SELECT input_manifest_sha256, sync_profile_sha256

				FROM capture_sync_job

				WHERE capture_sync_job_id = ?

				""", this.jobId);

		var inputManifestSha256 = (String) digests.get("input_manifest_sha256");

		var syncProfileSha256 = (String) digests.get("sync_profile_sha256");

		var parquetSha256 = sha256("parquet-bytes");

		var resultStartedAt = Instant.now().minusSeconds(30);

		var resultCompletedAt = Instant.now().minusSeconds(5);

		var payload = """

				{

				  "schema_version": "capture-sync-result.v1",

				  "message_id": "%s",

				  "job_id": "%s",

				  "attempt": 1,

				  "lease_token": "%s",

				  "status": "SUCCEEDED",

				  "input_manifest_sha256": "%s",

				  "sync_profile_sha256": "%s",

				  "software_digest": "%s",

				  "started_at": "%s",

				  "completed_at": "%s",

				  "success": {

				    "summary": {

				      "reference_clock_source": "TEST_CLOCK",

				      "time_basis_source": "TEST_CAPTURE_TIME",

				      "sync_frame_count": 1,

				      "present_frame_count": 1,

				      "missing_frame_count": 0,

				      "invalid_frame_count": 0,

				      "camera_count_received": 1,

				      "processing_duration_ms": 1,

				      "logical_content_sha256": "%s"

				    },

				    "streams": [{

				      "camera_number": 1,

				      "camera_set_snapshot_member_id": "%s",

				      "stream_presence_state": "PRESENT",

				      "camera_stream_id": "%s",

				      "clip_asset_id": "%s",

				      "source_input_sha256": "%s",

				      "source_media_report_id": "%s",

				      "source_time_base_digest": "%s",

				      "anchor_source_pts_ns": 0,

				      "anchor_capture_timestamp_ns": 0,

				      "present_frame_count": 1,

				      "missing_frame_count": 0,

				      "invalid_frame_count": 0

				    }],

				    "artifact": {

				      "storage_bucket": "local-test",

				      "object_key": "%s.parquet",

				      "size_bytes": 13,

				      "sha256": "%s",

				      "logical_content_sha256": "%s",

				      "artifact_manifest_sha256": "%s",

				      "artifact_manifest": {"schema_version":"synchronized-frame.v1"}

				    }

				  }

				}

				""".formatted(this.attemptMessageId, this.jobId, this.leaseToken, inputManifestSha256,

				syncProfileSha256, SOFTWARE_DIGEST, resultStartedAt, resultCompletedAt, SHA256,

				this.fixture.snapshotMemberId(), this.fixture.cameraStreamId(), this.fixture.clipAssetId(), SHA256,

				this.fixture.mediaReportId(), SHA256, this.jobId, parquetSha256, SHA256, SHA256);

		this.mockMvc

			.perform(put("/api/v1/internal/capture-sync-jobs/{jobId}/attempts/{attempt}/result", this.jobId, 1)

				.header("Authorization", "Bearer " + WORKER_TOKEN)

				.header("X-Capture-Sync-Lease-Token", this.leaseToken)

				.contentType(MediaType.APPLICATION_JSON)

				.content(payload))

			.andExpect(status().isOk())

			.andExpect(jsonPath("$.status").value("SUCCEEDED"))

			.andExpect(jsonPath("$.capture_sync_revision_id").isNotEmpty());

	}

	private static String sha256(String value) {

		try {

			return java.util.HexFormat.of()

				.formatHex(java.security.MessageDigest.getInstance("SHA-256")

					.digest(value.getBytes(StandardCharsets.UTF_8)));

		}

		catch (java.security.NoSuchAlgorithmException exception) {

			throw new IllegalStateException(exception);

		}

	}

}
