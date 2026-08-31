package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

record ClipAssetSessionBindingResponse(UUID captureSessionId, UUID captureGroupId, boolean captureGroupWorkflow,
		CaptureGroupReceiptState receiptState, UUID cameraStreamId, UUID cameraChannelId, int cameraIndex,
		int cameraNumber, String cameraRole, String mountPosition, Instant recordStartUtc, long timeOffsetMs,
		DeclaredSyncStatus declaredSyncStatus, CameraStreamStatus streamStatus, CaptureQualityStatus qualityStatus,
		boolean enabledForHandDetection) {
}
