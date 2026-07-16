package com.medicationvideo.platform.mediaprivacy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
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
@Table(name = "media_job_attempt")
class MediaJobAttempt {

	@Id
	@Column(name = "media_job_attempt_id", nullable = false, updatable = false)
	private UUID mediaJobAttemptId;

	@Column(name = "media_job_id", nullable = false, updatable = false)
	private UUID mediaJobId;

	@Column(name = "attempt", nullable = false, updatable = false)
	private int attempt;

	@Column(name = "message_id", nullable = false, updatable = false)
	private UUID messageId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private MediaJobAttemptStatus status;

	@Column(name = "lease_token_hash", nullable = false, length = 64, updatable = false)
	private String leaseTokenHash;

	@Column(name = "worker_id", length = 128)
	private String workerId;

	@Column(name = "lease_started_at")
	private Instant leaseStartedAt;

	@Column(name = "lease_until")
	private Instant leaseUntil;

	@Column(name = "last_heartbeat_at")
	private Instant lastHeartbeatAt;

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
	@Column(name = "version", nullable = false)
	private long version;

	protected MediaJobAttempt() {
	}

	private MediaJobAttempt(
			UUID mediaJobId,
			int attempt,
			UUID messageId,
			String leaseTokenHash,
			Instant createdAt) {
		this.mediaJobAttemptId = UUID.randomUUID();
		this.mediaJobId = mediaJobId;
		this.attempt = attempt;
		this.messageId = messageId;
		this.status = MediaJobAttemptStatus.QUEUED;
		this.leaseTokenHash = leaseTokenHash;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	static MediaJobAttempt queue(
			UUID mediaJobId,
			int attempt,
			UUID messageId,
			String leaseTokenHash,
			Instant createdAt) {
		if (attempt < 1) {
			throw new IllegalArgumentException("Media job attempt must be positive.");
		}
		return new MediaJobAttempt(mediaJobId, attempt, messageId, leaseTokenHash, createdAt);
	}

	void acquire(String requestedWorkerId, Instant now, Duration leaseDuration) {
		if (this.status == MediaJobAttemptStatus.RUNNING) {
			if (!requestedWorkerId.equals(this.workerId) || this.leaseUntil == null
					|| !now.isBefore(this.leaseUntil)) {
				throw new MediaJobExecutionException(
						"MEDIA_JOB_LEASE_CONFLICT",
						"The media job attempt already has an active or expired lease.",
						false);
			}
			return;
		}
		if (this.status != MediaJobAttemptStatus.QUEUED) {
			throw new MediaJobExecutionException(
					"STALE_ATTEMPT", "The media job attempt is no longer queued.", false);
		}
		this.status = MediaJobAttemptStatus.RUNNING;
		this.workerId = requestedWorkerId;
		this.leaseStartedAt = now;
		this.leaseUntil = now.plus(leaseDuration);
		this.lastHeartbeatAt = now;
		this.startedAt = now;
		this.updatedAt = now;
	}

	void heartbeat(String requestedWorkerId, Instant now, Duration leaseDuration) {
		requireActiveLease(now);
		if (!requestedWorkerId.equals(this.workerId)) {
			throw new MediaJobExecutionException(
					"MEDIA_JOB_LEASE_CONFLICT",
					"The media job lease belongs to a different Worker.",
					false);
		}
		this.leaseUntil = now.plus(leaseDuration);
		this.lastHeartbeatAt = now;
		this.updatedAt = now;
	}

	void succeed(Instant now) {
		requireActiveLease(now);
		this.status = MediaJobAttemptStatus.SUCCEEDED;
		this.completedAt = now;
		this.updatedAt = now;
	}

	void fail(MediaProbeErrorPayload error, Instant now) {
		requireActiveLease(now);
		this.status = MediaJobAttemptStatus.FAILED;
		this.errorClass = error.errorClass().name();
		this.errorCode = error.errorCode().name();
		this.safeErrorMessage = error.safeMessage();
		this.errorRetryable = error.retryable();
		this.completedAt = now;
		this.updatedAt = now;
	}

	void lose(Instant now) {
		if (this.status != MediaJobAttemptStatus.RUNNING) {
			throw new MediaJobExecutionException(
					"STALE_ATTEMPT", "The media job attempt is no longer running.", false);
		}
		this.status = MediaJobAttemptStatus.LOST;
		this.errorClass = "RETRYABLE_RESOURCE";
		this.errorCode = "WORKER_LEASE_LOST";
		this.safeErrorMessage = "The Worker stopped renewing its media job lease.";
		this.errorRetryable = true;
		this.completedAt = now;
		this.updatedAt = now;
	}

	boolean isRecoverableAt(Instant cutoff) {
		return this.status == MediaJobAttemptStatus.RUNNING
				&& this.leaseUntil != null
				&& !this.leaseUntil.isAfter(cutoff);
	}

	void requireActiveLease(Instant now) {
		if (this.status != MediaJobAttemptStatus.RUNNING || this.leaseUntil == null
				|| !now.isBefore(this.leaseUntil)) {
			throw new MediaJobExecutionException(
					"MEDIA_JOB_LEASE_EXPIRED", "The media job lease is not active.", false);
		}
	}

	boolean matchesLeaseHash(String candidateHash) {
		return MessageDigest.isEqual(
				this.leaseTokenHash.getBytes(StandardCharsets.US_ASCII),
				candidateHash.getBytes(StandardCharsets.US_ASCII));
	}

	UUID getMediaJobId() {
		return this.mediaJobId;
	}

	int getAttempt() {
		return this.attempt;
	}

	UUID getMessageId() {
		return this.messageId;
	}

	MediaJobAttemptStatus getStatus() {
		return this.status;
	}

	Instant getLeaseUntil() {
		return this.leaseUntil;
	}

	Instant getLastHeartbeatAt() {
		return this.lastHeartbeatAt;
	}

	Instant getStartedAt() {
		return this.startedAt;
	}

	Instant getCompletedAt() {
		return this.completedAt;
	}

	String getErrorClass() {
		return this.errorClass;
	}

	String getErrorCode() {
		return this.errorCode;
	}

	String getSafeErrorMessage() {
		return this.safeErrorMessage;
	}

	Boolean getErrorRetryable() {
		return this.errorRetryable;
	}
}
