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
@Table(name = "media_job_manual_replay")
class MediaJobManualReplay {

	@Id
	@Column(name = "replay_id", nullable = false, updatable = false)
	private UUID replayId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "media_job_id", nullable = false, updatable = false)
	private UUID mediaJobId;

	@Column(name = "dead_letter_id", nullable = false, updatable = false)
	private UUID deadLetterId;

	@Column(name = "source_message_id", nullable = false, updatable = false)
	private UUID sourceMessageId;

	@Column(name = "source_attempt", nullable = false, updatable = false)
	private int sourceAttempt;

	@Column(name = "new_attempt", nullable = false, updatable = false)
	private int newAttempt;

	@Enumerated(EnumType.STRING)
	@Column(name = "dead_letter_reason", nullable = false, length = 64, updatable = false)
	private MediaDeadLetterReason deadLetterReason;

	@Column(name = "body_sha256", nullable = false, length = 64, updatable = false)
	private String bodySha256;

	@Column(name = "operator_actor_id", nullable = false, length = 128, updatable = false)
	private String operatorActorId;

	@Enumerated(EnumType.STRING)
	@Column(name = "operator_reason_code", nullable = false, length = 64, updatable = false)
	private MediaJobReplayReason operatorReasonCode;

	@Column(name = "change_reference", nullable = false, length = 128, updatable = false)
	private String changeReference;

	@Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
	private String requestFingerprint;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected MediaJobManualReplay() {
	}

	private MediaJobManualReplay(
			UUID projectId,
			UUID mediaJobId,
			MediaJobReplayRequest request,
			int newAttempt,
			String actorId,
			String idempotencyKey,
			String requestFingerprint,
			Instant createdAt) {
		this.replayId = UUID.randomUUID();
		this.projectId = projectId;
		this.mediaJobId = mediaJobId;
		this.deadLetterId = request.deadLetterId();
		this.sourceMessageId = request.sourceMessageId();
		this.sourceAttempt = request.sourceAttempt();
		this.newAttempt = newAttempt;
		this.deadLetterReason = request.deadLetterReason();
		this.bodySha256 = request.bodySha256();
		this.operatorActorId = actorId;
		this.operatorReasonCode = request.operatorReasonCode();
		this.changeReference = request.changeReference();
		this.idempotencyKey = idempotencyKey;
		this.requestFingerprint = requestFingerprint;
		this.createdAt = createdAt;
	}

	static MediaJobManualReplay create(
			UUID projectId,
			UUID mediaJobId,
			MediaJobReplayRequest request,
			int newAttempt,
			String actorId,
			String idempotencyKey,
			String requestFingerprint,
			Instant createdAt) {
		return new MediaJobManualReplay(
				projectId,
				mediaJobId,
				request,
				newAttempt,
				actorId,
				idempotencyKey,
				requestFingerprint,
				createdAt);
	}

	UUID getReplayId() {
		return this.replayId;
	}

	UUID getMediaJobId() {
		return this.mediaJobId;
	}

	UUID getDeadLetterId() {
		return this.deadLetterId;
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
