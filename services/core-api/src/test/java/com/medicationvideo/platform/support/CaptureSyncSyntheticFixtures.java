package com.medicationvideo.platform.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

public final class CaptureSyncSyntheticFixtures {

	private CaptureSyncSyntheticFixtures() {
	}

	public static CaptureFixture insertCaptureFixture(JdbcTemplate jdbc, UUID projectId, String sha256,
			String recipeDigest, Instant createdAt) {
		var cameraChannelId = UUID.randomUUID();
		var cameraSetId = UUID.randomUUID();
		var cameraSetMemberId = UUID.randomUUID();
		var batchId = UUID.randomUUID();
		var metadataFileId = UUID.randomUUID();
		var videoFileId = UUID.randomUUID();
		var clientMetadataId = UUID.randomUUID();
		var clientVideoId = UUID.randomUUID();
		var clipAssetId = UUID.randomUUID();
		var captureSessionId = UUID.randomUUID();
		var snapshotId = UUID.randomUUID();
		var snapshotMemberId = UUID.randomUUID();
		var cameraStreamId = UUID.randomUUID();
		var mediaJobId = UUID.randomUUID();
		var mediaReportId = UUID.randomUUID();
		var time = Timestamp.from(createdAt);
		jdbc.update(
				"INSERT INTO camera_channel (camera_channel_id, project_id, provisioning_key, created_at) VALUES (?, ?, ?, ?)",
				cameraChannelId, projectId, "sync-channel-" + cameraChannelId, time);
		jdbc.update("""
				INSERT INTO camera_set (camera_set_id, project_id, camera_set_key, configuration_version,
				    layout_version, request_fingerprint, created_at)
				VALUES (?, ?, ?, 1, 'layout-v1', ?, ?)
				""", cameraSetId, projectId, "sync-set-" + cameraSetId, sha256, time);
		jdbc.update("""
				INSERT INTO camera_set_member (camera_set_member_id, camera_set_id, camera_channel_id,
				    camera_index, camera_role, mount_position, enabled, created_at)
				VALUES (?, ?, ?, 0, 'FRONT', 'front', TRUE, ?)
				""", cameraSetMemberId, cameraSetId, cameraChannelId, time);
		jdbc.update("""
				INSERT INTO upload_batch (
				    batch_id, project_id, idempotency_key, request_fingerprint, device_id,
				    initiated_by_actor_id, file_count, total_size_bytes, manifest_sha256, status,
				    expires_at, created_at, updated_at, verified_manifest_sha256, completed_at)
				VALUES (?, ?, ?, ?, 'sync-device', 'sync-actor', 2, 2468, ?, 'COMPLETED', ?, ?, ?, ?, ?)
				""", batchId, projectId, "sync-batch-" + batchId, sha256, sha256,
				Timestamp.from(createdAt.plusSeconds(3600)), time, time, sha256, time);
		insertUploadedFile(jdbc, metadataFileId, batchId, clientMetadataId, sha256, createdAt, "METADATA",
				"metadata-" + metadataFileId);
		insertUploadedFile(jdbc, videoFileId, batchId, clientVideoId, sha256, createdAt, "VIDEO",
				"video-" + videoFileId);
		jdbc.update("""
				INSERT INTO clip_asset (
				    clip_asset_id, project_id, batch_id, source_upload_file_id, client_file_id,
				    content_type, size_bytes, sha256, storage_bucket, object_key, storage_etag,
				    asset_status, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'video/mp4', 1234, ?, 'sync-bucket', ?, 'etag', 'INGESTED', ?, ?)
				""", clipAssetId, projectId, batchId, videoFileId, clientVideoId, sha256, "objects/" + clipAssetId,
				time, time);
		jdbc.update("""
				INSERT INTO capture_session (
				    capture_session_id, project_id, batch_id, device_id, camera_set_id,
				    metadata_upload_file_id, metadata_schema_version, metadata_sha256,
				    expected_camera_count, received_camera_count, sync_group_id, device_time_utc,
				    device_timezone, server_receive_time, assembly_status, quality_status, created_at, updated_at)
				VALUES (?, ?, ?, 'sync-device', ?, ?, 'capture-session-metadata-v1', ?, 1, 1,
				        'sync-group', ?, 'UTC', ?, 'COMPLETE', 'PASS', ?, ?)
				""", captureSessionId, projectId, batchId, cameraSetId, metadataFileId, sha256, time, time, time, time);
		jdbc.update("""
				INSERT INTO camera_set_snapshot (
				    camera_set_snapshot_id, capture_session_id, source_camera_set_id, camera_set_key,
				    configuration_version, layout_version, expected_camera_count, created_at)
				VALUES (?, ?, ?, ?, 1, 'layout-v1', 1, ?)
				""", snapshotId, captureSessionId, cameraSetId, "sync-set-" + cameraSetId, time);
		jdbc.update("""
				INSERT INTO camera_set_snapshot_member (
				    camera_set_snapshot_member_id, camera_set_snapshot_id, camera_channel_id, camera_index,
				    camera_role, mount_position, enabled, enabled_for_hand_detection, created_at)
				VALUES (?, ?, ?, 0, 'FRONT', 'front', TRUE, TRUE, ?)
				""", snapshotMemberId, snapshotId, cameraChannelId, time);
		jdbc.update("""
				INSERT INTO camera_stream (
				    camera_stream_id, capture_session_id, camera_set_snapshot_member_id, camera_channel_id,
				    camera_index, clip_asset_id, client_file_id, record_start_utc, time_offset_ms,
				    declared_sync_status, stream_status, quality_status, created_at, updated_at)
				VALUES (?, ?, ?, ?, 0, ?, ?, ?, 0, 'UNKNOWN', 'INGESTED', 'PASS', ?, ?)
				""", cameraStreamId, captureSessionId, snapshotMemberId, cameraChannelId, clipAssetId, clientVideoId,
				time, time, time);
		insertMediaReport(jdbc, mediaJobId, mediaReportId, projectId, clipAssetId, sha256, recipeDigest, time);
		return new CaptureFixture(captureSessionId, snapshotId, snapshotMemberId, cameraStreamId, clipAssetId,
				mediaReportId);
	}

	public static void insertUploadedFile(JdbcTemplate jdbc, UUID fileId, UUID batchId, UUID clientFileId,
			String sha256, Instant createdAt, String kind, String objectKey) {
		var time = Timestamp.from(createdAt);
		jdbc.update("""
				INSERT INTO upload_file (
				    upload_file_id, batch_id, client_file_id, idempotency_key, request_fingerprint,
				    file_kind, content_type, size_bytes, sha256, part_size_bytes, total_parts, status,
				    created_at, updated_at, storage_bucket, object_key, storage_upload_id,
				    multipart_created_at, completion_started_at, completion_finished_at,
				    verified_sha256, completed_storage_etag)
				VALUES (?, ?, ?, ?, ?, ?, ?, 1234, ?, 8388608, 1, 'UPLOADED', ?, ?,
				        'sync-bucket', ?, ?, ?, ?, ?, ?, 'etag')
				""", fileId, batchId, clientFileId, "sync-file-" + fileId, sha256, kind,
				"METADATA".equals(kind) ? "application/json" : "video/mp4", sha256, time, time, objectKey,
				"upload-" + fileId, time, time, time, sha256);
	}

	public static void insertMediaReport(JdbcTemplate jdbc, UUID mediaJobId, UUID mediaReportId, UUID projectId,
			UUID clipAssetId, String sha256, String recipeDigest, Timestamp time) {
		jdbc.update("""
				INSERT INTO media_job (
				    media_job_id, project_id, clip_asset_id, job_type, input_digest, input_size_bytes,
				    content_type, status, idempotency_key, request_fingerprint, created_at, updated_at,
				    probe_profile_key, recipe_digest)
				VALUES (?, ?, ?, 'MEDIA_PROBE', ?, 1234, 'video/mp4', 'SUCCEEDED', ?, ?, ?, ?,
				        'media-probe-baseline-v1', ?)
				""", mediaJobId, projectId, clipAssetId, sha256, "sync-media-job-" + mediaJobId, sha256, time, time,
				recipeDigest);
		jdbc.update("""
				INSERT INTO media_report (
				    media_report_id, media_job_id, project_id, clip_asset_id, report_schema_version,
				    input_digest, recipe_digest, software_digest, container_format, video_codec,
				    duration_microseconds, width, height, video_stream_count, primary_video_stream_index,
				    time_base_numerator, time_base_denominator, variable_frame_rate, audio_stream_count,
				    decode_verified, input_digest_verified, warning_codes, created_at)
				VALUES (?, ?, ?, ?, 'media-report.v1', ?, ?, 'sha256:%s', 'mp4', 'h264', 1000000, 640, 360,
				        1, 0, 1, 90000, FALSE, 0, TRUE, TRUE, '[]'::jsonb, ?)
				""".formatted(sha256), mediaReportId, mediaJobId, projectId, clipAssetId, sha256, recipeDigest, time);
	}

	public record CaptureFixture(UUID captureSessionId, UUID snapshotId, UUID snapshotMemberId, UUID cameraStreamId,
			UUID clipAssetId, UUID mediaReportId) {
	}

}
