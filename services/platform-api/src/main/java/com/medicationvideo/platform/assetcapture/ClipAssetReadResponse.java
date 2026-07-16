package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

record ClipAssetReadResponse(
		UUID clipAssetId,
		UUID batchId,
		UUID sourceUploadFileId,
		UUID clientFileId,
		String contentType,
		long sizeBytes,
		String sha256,
		ClipAssetStatus status,
		ClipAssetSessionBindingResponse sessionBinding,
		Instant createdAt,
		Instant updatedAt) {
}
