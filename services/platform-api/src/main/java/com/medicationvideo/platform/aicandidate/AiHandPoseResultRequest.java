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

record AiHandPoseResultRequest(@NotBlank @Pattern(regexp = "^ai-hand-pose-result\\.v1$") String schemaVersion,
		@NotNull UUID messageId, @NotNull UUID jobId, @NotNull @Min(1) Integer attempt,
		@NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43,128}$") String leaseToken,
		@NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String inputSha256,
		@NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String modelSha256, @NotNull Instant startedAt,
		@NotNull Instant completedAt, @NotNull @Size(max = 20000) List<@Valid AiHandPoseCandidatePayload> candidates) {

	@AssertTrue(message = "completed_at must not precede started_at.")
	boolean validTimeRange() {
		return this.startedAt != null && this.completedAt != null && !this.completedAt.isBefore(this.startedAt);
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
