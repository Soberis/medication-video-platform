package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

record UploadBatchTerminationResponse(UUID batchId, UploadBatchStatus status, String terminationReasonCode,
		Instant terminatedAt, UploadCleanupStatus cleanupStatus, int cleanupTaskCount, int completedCleanupTaskCount,
		int failedCleanupTaskCount) {

	static UploadBatchTerminationResponse from(UploadBatch batch, UploadCleanupSummary cleanup) {
		return new UploadBatchTerminationResponse(batch.getBatchId(), batch.getStatus(),
				batch.getTerminationReasonCode(), batch.getTerminatedAt(), cleanup.status(), cleanup.taskCount(),
				cleanup.completedTaskCount(), cleanup.failedTaskCount());
	}
}
