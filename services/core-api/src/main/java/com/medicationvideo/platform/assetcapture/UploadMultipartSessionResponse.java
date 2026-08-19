package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

record UploadMultipartSessionResponse(UUID uploadFileId, UUID batchId, UUID clientFileId, int totalParts,
		UploadFileStatus status, Instant multipartCreatedAt) {

	static UploadMultipartSessionResponse from(UploadFile file) {
		return new UploadMultipartSessionResponse(file.getUploadFileId(), file.getBatchId(), file.getClientFileId(),
				file.getTotalParts(), file.getStatus(), file.getMultipartCreatedAt());
	}
}
