package com.medicationvideo.platform.mediaprivacy;

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
}
