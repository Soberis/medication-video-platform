package com.medicationvideo.platform.assetcapture;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

record UploadPartAuthorizationResponse(UUID uploadFileId, UUID batchId, UUID clientFileId, int partNumber,
		long expectedSizeBytes, String sha256, UploadPartStatus status, URI uploadUrl, Instant expiresAt) {

	static UploadPartAuthorizationResponse from(UploadFile file, UploadPart part, URI uploadUrl) {
		return new UploadPartAuthorizationResponse(file.getUploadFileId(), file.getBatchId(), file.getClientFileId(),
				part.getPartNumber(), part.getExpectedSizeBytes(), part.getSha256(), part.getStatus(), uploadUrl,
				part.getAuthorizationExpiresAt());
	}
}
