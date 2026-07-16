package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "camera_set_snapshot")
class CameraSetSnapshot {

	@Id
	@Column(name = "camera_set_snapshot_id", nullable = false, updatable = false)
	private UUID cameraSetSnapshotId;

	@Column(name = "capture_session_id", nullable = false, updatable = false)
	private UUID captureSessionId;

	@Column(name = "source_camera_set_id", nullable = false, updatable = false)
	private UUID sourceCameraSetId;

	@Column(name = "camera_set_key", nullable = false, length = 128, updatable = false)
	private String cameraSetKey;

	@Column(name = "configuration_version", nullable = false, updatable = false)
	private int configurationVersion;

	@Column(name = "layout_version", nullable = false, length = 128, updatable = false)
	private String layoutVersion;

	@Column(name = "expected_camera_count", nullable = false, updatable = false)
	private int expectedCameraCount;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected CameraSetSnapshot() {
	}

	private CameraSetSnapshot(
			UUID captureSessionId,
			CameraSetConfiguration source,
			int expectedCameraCount,
			Instant createdAt) {
		this.cameraSetSnapshotId = UUID.randomUUID();
		this.captureSessionId = captureSessionId;
		this.sourceCameraSetId = source.getCameraSetId();
		this.cameraSetKey = source.getCameraSetKey();
		this.configurationVersion = source.getConfigurationVersion();
		this.layoutVersion = source.getLayoutVersion();
		this.expectedCameraCount = expectedCameraCount;
		this.createdAt = createdAt;
	}

	static CameraSetSnapshot freeze(
			UUID captureSessionId,
			CameraSetConfiguration source,
			int expectedCameraCount,
			Instant createdAt) {
		return new CameraSetSnapshot(captureSessionId, source, expectedCameraCount, createdAt);
	}

	UUID getCameraSetSnapshotId() {
		return this.cameraSetSnapshotId;
	}

	UUID getSourceCameraSetId() {
		return this.sourceCameraSetId;
	}

	String getCameraSetKey() {
		return this.cameraSetKey;
	}

	int getConfigurationVersion() {
		return this.configurationVersion;
	}

	String getLayoutVersion() {
		return this.layoutVersion;
	}

	int getExpectedCameraCount() {
		return this.expectedCameraCount;
	}
}
