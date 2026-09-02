package com.medicationvideo.platform.aicandidate;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
		var countsArePresent = this.totalVideoFrames != null && this.inferenceFrameCount != null
				&& this.framesWithHand != null && this.framesWithoutHand != null && this.totalVideoFrames >= 0
				&& this.inferenceFrameCount >= 0 && this.framesWithHand >= 0 && this.framesWithoutHand >= 0
				&& this.totalVideoFrames >= this.inferenceFrameCount
				&& sumAtMost(this.framesWithHand, this.framesWithoutHand, this.inferenceFrameCount);
		if (!countsArePresent) {
			return false;
		}
		if (this.outcome == AiHandPoseRunOutcome.SUCCEEDED) {
			return sumEquals(this.framesWithHand, this.framesWithoutHand, this.inferenceFrameCount)
					&& this.totalVideoFrames > 0 && this.inferenceFrameCount > 0;
		}
		return true;
	}

	@AssertTrue(message = "hand result must match the explicit terminal outcome and candidates.")
	boolean validOutcome() {
		if (this.outcome == null || this.analysisComplete == null || this.handResult == null
				|| this.candidates == null) {
			return false;
		}
		if (this.outcome == AiHandPoseRunOutcome.SUCCEEDED) {
			if (!this.analysisComplete
					|| (this.handResult != HandPoseResult.HAND_DETECTED
							&& this.handResult != HandPoseResult.NO_HAND_DETECTED)
					|| this.failureReason != null || !Long.valueOf(0).equals(this.invalidLandmarkCount)) {
				return false;
			}
			if (this.handResult == HandPoseResult.NO_HAND_DETECTED) {
				return this.candidates.isEmpty() && Long.valueOf(0).equals(this.framesWithHand)
						&& this.framesWithoutHand != null && this.framesWithoutHand.equals(this.inferenceFrameCount)
						&& Long.valueOf(0).equals(this.leftHandDetectionCount)
						&& Long.valueOf(0).equals(this.rightHandDetectionCount);
			}
			return validHandDetectedSummary();
		}
		return !this.analysisComplete && this.handResult == HandPoseResult.UNKNOWN && this.candidates.isEmpty()
				&& this.failureReason != null && !this.failureReason.isBlank() && validFailedCounts();
	}

	private boolean validFailedCounts() {
		return validLeftRightCounts() && this.invalidLandmarkCount != null && this.invalidLandmarkCount >= 0;
	}

	private boolean validLeftRightCounts() {
		if (this.framesWithHand == null || this.leftHandDetectionCount == null || this.rightHandDetectionCount == null
				|| this.framesWithHand < 0 || this.leftHandDetectionCount < 0 || this.rightHandDetectionCount < 0) {
			return false;
		}
		var limit = BigInteger.valueOf(this.framesWithHand).shiftLeft(1);
		var left = BigInteger.valueOf(this.leftHandDetectionCount);
		var right = BigInteger.valueOf(this.rightHandDetectionCount);
		return left.compareTo(limit) <= 0 && right.compareTo(limit) <= 0 && left.add(right).compareTo(limit) <= 0;
	}

	private boolean validHandDetectedSummary() {
		if (this.candidates.isEmpty() || this.framesWithHand == null || this.framesWithHand <= 0
				|| this.inferenceFrameCount == null || this.framesWithHand > this.inferenceFrameCount
				|| !this.candidates.stream().allMatch(candidate -> candidate != null && candidate.validGeometry())) {
			return false;
		}
		var candidateFrameCount = this.candidates.stream()
			.map(AiHandPoseCandidatePayload::frameTimestampMs)
			.distinct()
			.count();
		var uniqueHandSlots = this.candidates.stream()
			.map(candidate -> Map.entry(candidate.frameTimestampMs(), candidate.handIndex()))
			.distinct()
			.count();
		var maximumCandidateCount = BigInteger.valueOf(this.framesWithHand).shiftLeft(1);
		if (candidateFrameCount != this.framesWithHand
				|| BigInteger.valueOf(this.candidates.size()).compareTo(maximumCandidateCount) > 0
				|| uniqueHandSlots != this.candidates.size()) {
			return false;
		}
		var left = this.candidates.stream().filter(candidate -> "LEFT".equals(candidate.handedness())).count();
		var right = this.candidates.stream().filter(candidate -> "RIGHT".equals(candidate.handedness())).count();
		return Long.valueOf(left).equals(this.leftHandDetectionCount)
				&& Long.valueOf(right).equals(this.rightHandDetectionCount);
	}

	private static boolean sumAtMost(Long first, Long second, Long limit) {
		if (first == null || second == null || limit == null || first < 0 || second < 0 || limit < 0) {
			return false;
		}
		return BigInteger.valueOf(first).add(BigInteger.valueOf(second)).compareTo(BigInteger.valueOf(limit)) <= 0;
	}

	private static boolean sumEquals(Long first, Long second, Long expected) {
		if (first == null || second == null || expected == null || first < 0 || second < 0 || expected < 0) {
			return false;
		}
		return BigInteger.valueOf(first).add(BigInteger.valueOf(second)).equals(BigInteger.valueOf(expected));
	}
}

record AiHandPoseCandidatePayload(@NotNull @Min(0) Long frameTimestampMs, @NotNull @Min(1) Integer imageWidth,
		@NotNull @Min(1) Integer imageHeight,
		@NotNull @Min(0) @org.hibernate.validator.constraints.Range(max = 1) Integer handIndex,
		@NotBlank @Pattern(regexp = "^(LEFT|RIGHT|UNKNOWN)$") String handedness,
		@NotNull @org.hibernate.validator.constraints.Range(min = 0, max = 1) Double handednessScore,
		@NotNull JsonNode boundingBox, @NotNull JsonNode landmarks,
		@NotNull @Min(0) @org.hibernate.validator.constraints.Range(max = 21) Integer validLandmarkCount) {

	@AssertTrue(message = "bounding_box must be an object and landmarks must contain 21 points.")
	boolean validGeometry() {
		if (this.frameTimestampMs == null || this.imageWidth == null || this.imageHeight == null
				|| this.handIndex == null || this.handednessScore == null || this.validLandmarkCount == null
				|| this.boundingBox == null || !this.boundingBox.isObject() || this.landmarks == null
				|| !this.landmarks.isArray() || this.landmarks.size() != 21 || this.validLandmarkCount != 21
				|| !Double.isFinite(this.handednessScore) || this.handednessScore < 0 || this.handednessScore > 1) {
			return false;
		}
		if (!finite(this.boundingBox, "x") || !finite(this.boundingBox, "y") || !finite(this.boundingBox, "width")
				|| !finite(this.boundingBox, "height") || this.boundingBox.get("width").asDouble() <= 0
				|| this.boundingBox.get("height").asDouble() <= 0) {
			return false;
		}
		for (var point : this.landmarks) {
			if (point == null || !point.isObject() || !finite(point, "x") || !finite(point, "y")
					|| !finite(point, "z")) {
				return false;
			}
		}
		return true;
	}

	private static boolean finite(JsonNode object, String field) {
		var value = object.get(field);
		return value != null && value.isNumber() && Double.isFinite(value.asDouble());
	}
}
