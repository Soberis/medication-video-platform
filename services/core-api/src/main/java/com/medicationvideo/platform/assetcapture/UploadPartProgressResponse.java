package com.medicationvideo.platform.assetcapture;

import java.util.List;
import java.util.UUID;

record UploadPartProgressResponse(UUID uploadFileId, UUID batchId, UUID clientFileId, int totalParts, int trackedParts,
		int uploadedParts, List<UploadPartResponse> parts) {

	static UploadPartProgressResponse from(UploadFile file, List<UploadPart> parts) {
		var responses = parts.stream().map(UploadPartResponse::from).toList();
		var uploadedParts = Math
			.toIntExact(parts.stream().filter(part -> part.getStatus() == UploadPartStatus.UPLOADED).count());
		return new UploadPartProgressResponse(file.getUploadFileId(), file.getBatchId(), file.getClientFileId(),
				file.getTotalParts(), parts.size(), uploadedParts, responses);
	}
}
