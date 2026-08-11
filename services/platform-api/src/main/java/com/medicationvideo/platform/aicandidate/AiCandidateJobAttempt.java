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

	@Column(name = "analysis_complete")
	private Boolean analysisComplete;

	@Enumerated(EnumType.STRING)
	@Column(name = "hand_result", length = 32)
	private HandPoseResult handResult;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "total_video_frames")
	private Long totalVideoFrames;

	@Column(name = "inference_frame_count")
	private Long inferenceFrameCount;

	@Column(name = "frames_with_hand")
	private Long framesWithHand;

	@Column(name = "frames_without_hand")
	private Long framesWithoutHand;

	@Column(name = "left_hand_detection_count")
	private Long leftHandDetectionCount;

	@Column(name = "right_hand_detection_count")
	private Long rightHandDetectionCount;

	@Column(name = "invalid_landmark_count")
	private Long invalidLandmarkCount;

	@Column(name = "processing_duration_ms")
	private Long processingDurationMs;

	@Column(name = "failure_reason", length = 128)
	private String failureReason;

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

	void recordRun(AiHandPoseResultRequest request) {
		this.analysisComplete = request.analysisComplete();
		this.handResult = request.handResult();
		this.startedAt = request.startedAt();
		this.completedAt = request.completedAt();
		this.totalVideoFrames = request.totalVideoFrames();
		this.inferenceFrameCount = request.inferenceFrameCount();
		this.framesWithHand = request.framesWithHand();
		this.framesWithoutHand = request.framesWithoutHand();
		this.leftHandDetectionCount = request.leftHandDetectionCount();
		this.rightHandDetectionCount = request.rightHandDetectionCount();
		this.invalidLandmarkCount = request.invalidLandmarkCount();
		this.processingDurationMs = request.processingDurationMs();
		this.failureReason = request.failureReason();
	}

	void fail(Instant now) {
		requireActiveLease(now);
		this.status = AiCandidateJobAttemptStatus.FAILED;
		this.updatedAt = now;
	}

	void markDeliveryMissing(Instant now) {
		if (this.status != AiCandidateJobAttemptStatus.QUEUED) {
			throw new HandPoseJobException("AI_HAND_POSE_DELIVERY_RECOVERY_STATE_CONFLICT",
					"Only a queued AI job attempt can be recovered after a missing delivery.", 409);
		}
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

	AiCandidateJobAttemptStatus getStatus() {
		return this.status;
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}

	Instant getLeaseUntil() {
		return this.leaseUntil;
	}

	boolean isAnalysisComplete() {
		return Boolean.TRUE.equals(this.analysisComplete);
	}

	String getHandResult() {
		return this.handResult == null ? null : this.handResult.name();
	}

	Instant getStartedAt() {
		return this.startedAt;
	}

	Instant getCompletedAt() {
		return this.completedAt;
	}

	Long getTotalVideoFrames() {
		return this.totalVideoFrames;
	}

	Long getInferenceFrameCount() {
		return this.inferenceFrameCount;
	}

	Long getFramesWithHand() {
		return this.framesWithHand;
	}

	Long getFramesWithoutHand() {
		return this.framesWithoutHand;
	}

	Long getLeftHandDetectionCount() {
		return this.leftHandDetectionCount;
	}

	Long getRightHandDetectionCount() {
		return this.rightHandDetectionCount;
	}

	Long getInvalidLandmarkCount() {
		return this.invalidLandmarkCount;
	}

	Long getProcessingDurationMs() {
		return this.processingDurationMs;
	}

	String getFailureReason() {
		return this.failureReason;
	}

	static AiCandidateJobAttempt queue(UUID aiJobId, int attempt, UUID messageId, String leaseTokenHash,
			Instant createdAt) {
		if (attempt < 1)
			throw new IllegalArgumentException("AI job attempt must be positive.");
		return new AiCandidateJobAttempt(aiJobId, attempt, messageId, leaseTokenHash, createdAt);
	}

}
