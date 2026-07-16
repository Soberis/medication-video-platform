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
@Table(name = "upload_batch")
class UploadBatch {

	@Id
	@Column(name = "batch_id", nullable = false, updatable = false)
	private UUID batchId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
	private String requestFingerprint;

	@Column(name = "device_id", nullable = false, length = 128, updatable = false)
	private String deviceId;

	@Column(name = "file_count", nullable = false, updatable = false)
	private int fileCount;

	@Column(name = "total_size_bytes", nullable = false, updatable = false)
	private long totalSizeBytes;

	@Column(name = "manifest_sha256", nullable = false, length = 64, updatable = false)
	private String manifestSha256;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private UploadBatchStatus status;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "verified_manifest_sha256", length = 64)
	private String verifiedManifestSha256;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected UploadBatch() {
	}

	private UploadBatch(
			UUID batchId,
			UUID projectId,
			String idempotencyKey,
			String requestFingerprint,
			String deviceId,
			int fileCount,
			long totalSizeBytes,
			String manifestSha256,
			Instant expiresAt,
			Instant createdAt) {
		this.batchId = batchId;
		this.projectId = projectId;
		this.idempotencyKey = idempotencyKey;
		this.requestFingerprint = requestFingerprint;
		this.deviceId = deviceId;
		this.fileCount = fileCount;
		this.totalSizeBytes = totalSizeBytes;
		this.manifestSha256 = manifestSha256;
		this.status = UploadBatchStatus.INITIATED;
		this.expiresAt = expiresAt;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	static UploadBatch initiate(
			UUID projectId,
			String idempotencyKey,
			String requestFingerprint,
			String deviceId,
			int fileCount,
			long totalSizeBytes,
			String manifestSha256,
			Instant expiresAt,
			Instant createdAt) {
		return new UploadBatch(
				UUID.randomUUID(),
				projectId,
				idempotencyKey,
				requestFingerprint,
				deviceId,
				fileCount,
				totalSizeBytes,
				manifestSha256,
				expiresAt,
				createdAt);
	}

	UUID getBatchId() {
		return this.batchId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	String getRequestFingerprint() {
		return this.requestFingerprint;
	}

	String getDeviceId() {
		return this.deviceId;
	}

	int getFileCount() {
		return this.fileCount;
	}

	long getTotalSizeBytes() {
		return this.totalSizeBytes;
	}

	String getManifestSha256() {
		return this.manifestSha256;
	}

	UploadBatchStatus getStatus() {
		return this.status;
	}

	Instant getExpiresAt() {
		return this.expiresAt;
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}

	String getVerifiedManifestSha256() {
		return this.verifiedManifestSha256;
	}

	Instant getCompletedAt() {
		return this.completedAt;
	}

	void markUploading(Instant changedAt) {
		if (this.status == UploadBatchStatus.INITIATED) {
			this.status = UploadBatchStatus.UPLOADING;
			this.updatedAt = changedAt;
		}
		else if (this.status != UploadBatchStatus.UPLOADING) {
			throw new IllegalStateException("Upload batch cannot start uploading from its current state.");
		}
	}

	void markCompleted(String verifiedManifestSha256, Instant changedAt) {
		if (this.status != UploadBatchStatus.UPLOADING) {
			throw new IllegalStateException("Upload batch cannot complete from its current state.");
		}
		if (!this.manifestSha256.equals(verifiedManifestSha256)) {
			throw new IllegalArgumentException("Verified manifest does not match the batch declaration.");
		}
		this.status = UploadBatchStatus.COMPLETING;
		this.verifiedManifestSha256 = verifiedManifestSha256;
		this.completedAt = changedAt;
		this.status = UploadBatchStatus.COMPLETED;
		this.updatedAt = changedAt;
	}
}
