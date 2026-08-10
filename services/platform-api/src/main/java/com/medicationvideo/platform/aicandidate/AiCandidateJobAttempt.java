package com.medicationvideo.platform.aicandidate;

import java.time.Instant;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

@Entity
@Table(name = "ai_job_attempt")
class AiCandidateJobAttempt {

	@Id
	@Column(name = "ai_job_attempt_id", nullable = false, updatable = false)
	private UUID aiJobAttemptId;

	@Column(name = "ai_job_id", nullable = false, updatable = false)
	private UUID aiJobId;

	@Column(name = "attempt", nullable = false, updatable = false)
	private int attempt;

	@Column(name = "message_id", nullable = false, updatable = false)
	private UUID messageId;

	@Column(name = "lease_token_hash", nullable = false, updatable = false, length = 64)
	private String leaseTokenHash;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private AiCandidateJobAttemptStatus status;

	@Column(name = "worker_id", length = 128)
	private String workerId;

	@Column(name = "lease_until")
	private Instant leaseUntil;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected AiCandidateJobAttempt() {
	}

	private AiCandidateJobAttempt(UUID aiJobId, int attempt, UUID messageId, String leaseTokenHash, Instant createdAt) {
		this.aiJobAttemptId = UUID.randomUUID();
		this.aiJobId = aiJobId;
		this.attempt = attempt;
		this.messageId = messageId;
		this.leaseTokenHash = leaseTokenHash;
		this.status = AiCandidateJobAttemptStatus.QUEUED;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	void acquire(String workerId, Instant now, Duration leaseDuration) {
		if (this.status == AiCandidateJobAttemptStatus.RUNNING) {
			if (!workerId.equals(this.workerId) || this.leaseUntil == null || !now.isBefore(this.leaseUntil)) {
				throw new HandPoseJobException("AI_HAND_POSE_LEASE_CONFLICT",
						"The AI job attempt already has an active or expired lease.", 409);
			}
			return;
		}
		if (this.status != AiCandidateJobAttemptStatus.QUEUED) {
			throw new HandPoseJobException("STALE_ATTEMPT", "The AI job attempt is no longer queued.", 409);
		}
		this.status = AiCandidateJobAttemptStatus.RUNNING;
		this.workerId = workerId;
		this.leaseUntil = now.plus(leaseDuration);
		this.updatedAt = now;
	}

	void heartbeat(String workerId, Instant now, Duration leaseDuration) {
		requireActiveLease(now);
		if (!workerId.equals(this.workerId)) {
			throw new HandPoseJobException("AI_HAND_POSE_LEASE_CONFLICT",
					"The AI job lease belongs to a different Worker.", 409);
		}
		this.leaseUntil = now.plus(leaseDuration);
		this.updatedAt = now;
	}

	void succeed(Instant now) {
		requireActiveLease(now);
		this.status = AiCandidateJobAttemptStatus.SUCCEEDED;
		this.updatedAt = now;
	}

	void fail(Instant now) {
		requireActiveLease(now);
		this.status = AiCandidateJobAttemptStatus.FAILED;
		this.updatedAt = now;
	}

	void requireActiveLease(Instant now) {
		if (this.status != AiCandidateJobAttemptStatus.RUNNING || this.leaseUntil == null
				|| !now.isBefore(this.leaseUntil)) {
			throw new HandPoseJobException("AI_HAND_POSE_LEASE_EXPIRED", "The AI job lease is not active.", 409);
		}
	}

	boolean matchesLeaseHash(String candidate) {
		return MessageDigest.isEqual(this.leaseTokenHash.getBytes(StandardCharsets.US_ASCII),
				candidate.getBytes(StandardCharsets.US_ASCII));
	}

	UUID getMessageId() {
		return this.messageId;
	}

	int getAttempt() {
		return this.attempt;
	}

	Instant getLeaseUntil() {
		return this.leaseUntil;
	}

	static AiCandidateJobAttempt queue(UUID aiJobId, int attempt, UUID messageId, String leaseTokenHash,
			Instant createdAt) {
		if (attempt < 1)
			throw new IllegalArgumentException("AI job attempt must be positive.");
		return new AiCandidateJobAttempt(aiJobId, attempt, messageId, leaseTokenHash, createdAt);
	}

}
