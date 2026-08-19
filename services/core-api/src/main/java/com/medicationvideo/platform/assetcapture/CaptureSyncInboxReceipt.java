package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

public record CaptureSyncInboxReceipt(UUID messageId, UUID jobId, int attempt, String resultFingerprint,
		String resultStatus, Instant processedAt) {
}
