package com.medicationvideo.platform.aicandidate;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ai_job_idempotency_receipt")
class AiCandidateJobIdempotencyReceipt {

	@Id
	@Column(name = "ai_job_idempotency_receipt_id", nullable = false, updatable = false)
	private UUID aiJobIdempotencyReceiptId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
	private String requestFingerprint;

	@Column(name = "ai_job_id", nullable = false, updatable = false)
	private UUID aiJobId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected AiCandidateJobIdempotencyReceipt() {
	}

	private AiCandidateJobIdempotencyReceipt(UUID projectId, String idempotencyKey, String requestFingerprint,
			UUID aiJobId, Instant createdAt) {
		this.aiJobIdempotencyReceiptId = UUID.randomUUID();
		this.projectId = projectId;
		this.idempotencyKey = idempotencyKey;
		this.requestFingerprint = requestFingerprint;
		this.aiJobId = aiJobId;
		this.createdAt = createdAt;
	}

	static AiCandidateJobIdempotencyReceipt record(UUID projectId, String idempotencyKey, String requestFingerprint,
			UUID aiJobId, Instant createdAt) {
		return new AiCandidateJobIdempotencyReceipt(projectId, idempotencyKey, requestFingerprint, aiJobId, createdAt);
	}

	UUID getAiJobId() {
		return this.aiJobId;
	}

	String getRequestFingerprint() {
		return this.requestFingerprint;
	}

}
