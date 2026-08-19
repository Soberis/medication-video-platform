package com.medicationvideo.platform.assetcapture;

import java.util.List;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/** Frozen source facts used to map one CameraStream. */
public record SyncStream(int cameraNumber, UUID cameraStreamId, UUID clipAssetId, String rawVideoSha256,
		UUID mediaReportId, String timeBaseDigest, StreamTimeMapping timeMapping, List<SourceFrame> sourceFrames) {

	public SyncStream {
		if (cameraNumber < 1 || cameraNumber > 64) {
			throw new IllegalArgumentException("cameraNumber must be between 1 and 64.");
		}
		Objects.requireNonNull(cameraStreamId, "cameraStreamId");
		Objects.requireNonNull(clipAssetId, "clipAssetId");
		if (rawVideoSha256 == null || !rawVideoSha256.matches("[a-f0-9]{64}")) {
			throw new IllegalArgumentException("rawVideoSha256 must be a lowercase SHA-256 digest.");
		}
		Objects.requireNonNull(mediaReportId, "mediaReportId");
		if (timeBaseDigest == null || !timeBaseDigest.matches("[a-f0-9]{64}")) {
			throw new IllegalArgumentException("timeBaseDigest must be a lowercase SHA-256 digest.");
		}
		Objects.requireNonNull(timeMapping, "timeMapping");
		Objects.requireNonNull(sourceFrames, "sourceFrames");
		if (sourceFrames.isEmpty()) {
			throw new IllegalArgumentException("A sync stream must contain source PTS facts.");
		}
		sourceFrames = sourceFrames.stream()
			.sorted(Comparator.comparingLong(SourceFrame::sourcePtsNs)
				.thenComparingLong(SourceFrame::sourceFrameIndex)
				.thenComparingInt(SourceFrame::sourcePtsOrdinal))
			.toList();
	}

}
