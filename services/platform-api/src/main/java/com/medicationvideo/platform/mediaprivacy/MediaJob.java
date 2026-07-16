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

/**
 * Media job aggregate root. A durable Outbox entry moves a new job from REQUESTED to QUEUED in
 * the same transaction. Worker-driven RUNNING and terminal transitions require the current
 * attempt and lease token.
 */
@Entity
@Table(name = "media_job")
class MediaJob {

	@Id
	@Column(name = "media_job_id", nullable = false, updatable = false)
	private UUID mediaJobId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "clip_asset_id", nullable = false, updatable = false)
	private UUID clipAssetId;

	@Enumerated(EnumType.STRING)
	@Column(name = "job_type", nullable = false, length = 32, updatable = false)
	private MediaJobType jobType;

	@Column(name = "input_digest", nullable = false, length = 64, updatable = false)
	private String inputDigest;

	@Column(name = "input_size_bytes", nullable = false, updatable = false)
	private long inputSizeBytes;

	@Column(name = "content_type", nullable = false, length = 128, updatable = false)
	private String contentType;

	@Column(name = "probe_profile_key", nullable = false, length = 64, updatable = false)
	private String probeProfileKey;

	@Column(name = "recipe_digest", nullable = false, length = 64, updatable = false)
	private String recipeDigest;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private MediaJobStatus status;

	@Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
	private String requestFingerprint;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected MediaJob() {
	}

	private MediaJob(
			UUID mediaJobId,
			UUID projectId,
			UUID clipAssetId,
			MediaJobType jobType,
			String inputDigest,
			long inputSizeBytes,
			String contentType,
			String probeProfileKey,
			String recipeDigest,
			String idempotencyKey,
			String requestFingerprint,
			Instant createdAt) {
		this.mediaJobId = mediaJobId;
		this.projectId = projectId;
		this.clipAssetId = clipAssetId;
		this.jobType = jobType;
		this.inputDigest = inputDigest;
		this.inputSizeBytes = inputSizeBytes;
		this.contentType = contentType;
		this.probeProfileKey = probeProfileKey;
		this.recipeDigest = recipeDigest;
		this.status = MediaJobStatus.REQUESTED;
		this.idempotencyKey = idempotencyKey;
		this.requestFingerprint = requestFingerprint;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	static MediaJob request(
			UUID projectId,
			UUID clipAssetId,
			MediaJobType jobType,
			String inputDigest,
			long inputSizeBytes,
			String contentType,
			String probeProfileKey,
			String recipeDigest,
			String idempotencyKey,
			String requestFingerprint,
			Instant createdAt) {
		return new MediaJob(
				UUID.randomUUID(),
				projectId,
				clipAssetId,
				jobType,
				inputDigest,
				inputSizeBytes,
				contentType,
				probeProfileKey,
				recipeDigest,
				idempotencyKey,
				requestFingerprint,
				createdAt);
	}

	void queue(Instant changedAt) {
		if (this.status != MediaJobStatus.REQUESTED) {
			throw new IllegalStateException("Only a requested media job can be queued.");
		}
		this.status = MediaJobStatus.QUEUED;
		this.updatedAt = changedAt;
	}

	void start(Instant changedAt) {
		if (this.status == MediaJobStatus.RUNNING) {
			return;
		}
		if (this.status != MediaJobStatus.QUEUED) {
			throw new MediaJobExecutionException(
					"STALE_ATTEMPT", "The media job is not queued for this attempt.", false);
		}
		this.status = MediaJobStatus.RUNNING;
		this.updatedAt = changedAt;
	}

	void succeed(Instant changedAt) {
		transitionFromRunning(MediaJobStatus.SUCCEEDED, changedAt);
	}

	void fail(Instant changedAt) {
		transitionFromRunning(MediaJobStatus.FAILED, changedAt);
	}

	private void transitionFromRunning(MediaJobStatus target, Instant changedAt) {
		if (this.status != MediaJobStatus.RUNNING) {
			throw new MediaJobExecutionException(
					"STALE_ATTEMPT", "The media job is no longer running.", false);
		}
		this.status = target;
		this.updatedAt = changedAt;
	}

	UUID getMediaJobId() {
		return this.mediaJobId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	UUID getClipAssetId() {
		return this.clipAssetId;
	}

	MediaJobType getJobType() {
		return this.jobType;
	}

	String getInputDigest() {
		return this.inputDigest;
	}

	long getInputSizeBytes() {
		return this.inputSizeBytes;
	}

	String getContentType() {
		return this.contentType;
	}

	String getProbeProfileKey() {
		return this.probeProfileKey;
	}

	String getRecipeDigest() {
		return this.recipeDigest;
	}

	MediaJobStatus getStatus() {
		return this.status;
	}

	String getIdempotencyKey() {
		return this.idempotencyKey;
	}

	String getRequestFingerprint() {
		return this.requestFingerprint;
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}

	Instant getUpdatedAt() {
		return this.updatedAt;
	}
}
