package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

record CameraStreamResponse(UUID cameraStreamId, UUID clipAssetId, UUID clientFileId, UUID cameraChannelId,
		int cameraIndex, int cameraNumber, String cameraRole, String mountPosition, Instant recordStartUtc,
		long timeOffsetMs, DeclaredSyncStatus syncStatus, CameraStreamStatus streamStatus,
		CaptureQualityStatus qualityStatus, boolean enabledForHandDetection) {

	static CameraStreamResponse from(CameraStream stream, CameraSetSnapshotMember member) {
		return new CameraStreamResponse(stream.getCameraStreamId(), stream.getClipAssetId(), stream.getClientFileId(),
				stream.getCameraChannelId(), stream.getCameraIndex(), stream.getCameraIndex() + 1,
				member.getCameraRole(), member.getMountPosition(), stream.getRecordStartUtc(), stream.getTimeOffsetMs(),
				stream.getDeclaredSyncStatus(), stream.getStreamStatus(), stream.getQualityStatus(),
				member.isEnabledForHandDetection());
	}
}
