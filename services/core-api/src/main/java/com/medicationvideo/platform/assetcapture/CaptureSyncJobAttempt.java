package com.medicationvideo.platform.assetcapture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "capture_sync_job_attempt")
class CaptureSyncJobAttempt {

	@Id
	@Column(name = "capture_sync_job_attempt_id", nullable = false, updatable = false)
	private UUID captureSyncJobAttemptId;

	@Column(name = "capture_sync_job_id", nullable = false, updatable = false)
	private UUID captureSyncJobId;

	@Column(name = "attempt", nullable = false, updatable = false)
	private int attempt;

	@Column(name = "message_id", nullable = false, updatable = false)
	private UUID messageId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private CaptureSyncJobAttemptStatus status;

	@Column(name = "lease_token_hash", nullable = false, length = 64)
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

	protected CaptureSyncJobAttempt() {
	}

	private CaptureSyncJobAttempt(UUID captureSyncJobId, int attempt, UUID messageId, String leaseTokenHash,
			Instant createdAt) {
		if (attempt < 1) {
			throw new IllegalArgumentException("Capture synchronization attempt must be positive.");
		}
		this.captureSyncJobAttemptId = UUID.randomUUID();
		this.captureSyncJobId = captureSyncJobId;
		this.attempt = attempt;
		this.messageId = messageId;
		this.status = CaptureSyncJobAttemptStatus.QUEUED;
		this.leaseTokenHash = requireSha256(leaseTokenHash, "leaseTokenHash");
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	static CaptureSyncJobAttempt queue(UUID captureSyncJobId, int attempt, UUID messageId, String leaseTokenHash,
			Instant createdAt) {
		return new CaptureSyncJobAttempt(captureSyncJobId, attempt, messageId, leaseTokenHash, createdAt);
	}

	void issueLeaseToken(String leaseToken) {
		if (this.status != CaptureSyncJobAttemptStatus.QUEUED) {
			throw new IllegalStateException("A lease token can only be issued for a queued attempt.");
		}
		this.leaseTokenHash = hashLeaseToken(leaseToken);
	}

	static String hashLeaseToken(String leaseToken) {
		if (leaseToken == null || leaseToken.isBlank()) {
			throw new IllegalArgumentException("leaseToken must not be blank.");
		}
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(leaseToken.getBytes(StandardCharsets.UTF_8)));
		}
		catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable in the Java runtime.", exception);
		}
	}

	void acquire(String requestedWorkerId, Instant now, Duration leaseDuration) {
		if (this.status == CaptureSyncJobAttemptStatus.RUNNING) {
			if (!requestedWorkerId.equals(this.workerId) || this.leaseUntil == null || !now.isBefore(this.leaseUntil)) {
				throw new IllegalStateException("The capture synchronization attempt lease is unavailable.");
			}
			return;
		}
		if (this.status != CaptureSyncJobAttemptStatus.QUEUED) {
			throw new IllegalStateException("The capture synchronization attempt is no longer queued.");
		}
		this.status = CaptureSyncJobAttemptStatus.RUNNING;
		this.workerId = required(requestedWorkerId, "workerId");
		this.leaseStartedAt = now;
		this.leaseUntil = now.plus(requirePositive(leaseDuration, "leaseDuration"));
		this.lastHeartbeatAt = now;
		this.startedAt = now;
		this.updatedAt = now;
	}

	void heartbeat(String requestedWorkerId, Instant now, Duration leaseDuration) {
		requireActiveLease(now);
		if (!this.workerId.equals(requestedWorkerId)) {
			throw new IllegalStateException("The capture synchronization lease belongs to another Worker.");
		}
		this.leaseUntil = now.plus(requirePositive(leaseDuration, "leaseDuration"));
		this.lastHeartbeatAt = now;
		this.updatedAt = now;
	}

	void succeed(Instant now) {
		requireActiveLease(now);
		this.status = CaptureSyncJobAttemptStatus.SUCCEEDED;
		this.completedAt = now;
		this.updatedAt = now;
	}

	void fail(String errorClass, String errorCode, String safeMessage, boolean retryable, Instant now) {
		requireActiveLease(now);
		this.status = CaptureSyncJobAttemptStatus.FAILED;
		this.errorClass = required(errorClass, "errorClass");
		this.errorCode = required(errorCode, "errorCode");
		this.safeErrorMessage = required(safeMessage, "safeMessage");
		this.errorRetryable = retryable;
		this.completedAt = now;
		this.updatedAt = now;
	}

	void lose(Instant now) {
		if (this.status != CaptureSyncJobAttemptStatus.RUNNING) {
			throw new IllegalStateException("The capture synchronization attempt is not running.");
		}
		this.status = CaptureSyncJobAttemptStatus.LOST;
		this.errorClass = "RETRYABLE_RESOURCE";
		this.errorCode = "WORKER_LEASE_LOST";
		this.safeErrorMessage = "The Worker stopped renewing its capture synchronization lease.";
		this.errorRetryable = true;
		this.completedAt = now;
		this.updatedAt = now;
	}

	boolean isRecoverableAt(Instant cutoff) {
		return this.status == CaptureSyncJobAttemptStatus.RUNNING && this.leaseUntil != null
				&& !this.leaseUntil.isAfter(cutoff);
	}

	void requireActiveLease(Instant now) {
		if (this.status != CaptureSyncJobAttemptStatus.RUNNING || this.leaseUntil == null
				|| !now.isBefore(this.leaseUntil)) {
			throw new IllegalStateException("The capture synchronization lease is not active.");
		}
	}

	boolean matchesLeaseHash(String candidateHash) {
		return MessageDigest.isEqual(this.leaseTokenHash.getBytes(StandardCharsets.US_ASCII),
				requireSha256(candidateHash, "candidateHash").getBytes(StandardCharsets.US_ASCII));
	}

	UUID getCaptureSyncJobAttemptId() {
		return this.captureSyncJobAttemptId;
	}

	UUID getCaptureSyncJobId() {
		return this.captureSyncJobId;
	}

	int getAttempt() {
		return this.attempt;
	}

	UUID getMessageId() {
		return this.messageId;
	}

	CaptureSyncJobAttemptStatus getStatus() {
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

	String getWorkerId() {
		return this.workerId;
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

	private static Duration requirePositive(Duration value, String name) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(name + " must be positive.");
		}
		return value;
	}

	private static String required(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must be explicit.");
		}
		return value;
	}

	private static String requireSha256(String value, String name) {
		var normalized = required(value, name);
		if (!normalized.matches("[a-f0-9]{64}")) {
			throw new IllegalArgumentException(name + " must be a lowercase SHA-256 digest.");
		}
		return normalized;
	}

}
