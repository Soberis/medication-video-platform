package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "camera_set")
class CameraSetConfiguration {

	@Id
	@Column(name = "camera_set_id", nullable = false, updatable = false)
	private UUID cameraSetId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "camera_set_key", nullable = false, length = 128, updatable = false)
	private String cameraSetKey;

	@Column(name = "configuration_version", nullable = false, updatable = false)
	private int configurationVersion;

	@Column(name = "layout_version", nullable = false, length = 128, updatable = false)
	private String layoutVersion;

	@Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
	private String requestFingerprint;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected CameraSetConfiguration() {
	}

	private CameraSetConfiguration(UUID projectId, String cameraSetKey, int configurationVersion, String layoutVersion,
			String requestFingerprint, Instant createdAt) {
		this.cameraSetId = UUID.randomUUID();
		this.projectId = projectId;
		this.cameraSetKey = cameraSetKey;
		this.configurationVersion = configurationVersion;
		this.layoutVersion = layoutVersion;
		this.requestFingerprint = requestFingerprint;
		this.createdAt = createdAt;
	}

	static CameraSetConfiguration create(UUID projectId, String cameraSetKey, int configurationVersion,
			String layoutVersion, String requestFingerprint, Instant createdAt) {
		return new CameraSetConfiguration(projectId, cameraSetKey, configurationVersion, layoutVersion,
				requestFingerprint, createdAt);
	}

	UUID getCameraSetId() {
		return this.cameraSetId;
	}

	UUID getProjectId() {
		return this.projectId;
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

	String getRequestFingerprint() {
		return this.requestFingerprint;
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}

}
