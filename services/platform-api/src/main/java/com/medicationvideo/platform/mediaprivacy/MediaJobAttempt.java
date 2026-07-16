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
	private MediaJobStatus status;

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

	private MediaJobAttempt(UUID mediaJobId, UUID messageId, String leaseTokenHash, Instant createdAt) {
		this.mediaJobAttemptId = UUID.randomUUID();
		this.mediaJobId = mediaJobId;
		this.attempt = 1;
		this.messageId = messageId;
		this.status = MediaJobStatus.QUEUED;
		this.leaseTokenHash = leaseTokenHash;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	static MediaJobAttempt queue(UUID mediaJobId, UUID messageId, String leaseTokenHash, Instant createdAt) {
		return new MediaJobAttempt(mediaJobId, messageId, leaseTokenHash, createdAt);
	}

	void acquire(String requestedWorkerId, Instant now, Duration leaseDuration) {
		if (this.status == MediaJobStatus.RUNNING) {
			if (!requestedWorkerId.equals(this.workerId) || this.leaseUntil == null
					|| !now.isBefore(this.leaseUntil)) {
				throw new MediaJobExecutionException(
						"MEDIA_JOB_LEASE_CONFLICT",
						"The media job attempt already has an active or expired lease.",
						false);
			}
			return;
		}
		if (this.status != MediaJobStatus.QUEUED) {
			throw new MediaJobExecutionException(
					"STALE_ATTEMPT", "The media job attempt is no longer queued.", false);
		}
		this.status = MediaJobStatus.RUNNING;
		this.workerId = requestedWorkerId;
		this.leaseStartedAt = now;
		this.leaseUntil = now.plus(leaseDuration);
		this.lastHeartbeatAt = now;
		this.startedAt = now;
		this.updatedAt = now;
	}

	void succeed(Instant now) {
		requireActiveLease(now);
		this.status = MediaJobStatus.SUCCEEDED;
		this.completedAt = now;
		this.updatedAt = now;
	}

	void fail(MediaProbeErrorPayload error, Instant now) {
		requireActiveLease(now);
		this.status = MediaJobStatus.FAILED;
		this.errorClass = error.errorClass().name();
		this.errorCode = error.errorCode().name();
		this.safeErrorMessage = error.safeMessage();
		this.errorRetryable = error.retryable();
		this.completedAt = now;
		this.updatedAt = now;
	}

	void requireActiveLease(Instant now) {
		if (this.status != MediaJobStatus.RUNNING || this.leaseUntil == null
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

	MediaJobStatus getStatus() {
		return this.status;
	}

	Instant getLeaseUntil() {
		return this.leaseUntil;
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
