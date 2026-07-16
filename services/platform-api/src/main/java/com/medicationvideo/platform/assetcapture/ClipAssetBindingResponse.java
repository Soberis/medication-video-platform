package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

record ClipAssetBindingResponse(
		UUID clipAssetId,
		UUID sourceUploadFileId,
		UUID clientFileId,
		String contentType,
		long sizeBytes,
		String sha256,
		ClipAssetStatus status) {

	static ClipAssetBindingResponse from(ClipAsset asset) {
		return new ClipAssetBindingResponse(
				asset.getClipAssetId(),
				asset.getSourceUploadFileId(),
				asset.getClientFileId(),
				asset.getContentType(),
				asset.getSizeBytes(),
				asset.getSha256(),
				asset.getStatus());
	}
}
