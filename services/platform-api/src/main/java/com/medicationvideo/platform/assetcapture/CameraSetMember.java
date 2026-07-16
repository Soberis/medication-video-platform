package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "camera_set_member")
class CameraSetMember {

	@Id
	@Column(name = "camera_set_member_id", nullable = false, updatable = false)
	private UUID cameraSetMemberId;

	@Column(name = "camera_set_id", nullable = false, updatable = false)
	private UUID cameraSetId;

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

	protected CameraSetMember() {
	}

	private CameraSetMember(UUID cameraSetId, CameraSetMemberRequest request, Instant createdAt) {
		this.cameraSetMemberId = UUID.randomUUID();
		this.cameraSetId = cameraSetId;
		this.cameraChannelId = request.cameraChannelId();
		this.cameraIndex = request.cameraIndex();
		this.cameraRole = request.cameraRole();
		this.mountPosition = request.mountPosition();
		this.enabled = request.enabled();
		this.createdAt = createdAt;
	}

	static CameraSetMember create(UUID cameraSetId, CameraSetMemberRequest request, Instant createdAt) {
		return new CameraSetMember(cameraSetId, request, createdAt);
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
