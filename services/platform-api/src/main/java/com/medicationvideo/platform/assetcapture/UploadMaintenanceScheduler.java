package com.medicationvideo.platform.assetcapture;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "platform.upload.cleanup-enabled", havingValue = "true")
class UploadMaintenanceScheduler {

	private final UploadBatchTerminationService terminationService;
	private final UploadCleanupProcessor cleanupProcessor;
	private final int expiryBatchSize;

	UploadMaintenanceScheduler(
			UploadBatchTerminationService terminationService,
			UploadCleanupProcessor cleanupProcessor,
			@Value("${platform.upload.expiry-batch-size:20}") int expiryBatchSize) {
		if (expiryBatchSize < 1 || expiryBatchSize > 100) {
			throw new IllegalArgumentException("Upload expiry batch size must be between 1 and 100.");
		}
		this.terminationService = terminationService;
		this.cleanupProcessor = cleanupProcessor;
		this.expiryBatchSize = expiryBatchSize;
	}

	@Scheduled(fixedDelayString = "${platform.upload.cleanup-poll-delay:PT30S}")
	void maintain() {
		for (var index = 0; index < this.expiryBatchSize; index++) {
			if (!this.terminationService.expireNextDue()) {
				break;
			}
		}
		this.cleanupProcessor.processAvailable();
	}
}
