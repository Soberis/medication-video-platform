package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "camera_channel")
class CameraChannel {

	@Id
	@Column(name = "camera_channel_id", nullable = false, updatable = false)
	private UUID cameraChannelId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "provisioning_key", nullable = false, length = 128, updatable = false)
	private String provisioningKey;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected CameraChannel() {
	}

	private CameraChannel(UUID projectId, String provisioningKey, Instant createdAt) {
		this.cameraChannelId = UUID.randomUUID();
		this.projectId = projectId;
		this.provisioningKey = provisioningKey;
		this.createdAt = createdAt;
	}

	static CameraChannel provision(UUID projectId, String provisioningKey, Instant createdAt) {
		return new CameraChannel(projectId, provisioningKey, createdAt);
	}

	UUID getCameraChannelId() {
		return this.cameraChannelId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	String getProvisioningKey() {
		return this.provisioningKey;
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}
}
