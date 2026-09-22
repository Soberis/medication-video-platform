package com.medicationvideo.platform.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Minimal persisted CaptureSession graph shared by media-projection contract tests.
 */
public final class CaptureMediaProjectionTestData {

	private static final String REQUEST_FINGERPRINT = "a".repeat(64);

	private CaptureMediaProjectionTestData() {
	}

	public static Projection bindPending(JdbcTemplate jdbcTemplate, UUID projectId, UUID clipAssetId,
			Instant createdAt) {
		return bind(jdbcTemplate, projectId, clipAssetId, createdAt, 0, "ASSEMBLING", "PENDING", "BOUND", "PENDING");
	}

	private static Projection bind(JdbcTemplate jdbcTemplate, UUID projectId, UUID clipAssetId, Instant createdAt,
			int receivedCameraCount, String assemblyStatus, String sessionQualityStatus, String streamStatus,
			String streamQualityStatus) {
		var asset = jdbcTemplate.queryForObject("""
				SELECT batch_id, client_file_id
				FROM clip_asset
				WHERE project_id = ? AND clip_asset_id = ?
				""", (resultSet, rowNumber) -> new AssetBinding(resultSet.getObject("batch_id", UUID.class),
				resultSet.getObject("client_file_id", UUID.class)), projectId, clipAssetId);
		var captureSessionId = UUID.randomUUID();
		var cameraStreamId = UUID.randomUUID();
		var cameraChannelId = UUID.randomUUID();
		var cameraSetId = UUID.randomUUID();
		var snapshotId = UUID.randomUUID();
		var snapshotMemberId = UUID.randomUUID();
		jdbcTemplate.update("UPDATE upload_batch SET upload_source_type = 'PLATFORM_LOCAL' WHERE batch_id = ?",
				asset.batchId());
		jdbcTemplate.update("""
				INSERT INTO camera_channel (
				    camera_channel_id, project_id, provisioning_key, created_at)
				VALUES (?, ?, ?, ?)
				""", cameraChannelId, projectId, "projection-channel-" + cameraChannelId, Timestamp.from(createdAt));
		jdbcTemplate.update("""
				INSERT INTO camera_set (
				    camera_set_id, project_id, camera_set_key, configuration_version,
				    layout_version, request_fingerprint, created_at)
				VALUES (?, ?, ?, 1, 'projection-v1', ?, ?)
				""", cameraSetId, projectId, "projection-set-" + cameraSetId, REQUEST_FINGERPRINT,
				Timestamp.from(createdAt));
		jdbcTemplate.update("""
				INSERT INTO capture_session (
				    capture_session_id, project_id, batch_id, device_id, camera_set_id,
				    metadata_upload_file_id, metadata_schema_version, metadata_sha256,
				    expected_camera_count, received_camera_count, sync_group_id,
				    device_time_utc, device_timezone, server_receive_time,
				    assembly_status, quality_status, created_at, updated_at)
				VALUES (?, ?, ?, 'synthetic-platform-local', ?, NULL,
				        'platform-local-upload-v1', NULL, 1, ?, NULL, NULL, NULL, ?,
				        ?, ?, ?, ?)
				""", captureSessionId, projectId, asset.batchId(), cameraSetId, receivedCameraCount,
				Timestamp.from(createdAt), assemblyStatus, sessionQualityStatus, Timestamp.from(createdAt),
				Timestamp.from(createdAt));
		jdbcTemplate.update("""
				INSERT INTO camera_set_snapshot (
				    camera_set_snapshot_id, capture_session_id, source_camera_set_id,
				    camera_set_key, configuration_version, layout_version,
				    expected_camera_count, created_at)
				VALUES (?, ?, ?, ?, 1, 'projection-v1', 1, ?)
				""", snapshotId, captureSessionId, cameraSetId, "projection-set-" + cameraSetId,
				Timestamp.from(createdAt));
		jdbcTemplate.update("""
				INSERT INTO camera_set_snapshot_member (
				    camera_set_snapshot_member_id, camera_set_snapshot_id, camera_channel_id,
				    camera_index, camera_role, mount_position, enabled, created_at)
				VALUES (?, ?, ?, 0, 'OVERVIEW', 'front-center', true, ?)
				""", snapshotMemberId, snapshotId, cameraChannelId, Timestamp.from(createdAt));
		jdbcTemplate.update("""
				INSERT INTO camera_stream (
				    camera_stream_id, capture_session_id, camera_set_snapshot_member_id,
				    camera_channel_id, camera_index, clip_asset_id, client_file_id,
				    record_start_utc, time_offset_ms, declared_sync_status,
				    stream_status, quality_status, created_at, updated_at)
				VALUES (?, ?, ?, ?, 0, ?, ?, NULL, 0, 'PASS', ?, ?, ?, ?)
				""", cameraStreamId, captureSessionId, snapshotMemberId, cameraChannelId, clipAssetId,
				asset.clientFileId(), streamStatus, streamQualityStatus, Timestamp.from(createdAt),
				Timestamp.from(createdAt));
		return new Projection(captureSessionId, cameraStreamId);
	}

	private record AssetBinding(UUID batchId, UUID clientFileId) {
	}

	public record Projection(UUID captureSessionId, UUID cameraStreamId) {
	}

}
