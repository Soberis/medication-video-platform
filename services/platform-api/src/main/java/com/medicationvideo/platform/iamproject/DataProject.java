package com.medicationvideo.platform.iamproject;

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
@Table(name = "project")
class DataProject {

	@Id
	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "display_name", nullable = false, length = 128, updatable = false)
	private String displayName;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private ProjectStatus status;

	@Column(name = "created_by", nullable = false, length = 128, updatable = false)
	private String createdBy;

	@Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
	private String requestFingerprint;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Version
	@Column(name = "row_version", nullable = false)
	private long rowVersion;

	protected DataProject() {
	}

	private DataProject(
			UUID projectId,
			String displayName,
			String createdBy,
			String idempotencyKey,
			String requestFingerprint,
			Instant createdAt) {
		this.projectId = projectId;
		this.displayName = displayName;
		this.status = ProjectStatus.ACTIVE;
		this.createdBy = createdBy;
		this.idempotencyKey = idempotencyKey;
		this.requestFingerprint = requestFingerprint;
		this.createdAt = createdAt;
	}

	static DataProject create(
			String displayName,
			String createdBy,
			String idempotencyKey,
			String requestFingerprint,
			Instant createdAt) {
		return new DataProject(
				UUID.randomUUID(),
				displayName,
				createdBy,
				idempotencyKey,
				requestFingerprint,
				createdAt);
	}

	UUID getProjectId() {
		return this.projectId;
	}

	String getDisplayName() {
		return this.displayName;
	}

	ProjectStatus getStatus() {
		return this.status;
	}

	String getCreatedBy() {
		return this.createdBy;
	}

	String getIdempotencyKey() {
		return this.idempotencyKey;
	}

	String getRequestFingerprint() {
		return this.requestFingerprint;
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}

	long getRowVersion() {
		return this.rowVersion;
	}
}
