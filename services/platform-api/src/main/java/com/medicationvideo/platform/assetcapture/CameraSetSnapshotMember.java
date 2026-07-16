package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "camera_set_snapshot_member")
class CameraSetSnapshotMember {

	@Id
	@Column(name = "camera_set_snapshot_member_id", nullable = false, updatable = false)
	private UUID cameraSetSnapshotMemberId;

	@Column(name = "camera_set_snapshot_id", nullable = false, updatable = false)
	private UUID cameraSetSnapshotId;

	@Column(name = "camera_channel_id", nullable = false, updatable = false)
	private UUID cameraChannelId;

	@Column(name = "camera_index", nullable = false, updatable = false)
	private int cameraIndex;

	@Column(name = "camera_role", nullable = false, length = 64, updatable = false)
	private String cameraRole;

	@Column(name = "mount_position", nullable = false, length = 128, updatable = false)
	private String mountPosition;

	@Column(name = "enabled", nullable = false, updatable = false)
	private boolean enabled;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected CameraSetSnapshotMember() {
	}

	private CameraSetSnapshotMember(
			UUID cameraSetSnapshotId,
			CameraSetMember source,
			Instant createdAt) {
		this.cameraSetSnapshotMemberId = UUID.randomUUID();
		this.cameraSetSnapshotId = cameraSetSnapshotId;
		this.cameraChannelId = source.getCameraChannelId();
		this.cameraIndex = source.getCameraIndex();
		this.cameraRole = source.getCameraRole();
		this.mountPosition = source.getMountPosition();
		this.enabled = source.isEnabled();
		this.createdAt = createdAt;
	}

	static CameraSetSnapshotMember freeze(
			UUID cameraSetSnapshotId,
			CameraSetMember source,
			Instant createdAt) {
		return new CameraSetSnapshotMember(cameraSetSnapshotId, source, createdAt);
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

	String getCameraRole() {
		return this.cameraRole;
	}

	String getMountPosition() {
		return this.mountPosition;
	}

	boolean isEnabled() {
		return this.enabled;
	}
}
