package com.medicationvideo.platform.datasetexport;

import java.time.Instant;
import java.util.UUID;

public record DatasetExportInboxReceipt(UUID messageId, UUID exportId, int attempt, String resultFingerprint,
		String resultStatus, Instant processedAt) {
}
