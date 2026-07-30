package com.medicationvideo.platform.datasetexport;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "dataset_export_attempt")
class DatasetExportAttempt {

	@Id
	@Column(name = "dataset_export_attempt_id", nullable = false, updatable = false)
	private UUID datasetExportAttemptId;

	@Column(name = "dataset_export_id", nullable = false, updatable = false)
	private UUID datasetExportId;

	@Column(name = "attempt", nullable = false, updatable = false)
	private int attempt;

	@Column(name = "message_id", nullable = false, updatable = false)
	private UUID messageId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16)
	private DatasetExportAttemptStatus status;

	@Column(name = "lease_token_hash", nullable = false, updatable = false, length = 64)
	private String leaseTokenHash;

	@Column(name = "worker_id", length = 128)
	private String workerId;

	@Column(name = "lease_started_at")
	private Instant leaseStartedAt;

	@Column(name = "lease_until")
	private Instant leaseUntil;

	@Column(name = "last_heartbeat_at")
	private Instant lastHeartbeatAt;

	@Column(name = "output_bucket", length = 255)
	private String outputBucket;

	@Column(name = "output_object_key", length = 1024)
	private String outputObjectKey;

	@Column(name = "output_upload_id", length = 2048)
	private String outputUploadId;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "error_class", length = 64)
	private String errorClass;

	@Column(name = "error_code", length = 64)
	private String errorCode;

	@Column(name = "safe_error_message", length = 512)
	private String safeErrorMessage;

	@Column(name = "error_retryable")
	private Boolean errorRetryable;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(name = "row_version", nullable = false)
	private long version;

	protected DatasetExportAttempt() {
	}

	private DatasetExportAttempt(
			DatasetExport export,
			int attempt,
			UUID messageId,
			String leaseTokenHash,
			Instant createdAt) {
		this.datasetExportAttemptId = UUID.randomUUID();
		this.datasetExportId = export.getDatasetExportId();
		this.attempt = attempt;
		this.messageId = messageId;
		this.status = DatasetExportAttemptStatus.QUEUED;
		this.leaseTokenHash = leaseTokenHash;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	static DatasetExportAttempt queue(
			DatasetExport export,
			int attempt,
			UUID messageId,
			String leaseTokenHash,
			Instant createdAt) {
		return new DatasetExportAttempt(export, attempt, messageId, leaseTokenHash, createdAt);
	}

	void acquire(String workerId, Instant now, Duration leaseDuration) {
		if (this.status == DatasetExportAttemptStatus.RUNNING) {
			if (!Objects.equals(this.workerId, workerId)) {
				throw new DatasetExportExecutionException(
						"EXPORT_ATTEMPT_ALREADY_LEASED",
						"The dataset export attempt is leased by another Worker.",
						false);
			}
			heartbeat(workerId, now, leaseDuration);
			return;
		}
		if (this.status != DatasetExportAttemptStatus.QUEUED) {
			throw new DatasetExportExecutionException(
					"STALE_ATTEMPT", "The dataset export attempt is no longer queued.", false);
		}
		this.status = DatasetExportAttemptStatus.RUNNING;
		this.workerId = workerId;
		this.leaseStartedAt = now;
		this.lastHeartbeatAt = now;
		this.leaseUntil = now.plus(leaseDuration);
		this.startedAt = now;
		this.updatedAt = now;
	}

	void heartbeat(String workerId, Instant now, Duration leaseDuration) {
		requireActiveLease(now);
		if (!Objects.equals(this.workerId, workerId)) {
			throw new DatasetExportExecutionException(
					"EXPORT_WORKER_IDENTITY_MISMATCH",
					"The dataset export attempt belongs to another Worker.",
					false);
		}
		this.lastHeartbeatAt = now;
		this.leaseUntil = now.plus(leaseDuration);
		this.updatedAt = now;
	}

	void attachUpload(DatasetExportStoragePort.CreatedUpload upload, Instant now) {
		if (this.outputUploadId != null) {
			if (!this.outputBucket.equals(upload.bucket())
					|| !this.outputObjectKey.equals(upload.objectKey())
					|| !this.outputUploadId.equals(upload.uploadId())) {
				throw new IllegalStateException("The dataset export attempt already has another upload.");
			}
			return;
		}
		this.outputBucket = upload.bucket();
		this.outputObjectKey = upload.objectKey();
		this.outputUploadId = upload.uploadId();
		this.updatedAt = now;
	}

	void succeed(Instant now) {
		if (this.status != DatasetExportAttemptStatus.RUNNING) {
			throw new DatasetExportExecutionException(
					"STALE_ATTEMPT", "The dataset export attempt is no longer running.", false);
		}
		this.status = DatasetExportAttemptStatus.SUCCEEDED;
		this.completedAt = now;
		this.updatedAt = now;
	}

	void fail(DatasetExportErrorPayload error, Instant now) {
		if (this.status != DatasetExportAttemptStatus.RUNNING) {
			throw new DatasetExportExecutionException(
					"STALE_ATTEMPT", "The dataset export attempt is no longer running.", false);
		}
		this.status = DatasetExportAttemptStatus.FAILED;
		this.errorClass = error.errorClass().name();
		this.errorCode = error.errorCode().name();
		this.safeErrorMessage = error.safeMessage();
		this.errorRetryable = error.retryable();
		this.completedAt = now;
		this.updatedAt = now;
	}

	void requireActiveLease(Instant now) {
		if (this.status != DatasetExportAttemptStatus.RUNNING
				|| this.leaseUntil == null
				|| !this.leaseUntil.isAfter(now)) {
			throw new DatasetExportExecutionException(
					"EXPORT_LEASE_EXPIRED", "The dataset export lease is no longer active.", false);
		}
	}

	boolean matchesLeaseHash(String candidateHash) {
		return this.leaseTokenHash.equals(candidateHash);
	}

	DatasetExportStoragePort.CreatedUpload getUpload() {
		if (this.outputBucket == null || this.outputObjectKey == null || this.outputUploadId == null) {
			return null;
		}
		return new DatasetExportStoragePort.CreatedUpload(
				this.outputBucket, this.outputObjectKey, this.outputUploadId);
	}

	UUID getDatasetExportAttemptId() {
		return this.datasetExportAttemptId;
	}

	UUID getDatasetExportId() {
		return this.datasetExportId;
	}

	int getAttempt() {
		return this.attempt;
	}

	UUID getMessageId() {
		return this.messageId;
	}

	DatasetExportAttemptStatus getStatus() {
		return this.status;
	}

	Instant getLeaseUntil() {
		return this.leaseUntil;
	}

	String getWorkerId() {
		return this.workerId;
	}

	Instant getStartedAt() {
		return this.startedAt;
	}

	Instant getCompletedAt() {
		return this.completedAt;
	}
}

enum DatasetExportAttemptStatus {
	QUEUED,
	RUNNING,
	SUCCEEDED,
	FAILED,
	LOST
}
