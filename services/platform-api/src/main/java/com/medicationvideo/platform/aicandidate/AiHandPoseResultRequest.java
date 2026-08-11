package com.medicationvideo.platform.aicandidate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.JsonNode;

/**
 * Versioned, complete-result envelope. A successful empty candidate list is only valid
 * when the Worker explicitly reports NO_HAND_DETECTED.
 */
record AiHandPoseResultRequest(@NotBlank @Pattern(regexp = "^ai-hand-pose-result\\.v2$") String schemaVersion,
		@NotNull UUID messageId, @NotNull UUID jobId, @NotNull @Min(1) Integer attempt,
		@NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43,128}$") String leaseToken,
		@NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String inputSha256,
		@NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String modelSha256,
		@NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String profileSha256, @NotNull Instant startedAt,
		@NotNull Instant completedAt, @NotNull AiHandPoseRunOutcome outcome, @NotNull Boolean analysisComplete,
		@NotNull HandPoseResult handResult, @NotNull @Min(0) Long totalVideoFrames,
		@NotNull @Min(0) Long inferenceFrameCount, @NotNull @Min(0) Long framesWithHand,
		@NotNull @Min(0) Long framesWithoutHand, @NotNull @Min(0) Long leftHandDetectionCount,
		@NotNull @Min(0) Long rightHandDetectionCount, @NotNull @Min(0) Long invalidLandmarkCount,
		@NotNull @Min(0) Long processingDurationMs, @Size(max = 128) String failureReason,
		@NotNull @Size(max = 20000) List<@Valid AiHandPoseCandidatePayload> candidates) {

	@AssertTrue(message = "completed_at must not precede started_at.")
	boolean validTimeRange() {
		return this.startedAt != null && this.completedAt != null && !this.completedAt.isBefore(this.startedAt);
	}

	@AssertTrue(message = "run summary frame counts must describe the sampled video.")
	boolean validFrameSummary() {
		return this.totalVideoFrames != null && this.inferenceFrameCount != null && this.framesWithHand != null
				&& this.framesWithoutHand != null && this.totalVideoFrames >= this.inferenceFrameCount
				&& this.framesWithHand + this.framesWithoutHand == this.inferenceFrameCount;
	}

	@AssertTrue(message = "hand result must match the explicit terminal outcome and candidates.")
	boolean validOutcome() {
		if (this.outcome == null || this.analysisComplete == null || this.handResult == null
				|| this.candidates == null) {
			return false;
		}
		if (this.outcome == AiHandPoseRunOutcome.SUCCEEDED) {
			if (!this.analysisComplete || (this.handResult != HandPoseResult.HAND_DETECTED
					&& this.handResult != HandPoseResult.NO_HAND_DETECTED)) {
				return false;
			}
			if (this.handResult == HandPoseResult.NO_HAND_DETECTED) {
				return this.candidates.isEmpty() && this.framesWithHand == 0
						&& this.framesWithoutHand.equals(this.inferenceFrameCount);
			}
			return !this.candidates.isEmpty() && this.framesWithHand > 0;
		}
		return !this.analysisComplete && this.handResult == HandPoseResult.UNKNOWN && this.candidates.isEmpty()
				&& this.failureReason != null && !this.failureReason.isBlank();
	}
}

record AiHandPoseCandidatePayload(@Min(0) long frameTimestampMs, @Min(1) int imageWidth, @Min(1) int imageHeight,
		@Min(0) @org.hibernate.validator.constraints.Range(max = 1) int handIndex,
		@NotBlank @Pattern(regexp = "^(LEFT|RIGHT|UNKNOWN)$") String handedness,
		@org.hibernate.validator.constraints.Range(min = 0, max = 1) double handednessScore,
		@NotNull JsonNode boundingBox, @NotNull JsonNode landmarks,
		@Min(0) @org.hibernate.validator.constraints.Range(max = 21) int validLandmarkCount) {

	@AssertTrue(message = "bounding_box must be an object and landmarks must contain 21 points.")
	boolean validGeometry() {
		return this.boundingBox != null && this.boundingBox.isObject() && this.landmarks != null
				&& this.landmarks.isArray() && this.landmarks.size() == 21;
	}
}
