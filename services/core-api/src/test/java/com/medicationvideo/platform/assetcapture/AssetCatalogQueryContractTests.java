package com.medicationvideo.platform.assetcapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static com.medicationvideo.platform.support.ProjectMembershipTestData.MEMBER_ACTOR;
import static com.medicationvideo.platform.support.ProjectMembershipTestData.ensureProject;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssetCatalogQueryContractTests {

	private static final String TOKEN = "synthetic-test-bearer-token-do-not-reuse";

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final UUID OTHER_PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

	private static final String SHA256 = "a".repeat(64);

	private static final long PART_SIZE = 8_388_608L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private AssetCatalogQueryService assetCatalog;

	@Autowired
	private com.medicationvideo.platform.aicandidate.ActionCandidateJobService actionJobs;

	@BeforeEach
	void clearState() {
		deleteAllState();
		ensureProject(this.jdbcTemplate, PROJECT_ID);
		ensureProject(this.jdbcTemplate, OTHER_PROJECT_ID);
		this.jdbcTemplate.update(
				"UPDATE membership SET status = 'REMOVED', removed_by = ?, removed_at = now() "
						+ "WHERE project_id = ? AND actor_id = ?",
				"local-synthetic-admin", OTHER_PROJECT_ID, MEMBER_ACTOR);
	}

	@AfterEach
	void cleanState() {
		deleteAllState();
	}

	@Test
	void readsClipAssetsWithoutExposingStorageOrPreviewCredentials() throws Exception {
		var unbound = insertUnboundAsset(PROJECT_ID, Instant.parse("2026-07-15T01:00:00Z"));
		var grouped = insertSessionGraph(PROJECT_ID, "platform-local", "PLATFORM_LOCAL",
				Instant.parse("2026-07-15T02:00:00Z"));
		var hidden = insertSessionGraph(OTHER_PROJECT_ID, "device-hidden", "HANDS",
				Instant.parse("2026-07-15T03:00:00Z"));
		this.jdbcTemplate.update("""
				UPDATE capture_session
				SET capture_group_workflow = TRUE,
				    receipt_state = 'COMPLETE',
				    receipt_finalized_at = updated_at
				WHERE capture_session_id = ?
				""", grouped.captureSessionId());

		authorizedProjectGet("/clip-assets/" + unbound).andExpect(status().isOk())
			.andExpect(jsonPath("$.clip_asset_id").value(unbound.toString()))
			.andExpect(jsonPath("$.storage_bucket").doesNotExist())
			.andExpect(jsonPath("$.object_key").doesNotExist())
			.andExpect(jsonPath("$.storage_etag").doesNotExist())
			.andExpect(jsonPath("$.preview_url").doesNotExist())
			.andExpect(jsonPath("$.session_binding").doesNotExist());

		authorizedProjectGet("/clip-assets/" + hidden.clipAssetId()).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error_code").value("CLIP_ASSET_NOT_FOUND"));

		authorizedProjectGet("/clip-assets/" + grouped.clipAssetId()).andExpect(status().isOk())
			.andExpect(jsonPath("$.session_binding.capture_session_id").value(grouped.captureSessionId().toString()))
			.andExpect(jsonPath("$.session_binding.capture_group_id").value(grouped.captureSessionId().toString()))
			.andExpect(jsonPath("$.session_binding.capture_group_workflow").value(true))
			.andExpect(jsonPath("$.session_binding.receipt_state").value("COMPLETE"));
	}

	@Test
	void appliesCatalogQueryIndexes() {
		var migrationCount = this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version = '9' AND success = true", Integer.class);
		var indexCount = this.jdbcTemplate.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname IN "
				+ "('ix_capture_session_project_recorded', 'ix_camera_stream_recorded_asset')", Integer.class);

		assertEquals(1, migrationCount);
		assertEquals(2, indexCount);
	}

	@Test
	void mediaReadyVideosQueueAcrossProjectsWithoutHumanTasksAndAreReadable() throws Exception {
		var first = insertSessionGraph(PROJECT_ID, "synthetic-auto-a", "HANDS", Instant.now());
		var second = insertSessionGraph(OTHER_PROJECT_ID, "synthetic-auto-b", "HANDS", Instant.now());
		insertSessionGraph(PROJECT_ID, "synthetic-pending", "HANDS", Instant.now());
		this.jdbcTemplate.update("UPDATE clip_asset SET asset_status = 'INGESTED' WHERE clip_asset_id IN (?, ?)",
				first.clipAssetId(), second.clipAssetId());
		var inputs = this.assetCatalog.findActionProcessingInputs(0, 100);
		assertEquals(2, inputs.size());
		for (var input : inputs) {
			this.actionJobs.queueAutomatically(input.projectId(), input.clipAssetId());
			this.actionJobs.queueAutomatically(input.projectId(), input.clipAssetId());
		}
		assertEquals(2, this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM ai_job WHERE algorithm_key = 'QWEN3_5_ACTION' AND annotation_task_id IS NULL AND label_schema_id IS NOT NULL",
				Integer.class));
		assertEquals(2, this.jdbcTemplate.queryForObject("SELECT count(*) FROM ai_job_attempt", Integer.class));
		assertEquals(2, this.jdbcTemplate
			.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_type = 'AiJob'", Integer.class));
		assertEquals(0, this.jdbcTemplate.queryForObject("SELECT count(*) FROM annotation_task", Integer.class));
		authorizedProjectGet("/capture-groups/" + first.captureSessionId() + "/action-candidate-job")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("QUEUED"))
			.andExpect(jsonPath("$.clip_asset_id").value(first.clipAssetId().toString()));
	}

	private SessionGraph insertSessionGraph(UUID projectId, String deviceId, String cameraRole, Instant deviceTime) {
		var graph = new SessionGraph(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), UUID.randomUUID());
		var createdAt = deviceTime.plus(1, ChronoUnit.MINUTES);
		insertCompletedBatch(graph.batchId(), projectId, deviceId, createdAt);
		insertUploadedFile(graph.videoUploadFileId(), graph.batchId(), graph.videoClientFileId(), UploadFileKind.VIDEO,
				"video/mp4", 100, createdAt);
		insertUploadedFile(graph.metadataUploadFileId(), graph.batchId(), UUID.randomUUID(), UploadFileKind.METADATA,
				"application/json", 1, createdAt);
		this.jdbcTemplate.update("""
				INSERT INTO clip_asset (
				    clip_asset_id, project_id, batch_id, source_upload_file_id, client_file_id,
				    content_type, size_bytes, sha256, storage_bucket, object_key, storage_etag,
				    asset_status, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'video/mp4', 100, ?, 'synthetic-bucket', ?, 'synthetic-etag',
				        'UPLOADED', ?, ?)
				""", graph.clipAssetId(), projectId, graph.batchId(), graph.videoUploadFileId(),
				graph.videoClientFileId(), SHA256, "objects/" + graph.clipAssetId(), Timestamp.from(createdAt),
				Timestamp.from(createdAt));
		this.jdbcTemplate.update(
				"INSERT INTO camera_channel (camera_channel_id, project_id, provisioning_key, created_at) "
						+ "VALUES (?, ?, ?, ?)",
				graph.cameraChannelId(), projectId, "channel-" + graph.cameraChannelId(), Timestamp.from(createdAt));
		this.jdbcTemplate.update("""
				INSERT INTO camera_set (
				    camera_set_id, project_id, camera_set_key, configuration_version,
				    layout_version, request_fingerprint, created_at)
				VALUES (?, ?, ?, 1, 'layout-v1', ?, ?)
				""", graph.cameraSetId(), projectId, "set-" + graph.cameraSetId(), SHA256, Timestamp.from(createdAt));
		this.jdbcTemplate.update("""
				INSERT INTO camera_set_member (
				    camera_set_member_id, camera_set_id, camera_channel_id, camera_index,
				    camera_role, mount_position, enabled, created_at)
				VALUES (?, ?, ?, 0, ?, 'front-center', true, ?)
				""", UUID.randomUUID(), graph.cameraSetId(), graph.cameraChannelId(), cameraRole,
				Timestamp.from(createdAt));
		this.jdbcTemplate.update("""
				INSERT INTO capture_session (
				    capture_session_id, project_id, batch_id, device_id, camera_set_id,
				    metadata_upload_file_id, metadata_schema_version, metadata_sha256,
				    expected_camera_count, received_camera_count, sync_group_id,
				    device_time_utc, device_timezone, server_receive_time,
				    assembly_status, quality_status, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, 'capture-session-metadata-v1', ?, 1, 0, ?,
				        ?, 'Asia/Shanghai', ?, 'ASSEMBLING', 'PENDING', ?, ?)
				""", graph.captureSessionId(), projectId, graph.batchId(), deviceId, graph.cameraSetId(),
				graph.metadataUploadFileId(), SHA256, "sync-" + graph.captureSessionId(), Timestamp.from(deviceTime),
				Timestamp.from(createdAt), Timestamp.from(createdAt), Timestamp.from(createdAt));
		this.jdbcTemplate.update("""
				INSERT INTO camera_set_snapshot (
				    camera_set_snapshot_id, capture_session_id, source_camera_set_id,
				    camera_set_key, configuration_version, layout_version,
				    expected_camera_count, created_at)
				VALUES (?, ?, ?, ?, 1, 'layout-v1', 1, ?)
				""", graph.snapshotId(), graph.captureSessionId(), graph.cameraSetId(), "set-" + graph.cameraSetId(),
				Timestamp.from(createdAt));
		this.jdbcTemplate.update("""
				INSERT INTO camera_set_snapshot_member (
				    camera_set_snapshot_member_id, camera_set_snapshot_id, camera_channel_id,
				    camera_index, camera_role, mount_position, enabled, created_at)
				VALUES (?, ?, ?, 0, ?, 'front-center', true, ?)
				""", graph.snapshotMemberId(), graph.snapshotId(), graph.cameraChannelId(), cameraRole,
				Timestamp.from(createdAt));
		this.jdbcTemplate.update("""
				INSERT INTO camera_stream (
				    camera_stream_id, capture_session_id, camera_set_snapshot_member_id,
				    camera_channel_id, camera_index, clip_asset_id, client_file_id,
				    record_start_utc, time_offset_ms, declared_sync_status,
				    stream_status, quality_status, created_at, updated_at)
				VALUES (?, ?, ?, ?, 0, ?, ?, ?, 125, 'WARNING', 'BOUND', 'PENDING', ?, ?)
				""", graph.cameraStreamId(), graph.captureSessionId(), graph.snapshotMemberId(),
				graph.cameraChannelId(), graph.clipAssetId(), graph.videoClientFileId(),
				Timestamp.from(deviceTime.minusSeconds(1)), Timestamp.from(createdAt), Timestamp.from(createdAt));
		return graph;
	}

	private UUID insertUnboundAsset(UUID projectId, Instant createdAt) {
		var batchId = UUID.randomUUID();
		var uploadFileId = UUID.randomUUID();
		var clientFileId = UUID.randomUUID();
		var clipAssetId = UUID.randomUUID();
		insertCompletedBatch(batchId, projectId, "device-unbound", createdAt);
		insertUploadedFile(uploadFileId, batchId, clientFileId, UploadFileKind.VIDEO, "video/mp4", 100, createdAt);
		this.jdbcTemplate.update("""
				INSERT INTO clip_asset (
				    clip_asset_id, project_id, batch_id, source_upload_file_id, client_file_id,
				    content_type, size_bytes, sha256, storage_bucket, object_key, storage_etag,
				    asset_status, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'video/mp4', 100, ?, 'synthetic-bucket', ?, 'synthetic-etag',
				        'UPLOADED', ?, ?)
				""", clipAssetId, projectId, batchId, uploadFileId, clientFileId, SHA256, "objects/" + clipAssetId,
				Timestamp.from(createdAt), Timestamp.from(createdAt));
		return clipAssetId;
	}

	private void insertCompletedBatch(UUID batchId, UUID projectId, String deviceId, Instant createdAt) {
		this.jdbcTemplate.update("""
				INSERT INTO upload_batch (
				    batch_id, project_id, idempotency_key, request_fingerprint, device_id,
				    initiated_by_actor_id,
				    file_count, total_size_bytes, manifest_sha256, status, expires_at,
				    created_at, updated_at, verified_manifest_sha256, completed_at)
				VALUES (?, ?, ?, ?, ?, 'synthetic-test-actor', 2, 101, ?, 'COMPLETED', ?, ?, ?, ?, ?)
				""", batchId, projectId, "catalog-batch-" + batchId, SHA256, deviceId, SHA256,
				Timestamp.from(createdAt.plusSeconds(3600)), Timestamp.from(createdAt), Timestamp.from(createdAt),
				SHA256, Timestamp.from(createdAt));
	}

	private void insertUploadedFile(UUID uploadFileId, UUID batchId, UUID clientFileId, UploadFileKind kind,
			String contentType, long sizeBytes, Instant createdAt) {
		this.jdbcTemplate.update("""
				INSERT INTO upload_file (
				    upload_file_id, batch_id, client_file_id, idempotency_key, request_fingerprint,
				    file_kind, content_type, size_bytes, sha256, part_size_bytes, total_parts,
				    status, created_at, updated_at, storage_bucket, object_key, storage_upload_id,
				    multipart_created_at, completion_started_at, completion_finished_at,
				    verified_sha256, completed_storage_etag)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 'UPLOADED', ?, ?, 'synthetic-bucket', ?, ?,
				        ?, ?, ?, ?, 'synthetic-etag')
				""", uploadFileId, batchId, clientFileId, "catalog-file-" + uploadFileId, SHA256, kind.name(),
				contentType, sizeBytes, SHA256, PART_SIZE, Timestamp.from(createdAt), Timestamp.from(createdAt),
				"uploads/" + uploadFileId, "multipart-" + uploadFileId, Timestamp.from(createdAt),
				Timestamp.from(createdAt), Timestamp.from(createdAt), SHA256);
	}

	private ResultActions authorizedGet(String path) throws Exception {
		return this.mockMvc.perform(get(path).header("Authorization", "Bearer " + TOKEN));
	}

	private ResultActions authorizedProjectGet(String path) throws Exception {
		return authorizedGet("/api/v1/projects/" + PROJECT_ID + path);
	}

	private void deleteAllState() {
		this.jdbcTemplate.update("DELETE FROM action_candidate");
		this.jdbcTemplate.update("DELETE FROM candidate_annotation");
		this.jdbcTemplate.update("DELETE FROM ai_job_result_inbox");
		this.jdbcTemplate.update("DELETE FROM ai_job_attempt");
		this.jdbcTemplate.update("DELETE FROM ai_job_idempotency_receipt");
		this.jdbcTemplate.update("DELETE FROM ai_job");
		this.jdbcTemplate.update("DELETE FROM outbox_event WHERE aggregate_type = 'AiJob'");
		this.jdbcTemplate.update("DELETE FROM camera_stream");
		this.jdbcTemplate.update("DELETE FROM camera_set_snapshot_member");
		this.jdbcTemplate.update("DELETE FROM camera_set_snapshot");
		this.jdbcTemplate.update("DELETE FROM capture_session");
		this.jdbcTemplate.update("DELETE FROM clip_asset");
		this.jdbcTemplate.update("DELETE FROM upload_part");
		this.jdbcTemplate.update("DELETE FROM upload_file");
		this.jdbcTemplate.update("DELETE FROM upload_batch");
		this.jdbcTemplate.update("DELETE FROM camera_set_member");
		this.jdbcTemplate.update("DELETE FROM camera_set");
		this.jdbcTemplate.update("DELETE FROM camera_channel");
		this.jdbcTemplate.update("DELETE FROM membership");
		this.jdbcTemplate.update("DELETE FROM project");
	}

	private record SessionGraph(UUID batchId, UUID videoUploadFileId, UUID metadataUploadFileId, UUID videoClientFileId,
			UUID clipAssetId, UUID cameraChannelId, UUID cameraSetId, UUID captureSessionId, UUID snapshotId,
			UUID snapshotMemberId, UUID cameraStreamId) {
	}

}
