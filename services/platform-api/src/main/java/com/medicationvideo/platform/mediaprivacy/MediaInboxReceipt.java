package com.medicationvideo.platform.mediaprivacy;

import java.time.Instant;
import java.util.UUID;

public record MediaInboxReceipt(
		UUID messageId,
		UUID jobId,
		int attempt,
		String resultFingerprint,
		String resultStatus,
		Instant processedAt) {
}
