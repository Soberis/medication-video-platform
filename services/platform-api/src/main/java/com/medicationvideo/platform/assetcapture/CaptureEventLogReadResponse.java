package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

record CaptureEventLogReadResponse(
		UUID captureEventLogId,
		UUID batchId,
		UUID sourceUploadFileId,
		UUID clientFileId,
		String deviceId,
		String contentType,
		long sizeBytes,
		String sha256,
		Instant indexedAt) {

	static CaptureEventLogReadResponse from(CaptureEventLog eventLog) {
		return new CaptureEventLogReadResponse(
				eventLog.getCaptureEventLogId(),
				eventLog.getBatchId(),
				eventLog.getSourceUploadFileId(),
				eventLog.getClientFileId(),
				eventLog.getDeviceId(),
				eventLog.getContentType(),
				eventLog.getSizeBytes(),
				eventLog.getSha256(),
				eventLog.getIndexedAt());
	}
}
