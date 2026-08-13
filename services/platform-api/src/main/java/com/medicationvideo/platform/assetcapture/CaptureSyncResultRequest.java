package com.medicationvideo.platform.assetcapture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

record CaptureSyncResultRequest(@NotBlank @Pattern(regexp = "^capture-sync-result\\.v1$") String schemaVersion,
		@NotNull UUID messageId, @NotNull UUID jobId, @NotNull @Min(1) Integer attempt,
		@NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43,128}$") String leaseToken,
		@NotNull CaptureSyncResultStatus status,
		@NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String inputManifestSha256,
		@NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String syncProfileSha256,
		@NotBlank @Pattern(regexp = "^sha256:[a-f0-9]{64}$") String softwareDigest, @NotNull Instant startedAt,
		@NotNull Instant completedAt, UUID supersedesSyncRevisionId, @Valid CaptureSyncSuccessPayload success,
		@Valid CaptureSyncErrorPayload error) {

	@AssertTrue(message = "Successful results require a success payload and failed results require an error.")
	boolean validShape() {
		if (this.status == null) {
			return false;
		}
		return switch (this.status) {
			case SUCCEEDED -> this.success != null && this.error == null;
			case FAILED -> this.success == null && this.error != null;
		};
	}

	@AssertTrue(message = "completed_at must not precede started_at.")
	boolean validTimeRange() {
		return this.startedAt != null && this.completedAt != null && !this.completedAt.isBefore(this.startedAt);
	}

	String fingerprint() {
		var canonical = String.join("\n", String.valueOf(this.schemaVersion), String.valueOf(this.messageId),
				String.valueOf(this.jobId), String.valueOf(this.attempt), String.valueOf(this.status),
				String.valueOf(this.inputManifestSha256), String.valueOf(this.syncProfileSha256),
				String.valueOf(this.softwareDigest), String.valueOf(this.startedAt), String.valueOf(this.completedAt),
				String.valueOf(this.supersedesSyncRevisionId), String.valueOf(this.success),
				String.valueOf(this.error));
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable in the Java runtime.", exception);
		}
	}
}

record CaptureSyncSuccessPayload(@Valid @NotNull CaptureSyncRevisionSummary summary,
		@Valid @NotNull List<CaptureSyncStreamRevisionPayload> streams,
		@Valid @NotNull CaptureSyncArtifactPayload artifact) {
}

record CaptureSyncStreamRevisionPayload(@NotNull Integer cameraNumber, @NotNull UUID cameraSetSnapshotMemberId,
		@NotNull CaptureSyncStreamPresenceState streamPresenceState, UUID cameraStreamId, UUID clipAssetId,
		@Pattern(regexp = "^[a-f0-9]{64}$") String sourceInputSha256, UUID sourceMediaReportId,
		@Pattern(regexp = "^[a-f0-9]{64}$") String sourceTimeBaseDigest, Long anchorSourcePtsNs,
		Long anchorCaptureTimestampNs, String driftPpm, @NotNull @Min(0) Long presentFrameCount,
		@NotNull @Min(0) Long missingFrameCount, @NotNull @Min(0) Long invalidFrameCount, Long maxSyncErrorNs,
		Long p95SyncErrorNs) {
}

record CaptureSyncArtifactPayload(@NotBlank String storageBucket, @NotBlank String objectKey,
		@NotNull @Min(1) Long sizeBytes, @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String sha256,
		@NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String logicalContentSha256,
		@NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String artifactManifestSha256,
		@NotNull Map<String, Object> artifactManifest) {
}

record CaptureSyncErrorPayload(@NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$") String errorClass,
		@NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$") String errorCode, @NotBlank String safeErrorMessage,
		@NotNull Boolean retryable) {
}

record CaptureSyncResultAcceptedResponse(UUID messageId, UUID jobId, int attempt, CaptureSyncResultStatus status,
		Instant processedAt, UUID captureSyncRevisionId) {
}

record CaptureSyncLeaseRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$") String workerId) {
}
