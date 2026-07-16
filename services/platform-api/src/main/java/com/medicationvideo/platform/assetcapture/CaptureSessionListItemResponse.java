package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

record CaptureSessionListItemResponse(
		UUID captureSessionId,
		UUID batchId,
		String deviceId,
		UUID cameraSetId,
		String cameraSetKey,
		int cameraSetConfigurationVersion,
		String cameraLayoutVersion,
		int expectedCameraCount,
		int boundStreamCount,
		int receivedCameraCount,
		int ingestFailedStreamCount,
		String syncGroupId,
		Instant deviceTimeUtc,
		Instant serverReceiveTime,
		CaptureSessionAssemblyStatus assemblyStatus,
		CaptureQualityStatus qualityStatus,
		Instant createdAt) {
}
