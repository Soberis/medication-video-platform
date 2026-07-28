package com.medicationvideo.platform.datasetexport;

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
@Table(name = "dataset_version")
class DatasetVersion {

	@Id
	@Column(name = "dataset_version_id", nullable = false, updatable = false)
	private UUID datasetVersionId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "display_name", nullable = false, updatable = false, length = 128)
	private String displayName;

	@Enumerated(EnumType.STRING)
	@Column(name = "dataset_version_status", nullable = false, length = 16)
	private DatasetVersionStatus status;

	@Column(name = "created_by", nullable = false, updatable = false, length = 128)
	private String createdBy;

	@Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
	private String requestFingerprint;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Version
	@Column(name = "row_version", nullable = false)
	private long version;

	protected DatasetVersion() {
	}

	private DatasetVersion(
			UUID projectId,
			String displayName,
			String createdBy,
			String idempotencyKey,
			String requestFingerprint,
			Instant createdAt) {
		this.datasetVersionId = UUID.randomUUID();
		this.projectId = projectId;
		this.displayName = displayName;
		this.status = DatasetVersionStatus.DRAFT;
		this.createdBy = createdBy;
		this.idempotencyKey = idempotencyKey;
		this.requestFingerprint = requestFingerprint;
		this.createdAt = createdAt;
	}

	static DatasetVersion create(
			UUID projectId,
			String displayName,
			String createdBy,
			String idempotencyKey,
			String requestFingerprint,
			Instant createdAt) {
		return new DatasetVersion(projectId, displayName, createdBy, idempotencyKey, requestFingerprint, createdAt);
	}

	boolean matchesCreateRequest(String requestFingerprint) {
		return this.requestFingerprint.equals(requestFingerprint);
	}

	UUID getDatasetVersionId() {
		return this.datasetVersionId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	String getDisplayName() {
		return this.displayName;
	}

	DatasetVersionStatus getStatus() {
		return this.status;
	}

	String getCreatedBy() {
		return this.createdBy;
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}

	long getVersion() {
		return this.version;
	}
}

enum DatasetVersionStatus {
	DRAFT
}
