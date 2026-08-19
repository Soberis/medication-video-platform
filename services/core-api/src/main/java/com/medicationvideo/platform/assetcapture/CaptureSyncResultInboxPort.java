package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CaptureSyncResultInboxPort {

	Optional<CaptureSyncInboxReceipt> find(UUID messageId);

	CaptureSyncInboxReceipt record(UUID messageId, UUID jobId, int attempt, String resultFingerprint,
			String resultStatus, Instant processedAt);

}
