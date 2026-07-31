package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "asset_folder")
class AssetFolder {

	@Id
	@Column(name = "folder_id", nullable = false, updatable = false)
	private UUID folderId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "display_name", nullable = false, length = 128)
	private String displayName;

	@Column(name = "created_by", nullable = false, length = 128, updatable = false)
	private String createdBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_by", nullable = false, length = 128)
	private String updatedBy;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(name = "row_version", nullable = false)
	private long rowVersion;

	protected AssetFolder() {
	}

	private AssetFolder(UUID projectId, String displayName, String actorId, Instant occurredAt) {
		this.folderId = UUID.randomUUID();
		this.projectId = projectId;
		this.displayName = displayName;
		this.createdBy = actorId;
		this.createdAt = occurredAt;
		this.updatedBy = actorId;
		this.updatedAt = occurredAt;
	}

	static AssetFolder create(UUID projectId, String displayName, String actorId, Instant occurredAt) {
		return new AssetFolder(projectId, displayName, actorId, occurredAt);
	}

	void rename(String newDisplayName, String actorId, Instant occurredAt) {
		this.displayName = newDisplayName;
		this.updatedBy = actorId;
		this.updatedAt = occurredAt;
	}

	UUID getFolderId() {
		return this.folderId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	String getDisplayName() {
		return this.displayName;
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}

	Instant getUpdatedAt() {
		return this.updatedAt;
	}

	long getRowVersion() {
		return this.rowVersion;
	}

}
