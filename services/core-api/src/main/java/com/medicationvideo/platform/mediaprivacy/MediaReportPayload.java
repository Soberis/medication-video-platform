package com.medicationvideo.platform.mediaprivacy;

import java.util.HashSet;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

record MediaReportPayload(@NotBlank @Size(max = 64) String containerFormat, @NotNull @Min(0) Long durationMicroseconds,
		Long containerStartTimeMicroseconds, @NotNull @Min(1) Integer videoStreamCount,
		@NotNull @Min(0) Integer primaryVideoStreamIndex, @NotBlank @Size(max = 64) String videoCodec,
		@Size(max = 64) String videoProfile, @NotNull @Min(1) Integer width, @NotNull @Min(1) Integer height,
		@Size(max = 64) String pixelFormat, @Size(max = 64) String colorSpace, @Min(0) Long primaryVideoBitrateBps,
		@JsonProperty("has_b_frames") Boolean hasBFrames, Long streamStartTimeMicroseconds,
		@NotNull @Min(1) Integer timeBaseNumerator, @NotNull @Min(1) Integer timeBaseDenominator, Long firstPts,
		Long lastPts, @Min(0) Integer nominalFrameRateNumerator, @Min(1) Integer nominalFrameRateDenominator,
		@Min(0) Integer averageFrameRateNumerator, @Min(1) Integer averageFrameRateDenominator, @Min(0) Long frameCount,
		@NotNull Boolean variableFrameRate, @NotNull @Min(0) Integer audioStreamCount,
		@Min(1) Integer sampleAspectRatioNumerator, @Min(1) Integer sampleAspectRatioDenominator,
		@NotNull @AssertTrue Boolean decodeVerified, @NotNull @AssertTrue Boolean inputDigestVerified,
		@NotNull @Size(max = 6) List<@NotNull MediaProbeWarningCode> warningCodes) {

	MediaReportPayload {
		warningCodes = warningCodes == null ? null : List.copyOf(warningCodes);
	}

	@AssertTrue(message = "Rational media fields must provide numerator and denominator together.")
	boolean validRationalPairs() {
		return paired(this.nominalFrameRateNumerator, this.nominalFrameRateDenominator)
				&& paired(this.averageFrameRateNumerator, this.averageFrameRateDenominator)
				&& paired(this.sampleAspectRatioNumerator, this.sampleAspectRatioDenominator);
	}

	@AssertTrue(message = "warning_codes must not contain duplicates.")
	boolean uniqueWarnings() {
		return this.warningCodes == null || new HashSet<>(this.warningCodes).size() == this.warningCodes.size();
	}

	@AssertTrue(message = "primary_video_stream_index must identify an existing video stream.")
	boolean primaryVideoStreamExists() {
		return this.videoStreamCount == null || this.primaryVideoStreamIndex == null
				|| this.primaryVideoStreamIndex < this.videoStreamCount;
	}

	@AssertTrue(message = "last_pts must not precede first_pts.")
	boolean validPtsRange() {
		return this.firstPts == null || this.lastPts == null || this.lastPts >= this.firstPts;
	}

	private static boolean paired(Object numerator, Object denominator) {
		return (numerator == null) == (denominator == null);
	}
}
