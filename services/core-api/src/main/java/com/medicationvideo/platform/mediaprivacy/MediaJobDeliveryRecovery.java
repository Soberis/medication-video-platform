package com.medicationvideo.platform.mediaprivacy;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "media_job_delivery_recovery")
class MediaJobDeliveryRecovery {

	@Id
	@Column(name = "recovery_id", nullable = false, updatable = false)
	private UUID recoveryId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "media_job_id", nullable = false, updatable = false)
	private UUID mediaJobId;

	@Column(name = "source_message_id", nullable = false, updatable = false)
	private UUID sourceMessageId;

	@Column(name = "source_attempt", nullable = false, updatable = false)
	private int sourceAttempt;

	@Column(name = "new_attempt", nullable = false, updatable = false)
	private int newAttempt;

	@Column(name = "operator_actor_id", nullable = false, updatable = false, length = 128)
	private String operatorActorId;

	@Enumerated(EnumType.STRING)
	@Column(name = "recovery_reason", nullable = false, updatable = false, length = 64)
	private MediaJobDeliveryRecoveryReason recoveryReason;

	@Column(name = "change_reference", nullable = false, updatable = false, length = 128)
	private String changeReference;

	@Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
	private String requestFingerprint;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected MediaJobDeliveryRecovery() {
	}

	private MediaJobDeliveryRecovery(UUID projectId, UUID mediaJobId, MediaJobDeliveryRecoveryRequest request,
			int newAttempt, String actorId, String idempotencyKey, String requestFingerprint, Instant createdAt) {
		this.recoveryId = UUID.randomUUID();
		this.projectId = projectId;
		this.mediaJobId = mediaJobId;
		this.sourceMessageId = request.sourceMessageId();
		this.sourceAttempt = request.sourceAttempt();
		this.newAttempt = newAttempt;
		this.operatorActorId = actorId;
		this.recoveryReason = request.recoveryReason();
		this.changeReference = request.changeReference();
		this.idempotencyKey = idempotencyKey;
		this.requestFingerprint = requestFingerprint;
		this.createdAt = createdAt;
	}

	static MediaJobDeliveryRecovery create(UUID projectId, UUID mediaJobId, MediaJobDeliveryRecoveryRequest request,
			int newAttempt, String actorId, String idempotencyKey, String requestFingerprint, Instant createdAt) {
		return new MediaJobDeliveryRecovery(projectId, mediaJobId, request, newAttempt, actorId, idempotencyKey,
				requestFingerprint, createdAt);
	}

	UUID getRecoveryId() {
		return this.recoveryId;
	}

	UUID getMediaJobId() {
		return this.mediaJobId;
	}

	UUID getSourceMessageId() {
		return this.sourceMessageId;
	}

	int getSourceAttempt() {
		return this.sourceAttempt;
	}

	int getNewAttempt() {
		return this.newAttempt;
	}

	String getRequestFingerprint() {
		return this.requestFingerprint;
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}

}
