package com.medicationvideo.platform.assetcapture;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;

import java.time.Instant;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.test.context.ActiveProfiles;

import org.springframework.test.web.servlet.MockMvc;

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

	private UUID captureSessionId;

	private UUID revisionId;

	@BeforeEach

	void setUp() {

		ProjectMembershipTestData.ensureProject(this.jdbc, PROJECT_ID);

		ProjectMembershipTestData.ensureProject(this.jdbc, OTHER_PROJECT_ID);

		CaptureSyncTestSupport.clearCaptureSyncTables(this.jdbc);

		var fixture = CaptureSyncSyntheticFixtures.insertCaptureFixture(this.jdbc, PROJECT_ID, SHA256, RECIPE_DIGEST,

				CREATED_AT);

		var accepted = insertAcceptedRevision(fixture);

		this.captureSessionId = accepted.captureSessionId();

		this.revisionId = accepted.revisionId();

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

	private AcceptedFixture insertAcceptedRevision(CaptureSyncSyntheticFixtures.CaptureFixture fixture) {

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

				        'CAPTURE_SYNC', 'v1', 'TEST/G0', 'v1', ?, ?, ?, 1, 1, 1, 1, 0, 0, 1, ?, 'synthetic-admin')

				""", revisionId, jobId, attemptId, PROJECT_ID, fixture.captureSessionId(), fixture.snapshotId(), SHA256,

				SHA256, SHA256, time);

		this.jdbc.update("""

				INSERT INTO capture_sync_stream_revision (

				    capture_sync_stream_revision_id, capture_sync_revision_id, project_id, capture_session_id,

				    camera_set_snapshot_id, camera_set_snapshot_member_id, camera_number, camera_stream_id,

				    clip_asset_id, stream_presence_state, source_input_sha256, source_media_report_id,

				    source_time_base_digest, anchor_source_pts_ns, anchor_capture_timestamp_ns,

				    present_frame_count, missing_frame_count, invalid_frame_count, created_at)

				VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, 'PRESENT', ?, ?, ?, 0, 0, 1, 0, 0, ?)

				""", UUID.randomUUID(), revisionId, PROJECT_ID, fixture.captureSessionId(), fixture.snapshotId(),

				fixture.snapshotMemberId(), fixture.cameraStreamId(), fixture.clipAssetId(), SHA256,

				fixture.mediaReportId(), SHA256, time);

		var manifest = """

				{"schema_version":"synchronized-frame.v1","query_index":{"frames":[{"sync_revision_id":"%s","sync_frame_index":0,"capture_timestamp_ns":0,"camera_number":1,"camera_stream_id":"%s","video_id":"%s","source_frame_index":0,"source_pts_ns":0,"source_pts_ordinal":0,"frame_state":"PRESENT","sync_error_ns":0}]}}

				"""

			.formatted(revisionId, fixture.cameraStreamId(), fixture.clipAssetId());

		this.jdbc.update("""

				INSERT INTO capture_sync_artifact (

				    capture_sync_artifact_id, capture_sync_revision_id, artifact_role, schema_version,

				    file_name, media_type, size_bytes, sha256, logical_content_sha256,

				    storage_bucket, object_key, artifact_manifest, artifact_manifest_sha256, created_at)

				VALUES (?, ?, 'SYNCHRONIZED_FRAME_PARQUET', 'synchronized-frame.v1', 'synchronized-frames.parquet',

				        'application/vnd.apache.parquet', 1, ?, ?, 'bucket', 'key', ?::jsonb, ?, ?)

				""", artifactId, revisionId, SHA256, SHA256, manifest, SHA256, time);

		this.jdbc.update("UPDATE capture_session SET current_sync_revision_id = ? WHERE capture_session_id = ?",

				revisionId, fixture.captureSessionId());

		return new AcceptedFixture(fixture.captureSessionId(), revisionId);

	}

	private record AcceptedFixture(UUID captureSessionId, UUID revisionId) {

	}

}
