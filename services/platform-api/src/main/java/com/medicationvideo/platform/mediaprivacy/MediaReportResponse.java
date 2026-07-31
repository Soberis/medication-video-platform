package com.medicationvideo.platform.mediaprivacy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

record MediaReportResponse(UUID mediaReportId, UUID mediaJobId, UUID projectId, UUID clipAssetId,
		String reportSchemaVersion, String inputDigest, String recipeDigest, String softwareDigest,
		String containerFormat, long durationMicroseconds, Long containerStartTimeMicroseconds, int videoStreamCount,
		int primaryVideoStreamIndex, String videoCodec, String videoProfile, int width, int height, String pixelFormat,
		String colorSpace, Long primaryVideoBitrateBps, @JsonProperty("has_b_frames") Boolean hasBFrames,
		Long streamStartTimeMicroseconds, int timeBaseNumerator, int timeBaseDenominator, Long firstPts, Long lastPts,
		Integer nominalFrameRateNumerator, Integer nominalFrameRateDenominator, Integer averageFrameRateNumerator,
		Integer averageFrameRateDenominator, Long frameCount, boolean variableFrameRate, int audioStreamCount,
		Integer sampleAspectRatioNumerator, Integer sampleAspectRatioDenominator, boolean decodeVerified,
		boolean inputDigestVerified, List<String> warningCodes, Instant createdAt) {

	static MediaReportResponse from(MediaReport report) {
		return new MediaReportResponse(report.getMediaReportId(), report.getMediaJobId(), report.getProjectId(),
				report.getClipAssetId(), report.getReportSchemaVersion(), report.getInputDigest(),
				report.getRecipeDigest(), report.getSoftwareDigest(), report.getContainerFormat(),
				report.getDurationMicroseconds(), report.getContainerStartTimeMicroseconds(),
				report.getVideoStreamCount(), report.getPrimaryVideoStreamIndex(), report.getVideoCodec(),
				report.getVideoProfile(), report.getWidth(), report.getHeight(), report.getPixelFormat(),
				report.getColorSpace(), report.getPrimaryVideoBitrateBps(), report.getHasBFrames(),
				report.getStreamStartTimeMicroseconds(), report.getTimeBaseNumerator(), report.getTimeBaseDenominator(),
				report.getFirstPts(), report.getLastPts(), report.getNominalFrameRateNumerator(),
				report.getNominalFrameRateDenominator(), report.getAverageFrameRateNumerator(),
				report.getAverageFrameRateDenominator(), report.getFrameCount(), report.isVariableFrameRate(),
				report.getAudioStreamCount(), report.getSampleAspectRatioNumerator(),
				report.getSampleAspectRatioDenominator(), report.isDecodeVerified(), report.isInputDigestVerified(),
				report.getWarningCodes(), report.getCreatedAt());
	}
}
