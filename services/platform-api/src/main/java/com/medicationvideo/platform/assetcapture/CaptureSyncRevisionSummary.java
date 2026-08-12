package com.medicationvideo.platform.assetcapture;

import java.time.Instant;

/**
 * Facts verified by Core API before an immutable synchronization revision is accepted.
 */
public record CaptureSyncRevisionSummary(Integer referenceCameraNumber, String referenceClockSource,
		String timeBasisSource, Instant captureOriginUtc, Long timePrecisionNs, long syncFrameCount,
		long presentFrameCount, long missingFrameCount, long invalidFrameCount, long cameraCountReceived,
		Long maxSyncErrorNs, Long p95SyncErrorNs, long processingDurationMs, String logicalContentSha256) {

	public CaptureSyncRevisionSummary {
		if (referenceCameraNumber != null && (referenceCameraNumber < 1 || referenceCameraNumber > 64)) {
			throw new IllegalArgumentException("referenceCameraNumber must be between 1 and 64.");
		}
		if (referenceClockSource == null || referenceClockSource.isBlank() || timeBasisSource == null
				|| timeBasisSource.isBlank()) {
			throw new IllegalArgumentException("Reference clock and time basis sources must be explicit.");
		}
		if (timePrecisionNs != null && timePrecisionNs <= 0) {
			throw new IllegalArgumentException("timePrecisionNs must be positive when present.");
		}
		if (syncFrameCount < 0 || presentFrameCount < 0 || missingFrameCount < 0 || invalidFrameCount < 0
				|| cameraCountReceived < 0 || processingDurationMs < 0) {
			throw new IllegalArgumentException("Revision counts and duration must not be negative.");
		}
		if (maxSyncErrorNs != null && maxSyncErrorNs < 0 || p95SyncErrorNs != null && p95SyncErrorNs < 0) {
			throw new IllegalArgumentException("Sync error summaries must not be negative.");
		}
		if (logicalContentSha256 == null || !logicalContentSha256.matches("[a-f0-9]{64}")) {
			throw new IllegalArgumentException("logicalContentSha256 must be a lowercase SHA-256 digest.");
		}
	}

}
