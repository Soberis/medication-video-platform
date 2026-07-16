package com.medicationvideo.platform.mediaprivacy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

record MediaProbeResultRequest(
		@NotBlank @Pattern(regexp = "^media-probe-result\\.v1$") String schemaVersion,
		@NotNull UUID messageId,
		@NotNull UUID jobId,
		@NotNull @Min(1) Integer attempt,
		@NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43,128}$") String leaseToken,
		@NotNull MediaProbeResultStatus status,
		@NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String inputSha256,
		@NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String recipeDigest,
		@NotBlank @Pattern(regexp = "^sha256:[a-f0-9]{64}$") String softwareDigest,
		@NotNull Instant startedAt,
		@NotNull Instant completedAt,
		@Valid MediaReportPayload report,
		@Valid MediaProbeErrorPayload error) {

	@AssertTrue(message = "Successful results require a report and failed results require an error.")
	boolean validShape() {
		if (this.status == null) {
			return false;
		}
		return switch (this.status) {
			case SUCCEEDED -> this.report != null && this.error == null;
			case FAILED -> this.report == null && this.error != null;
		};
	}

	@AssertTrue(message = "completed_at must not precede started_at.")
	boolean validTimeRange() {
		return this.startedAt != null && this.completedAt != null
				&& !this.completedAt.isBefore(this.startedAt);
	}

	String fingerprint() {
		var canonical = String.join(
				"\n",
				String.valueOf(this.schemaVersion),
				String.valueOf(this.messageId),
				String.valueOf(this.jobId),
				String.valueOf(this.attempt),
				String.valueOf(this.status),
				String.valueOf(this.inputSha256),
				String.valueOf(this.recipeDigest),
				String.valueOf(this.softwareDigest),
				String.valueOf(this.startedAt),
				String.valueOf(this.completedAt),
				String.valueOf(this.report),
				String.valueOf(this.error));
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable in this Java runtime.", exception);
		}
	}
}
