package com.medicationvideo.platform.assetcapture;

import java.util.List;
import java.util.UUID;

record UploadFileProgressListResponse(
		UUID batchId,
		int registeredFileCount,
		List<UploadFileProgressResponse> files) {
}
