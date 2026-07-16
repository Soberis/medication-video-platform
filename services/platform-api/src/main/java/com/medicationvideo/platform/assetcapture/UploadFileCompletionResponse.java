package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

record UploadFileCompletionResponse(
		UUID uploadFileId,
		UUID batchId,
		UUID clientFileId,
		long sizeBytes,
		String declaredSha256,
		String verifiedSha256,
		UploadFileStatus status,
		String storageEtag,
		Instant completionStartedAt,
		Instant completedAt) {

	static UploadFileCompletionResponse from(UploadFile file) {
		if (file.getStatus() != UploadFileStatus.UPLOADED) {
			throw new IllegalArgumentException("Only an uploaded file has a completion response.");
		}
		return new UploadFileCompletionResponse(
				file.getUploadFileId(),
				file.getBatchId(),
				file.getClientFileId(),
				file.getSizeBytes(),
				file.getSha256(),
				file.getVerifiedSha256(),
				file.getStatus(),
				file.getCompletedStorageEtag(),
				file.getCompletionStartedAt(),
				file.getCompletionFinishedAt());
	}
}
