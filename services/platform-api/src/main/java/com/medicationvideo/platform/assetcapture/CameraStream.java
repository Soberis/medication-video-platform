package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "camera_stream")
class CameraStream {

	@Id
	@Column(name = "camera_stream_id", nullable = false, updatable = false)
	private UUID cameraStreamId;

	@Column(name = "capture_session_id", nullable = false, updatable = false)
	private UUID captureSessionId;

	@Column(name = "camera_set_snapshot_member_id", nullable = false, updatable = false)
	private UUID cameraSetSnapshotMemberId;

	@Column(name = "camera_channel_id", nullable = false, updatable = false)
	private UUID cameraChannelId;

	@Column(name = "camera_index", nullable = false, updatable = false)
	private int cameraIndex;

	@Column(name = "clip_asset_id", nullable = false, updatable = false)
	private UUID clipAssetId;

	@Column(name = "client_file_id", nullable = false, updatable = false)
	private UUID clientFileId;

	@Column(name = "record_start_utc", nullable = false, updatable = false)
	private Instant recordStartUtc;

	@Column(name = "time_offset_ms", nullable = false, updatable = false)
	private long timeOffsetMs;

	@Enumerated(EnumType.STRING)
	@Column(name = "declared_sync_status", nullable = false, length = 32, updatable = false)
	private DeclaredSyncStatus declaredSyncStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "stream_status", nullable = false, length = 32)
	private CameraStreamStatus streamStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "quality_status", nullable = false, length = 32)
	private CaptureQualityStatus qualityStatus;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected CameraStream() {
	}

	private CameraStream(
			UUID captureSessionId,
			CameraSetSnapshotMember snapshotMember,
			ClipAsset clipAsset,
			CaptureSessionMetadataV1.Stream metadata,
			Instant createdAt) {
		this.cameraStreamId = UUID.randomUUID();
		this.captureSessionId = captureSessionId;
		this.cameraSetSnapshotMemberId = snapshotMember.getCameraSetSnapshotMemberId();
		this.cameraChannelId = snapshotMember.getCameraChannelId();
		this.cameraIndex = snapshotMember.getCameraIndex();
		this.clipAssetId = clipAsset.getClipAssetId();
		this.clientFileId = clipAsset.getClientFileId();
		this.recordStartUtc = metadata.recordStartUtc();
		this.timeOffsetMs = metadata.timeOffsetMs();
		this.declaredSyncStatus = metadata.syncStatus();
		this.streamStatus = initialStatus(clipAsset.getStatus());
		this.qualityStatus = CaptureQualityStatus.PENDING;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	static CameraStream bind(
			UUID captureSessionId,
			CameraSetSnapshotMember snapshotMember,
			ClipAsset clipAsset,
			CaptureSessionMetadataV1.Stream metadata,
			Instant createdAt) {
		return new CameraStream(captureSessionId, snapshotMember, clipAsset, metadata, createdAt);
	}

	UUID getCameraStreamId() {
		return this.cameraStreamId;
	}

	UUID getCaptureSessionId() {
		return this.captureSessionId;
	}

	UUID getCameraSetSnapshotMemberId() {
		return this.cameraSetSnapshotMemberId;
	}

	UUID getCameraChannelId() {
		return this.cameraChannelId;
	}

	int getCameraIndex() {
		return this.cameraIndex;
	}

	UUID getClipAssetId() {
		return this.clipAssetId;
	}

	UUID getClientFileId() {
		return this.clientFileId;
	}

	Instant getRecordStartUtc() {
		return this.recordStartUtc;
	}

	long getTimeOffsetMs() {
		return this.timeOffsetMs;
	}

	DeclaredSyncStatus getDeclaredSyncStatus() {
		return this.declaredSyncStatus;
	}

	CameraStreamStatus getStreamStatus() {
		return this.streamStatus;
	}

	CaptureQualityStatus getQualityStatus() {
		return this.qualityStatus;
	}

	private static CameraStreamStatus initialStatus(ClipAssetStatus assetStatus) {
		return switch (assetStatus) {
			case UPLOADED, INGESTING -> CameraStreamStatus.BOUND;
			case INGESTED -> CameraStreamStatus.INGESTED;
			case INGEST_FAILED -> CameraStreamStatus.INGEST_FAILED;
		};
	}
}
