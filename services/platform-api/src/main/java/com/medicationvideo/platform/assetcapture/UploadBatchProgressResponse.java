package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

record UploadBatchProgressResponse(
		UUID batchId,
		UUID projectId,
		String deviceId,
		int fileCount,
		int registeredFileCount,
		int uploadedFileCount,
		int failedFileCount,
		long totalSizeBytes,
		long uploadedSizeBytes,
		long trackedPartCount,
		long uploadedPartCount,
		UploadBatchStatus status,
		Instant expiresAt,
		Instant createdAt) {

	static UploadBatchProgressResponse from(
			UploadBatch batch,
			List<UploadFile> files,
			Collection<UploadPartProgressSummary> partProgress) {
		var uploadedFileCount = Math.toIntExact(files.stream()
				.filter(file -> file.getStatus() == UploadFileStatus.UPLOADED)
				.count());
		var failedFileCount = Math.toIntExact(files.stream()
				.filter(file -> file.getStatus() == UploadFileStatus.FAILED)
				.count());
		var uploadedSizeBytes = partProgress.stream()
				.mapToLong(UploadPartProgressSummary::getUploadedSizeBytes)
				.sum();
		var trackedPartCount = partProgress.stream()
				.mapToLong(UploadPartProgressSummary::getTrackedParts)
				.sum();
		var uploadedPartCount = partProgress.stream()
				.mapToLong(UploadPartProgressSummary::getUploadedParts)
				.sum();
		return new UploadBatchProgressResponse(
				batch.getBatchId(),
				batch.getProjectId(),
				batch.getDeviceId(),
				batch.getFileCount(),
				files.size(),
				uploadedFileCount,
				failedFileCount,
				batch.getTotalSizeBytes(),
				uploadedSizeBytes,
				trackedPartCount,
				uploadedPartCount,
				batch.getStatus(),
				batch.getExpiresAt(),
				batch.getCreatedAt());
	}
}
