package com.medicationvideo.platform.assetcapture;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class UploadCleanupProcessor {

	private static final Logger log = LoggerFactory.getLogger(UploadCleanupProcessor.class);

	private final UploadCleanupTaskStore store;

	private final MultipartObjectStorage objectStorage;

	private final int batchSize;

	private final int maxAttempts;

	private final Duration claimDuration;

	private final Duration retryDelay;

	UploadCleanupProcessor(UploadCleanupTaskStore store, MultipartObjectStorage objectStorage,
			@Value("${platform.upload.cleanup-batch-size:20}") int batchSize,
			@Value("${platform.upload.cleanup-max-attempts:5}") int maxAttempts,
			@Value("${platform.upload.cleanup-claim-duration:PT1M}") Duration claimDuration,
			@Value("${platform.upload.cleanup-retry-delay:PT30S}") Duration retryDelay) {
		if (batchSize < 1 || batchSize > 100) {
			throw new IllegalArgumentException("Upload cleanup batch size must be between 1 and 100.");
		}
		if (maxAttempts < 1 || maxAttempts > 20) {
			throw new IllegalArgumentException("Upload cleanup max attempts must be between 1 and 20.");
		}
		if (claimDuration.isZero() || claimDuration.isNegative() || retryDelay.isZero() || retryDelay.isNegative()) {
			throw new IllegalArgumentException("Upload cleanup timing values must be positive.");
		}
		this.store = store;
		this.objectStorage = objectStorage;
		this.batchSize = batchSize;
		this.maxAttempts = maxAttempts;
		this.claimDuration = claimDuration;
		this.retryDelay = retryDelay;
	}

	void processAvailable() {
		var claimedAt = Instant.now();
		for (var task : this.store.claim(this.batchSize, claimedAt, this.claimDuration)) {
			try {
				cleanup(task.upload());
				this.store.markSucceeded(task.cleanupTaskId(), task.claimToken(), Instant.now());
			}
			catch (MultipartObjectStorageException exception) {
				var now = Instant.now();
				this.store.releaseOrFail(task, now, now.plus(this.retryDelay), this.maxAttempts,
						"OBJECT_STORAGE_CLEANUP_FAILED");
				log.warn("Upload cleanup task {} failed on attempt {} and remains governed by bounded retry.",
						task.cleanupTaskId(), task.attemptCount());
			}
		}
	}

	private void cleanup(MultipartObjectStorage.CreatedUpload upload) {
		var object = new MultipartObjectStorage.ObjectReference(upload.bucket(), upload.objectKey());
		if (this.objectStorage.findCompletedObject(upload).isPresent()) {
			this.objectStorage.delete(object);
			return;
		}
		this.objectStorage.abort(upload);
		if (this.objectStorage.findCompletedObject(upload).isPresent()) {
			this.objectStorage.delete(object);
		}
	}

}
