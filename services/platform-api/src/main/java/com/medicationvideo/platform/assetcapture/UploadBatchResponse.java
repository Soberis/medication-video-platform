package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

record UploadBatchResponse(
		UUID batchId,
		UUID projectId,
		String deviceId,
		UploadSourceType uploadSourceType,
		UUID assetFolderId,
		int fileCount,
		long totalSizeBytes,
		String manifestSha256,
		UploadBatchStatus status,
		Instant expiresAt,
		Instant createdAt) {

	static UploadBatchResponse from(UploadBatch batch) {
		return new UploadBatchResponse(
				batch.getBatchId(),
				batch.getProjectId(),
				batch.getDeviceId(),
				batch.getUploadSourceType(),
				batch.getAssetFolderId(),
				batch.getFileCount(),
				batch.getTotalSizeBytes(),
				batch.getManifestSha256(),
				batch.getStatus(),
				batch.getExpiresAt(),
				batch.getCreatedAt());
	}
}
