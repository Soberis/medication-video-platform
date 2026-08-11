package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

record UploadFileResponse(UUID uploadFileId, UUID batchId, UUID clientFileId, UploadFileKind fileKind, String fileName,
		String contentType, long sizeBytes, String sha256, long partSizeBytes, int totalParts, UploadFileStatus status,
		Instant createdAt) {

	static UploadFileResponse from(UploadFile file) {
		return new UploadFileResponse(file.getUploadFileId(), file.getBatchId(), file.getClientFileId(),
				file.getFileKind(), file.getFileName(), file.getContentType(), file.getSizeBytes(), file.getSha256(),
				file.getPartSizeBytes(), file.getTotalParts(), file.getStatus(), file.getCreatedAt());
	}
}
