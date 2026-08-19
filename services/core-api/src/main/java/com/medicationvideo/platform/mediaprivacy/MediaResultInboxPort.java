package com.medicationvideo.platform.mediaprivacy;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface MediaResultInboxPort {

	Optional<MediaInboxReceipt> find(UUID messageId);

	MediaInboxReceipt record(UUID messageId, UUID jobId, int attempt, String resultFingerprint, String resultStatus,
			Instant processedAt);

}
