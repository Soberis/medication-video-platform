package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

record UploadFileProgressResponse(UUID uploadFileId, UUID batchId, UUID clientFileId, UploadFileKind fileKind,
		String contentType, long sizeBytes, String declaredSha256, String verifiedSha256, int totalParts,
		int trackedParts, int uploadedParts, long uploadedSizeBytes, UploadFileStatus status,
		String completionErrorCode, Instant multipartCreatedAt, Instant completionStartedAt, Instant completedAt,
		Instant createdAt, Instant updatedAt) {

	static UploadFileProgressResponse from(UploadFile file, UploadPartProgressSummary progress) {
		return new UploadFileProgressResponse(file.getUploadFileId(), file.getBatchId(), file.getClientFileId(),
				file.getFileKind(), file.getContentType(), file.getSizeBytes(), file.getSha256(),
				file.getVerifiedSha256(), file.getTotalParts(),
				progress == null ? 0 : Math.toIntExact(progress.getTrackedParts()),
				progress == null ? 0 : Math.toIntExact(progress.getUploadedParts()),
				progress == null ? 0 : progress.getUploadedSizeBytes(), file.getStatus(), file.getCompletionErrorCode(),
				file.getMultipartCreatedAt(), file.getCompletionStartedAt(), file.getCompletionFinishedAt(),
				file.getCreatedAt(), file.getUpdatedAt());
	}
}
