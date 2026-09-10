package com.medicationvideo.platform.aicandidate;

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
@Table(name = "ai_job")
class AiCandidateJob {

	@Id
	@Column(name = "ai_job_id", nullable = false, updatable = false)
	private UUID aiJobId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "clip_asset_id", nullable = false, updatable = false)
	private UUID clipAssetId;

	@Column(name = "algorithm_key", nullable = false, length = 64, updatable = false)
	private String algorithmKey;

	@Column(name = "algorithm_version", nullable = false, length = 32, updatable = false)
	private String algorithmVersion;

	@Column(name = "model_sha256", nullable = false, length = 64, updatable = false)
	private String modelSha256;

	@Column(name = "input_sha256", nullable = false, length = 64, updatable = false)
	private String inputSha256;

	@Column(name = "input_size_bytes", nullable = false, updatable = false)
	private long inputSizeBytes;

	@Column(name = "content_type", nullable = false, length = 128, updatable = false)
	private String contentType;

	@Column(name = "sampling_fps", nullable = false, updatable = false)
	private int samplingFps;

	@Column(name = "profile_sha256", nullable = false, length = 64, updatable = false)
	private String profileSha256;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private AiCandidateJobStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "hand_result", nullable = false, length = 32)
	private HandPoseResult handResult;

	@Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
	private String requestFingerprint;

	@Column(name = "requested_by", nullable = false, length = 128, updatable = false)
	private String requestedBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected AiCandidateJob() {
	}

	private AiCandidateJob(UUID projectId, UUID clipAssetId, String modelSha256, String inputSha256,
			long inputSizeBytes, String contentType, String idempotencyKey, String requestFingerprint,
			String requestedBy, Instant createdAt) {
		this.aiJobId = UUID.randomUUID();
		this.projectId = projectId;
		this.clipAssetId = clipAssetId;
		this.algorithmKey = HandPoseProfile.ALGORITHM_KEY;
		this.algorithmVersion = HandPoseProfile.ALGORITHM_VERSION;
		this.modelSha256 = modelSha256;
		this.inputSha256 = inputSha256;
		this.inputSizeBytes = inputSizeBytes;
		this.contentType = contentType;
		this.samplingFps = HandPoseProfile.SAMPLING_FPS;
		this.profileSha256 = HandPoseProfile.PROFILE_SHA256;
		this.status = AiCandidateJobStatus.QUEUED;
		this.handResult = HandPoseResult.UNKNOWN;
		this.idempotencyKey = idempotencyKey;
		this.requestFingerprint = requestFingerprint;
		this.requestedBy = requestedBy;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	static AiCandidateJob queue(UUID projectId, UUID clipAssetId, String modelSha256, String inputSha256,
			long inputSizeBytes, String contentType, String idempotencyKey, String requestFingerprint,
			String requestedBy, Instant createdAt) {
		return new AiCandidateJob(projectId, clipAssetId, modelSha256, inputSha256, inputSizeBytes, contentType,
				idempotencyKey, requestFingerprint, requestedBy, createdAt);
	}

	static AiCandidateJob queueAction(UUID projectId, UUID clipAssetId, String modelSha256, String inputSha256,
			long inputSizeBytes, String contentType, String idempotencyKey, String requestFingerprint,
			String requestedBy, Instant createdAt) {
		var job = new AiCandidateJob();
		job.aiJobId = UUID.randomUUID();
		job.projectId = projectId;
		job.clipAssetId = clipAssetId;
		job.algorithmKey = "QWEN3_VL_ACTION";
		job.algorithmVersion = "v1";
		job.modelSha256 = modelSha256 != null && !modelSha256.isBlank() ? modelSha256 : "qwen3-vl-8b";
		job.inputSha256 = inputSha256;
		job.inputSizeBytes = inputSizeBytes;
		job.contentType = contentType;
		job.samplingFps = 1;
		job.profileSha256 = "qwen3-vl-8b-bf16";
		job.status = AiCandidateJobStatus.QUEUED;
		job.handResult = HandPoseResult.UNKNOWN;
		job.idempotencyKey = idempotencyKey;
		job.requestFingerprint = requestFingerprint;
		job.requestedBy = requestedBy;
		job.createdAt = createdAt;
		job.updatedAt = createdAt;
		return job;
	}

	UUID getAiJobId() {
		return this.aiJobId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	UUID getClipAssetId() {
		return this.clipAssetId;
	}

	String getAlgorithmKey() {
		return this.algorithmKey;
	}

	String getAlgorithmVersion() {
		return this.algorithmVersion;
	}

	String getModelSha256() {
		return this.modelSha256;
	}

	String getInputSha256() {
		return this.inputSha256;
	}

	long getInputSizeBytes() {
		return this.inputSizeBytes;
	}

	String getContentType() {
		return this.contentType;
	}

	int getSamplingFps() {
		return this.samplingFps;
	}

	String getProfileSha256() {
		return this.profileSha256;
	}

	AiCandidateJobStatus getStatus() {
		return this.status;
	}

	HandPoseResult getHandResult() {
		return this.handResult;
	}

	String getRequestFingerprint() {
		return this.requestFingerprint;
	}

	String getIdempotencyKey() {
		return this.idempotencyKey;
	}

	String getRequestedBy() {
		return this.requestedBy;
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}

	Instant getUpdatedAt() {
		return this.updatedAt;
	}

	void start(Instant now) {
		if (this.status == AiCandidateJobStatus.QUEUED) {
			this.status = AiCandidateJobStatus.RUNNING;
			this.updatedAt = now;
		}
	}

	void requeueAfterLostAttempt(Instant now) {
		if (this.status != AiCandidateJobStatus.RUNNING) {
			throw new HandPoseJobException("STALE_ATTEMPT", "The AI job is no longer running.", 409);
		}
		this.status = AiCandidateJobStatus.QUEUED;
		this.handResult = HandPoseResult.UNKNOWN;
		this.updatedAt = now;
	}

	void requeueForManualRetry(Instant now) {
		if (this.status != AiCandidateJobStatus.FAILED) {
			throw new HandPoseJobException("AI_HAND_POSE_RETRY_STATE_CONFLICT",
					"Only a failed AI job can be manually retried.", 409);
		}
		this.status = AiCandidateJobStatus.QUEUED;
		this.handResult = HandPoseResult.UNKNOWN;
		this.updatedAt = now;
	}

	void succeed(Instant now, HandPoseResult handResult) {
		this.status = AiCandidateJobStatus.SUCCEEDED;
		this.handResult = handResult;
		this.updatedAt = now;
	}

	void fail(Instant now) {
		this.status = AiCandidateJobStatus.FAILED;
		this.handResult = HandPoseResult.UNKNOWN;
		this.updatedAt = now;
	}

}
