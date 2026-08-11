package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

public record VideoSummary(UUID videoId, String displayName, Integer cameraNumber, Instant capturedAt,
		Instant receivedAt, String sourceName, String mediaStatus, String handStatus, String handResult,
		boolean handRetryable, String annotationStatus, String cleaningStatus, String retentionStatus) {
}
