package com.medicationvideo.platform.aicandidate;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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
		this.createdAt = createdAt;
	}

	static AiCandidateJobAttempt queue(UUID aiJobId, int attempt, UUID messageId, String leaseTokenHash,
			Instant createdAt) {
		if (attempt < 1)
			throw new IllegalArgumentException("AI job attempt must be positive.");
		return new AiCandidateJobAttempt(aiJobId, attempt, messageId, leaseTokenHash, createdAt);
	}

}
