package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

record UploadPartResponse(UUID uploadFileId, int partNumber, long expectedSizeBytes, String sha256,
		UploadPartStatus status, Long uploadedSizeBytes, String storageEtag, Instant confirmedAt) {

	static UploadPartResponse from(UploadPart part) {
		return new UploadPartResponse(part.getUploadFileId(), part.getPartNumber(), part.getExpectedSizeBytes(),
				part.getSha256(), part.getStatus(), part.getUploadedSizeBytes(), part.getStorageEtag(),
				part.getConfirmedAt());
	}
}
