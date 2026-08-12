package com.medicationvideo.platform.assetcapture;

/** Core-API-verified per-stream summary used when accepting a revision. */
public record CaptureSyncStreamRevisionInput(ExpectedCamera expectedCamera, SyncStream stream,
		CaptureSyncStreamPresenceState presenceState, long presentFrameCount, long missingFrameCount,
		long invalidFrameCount, Long maxSyncErrorNs, Long p95SyncErrorNs) {

	public CaptureSyncStreamRevisionInput {
		if (expectedCamera == null || presenceState == null) {
			throw new IllegalArgumentException("Expected camera and stream presence state are required.");
		}
		if (presenceState == CaptureSyncStreamPresenceState.MISSING && stream != null
				|| presenceState != CaptureSyncStreamPresenceState.MISSING && stream == null) {
			throw new IllegalArgumentException("Stream facts must match the stream presence state.");
		}
	}

}
