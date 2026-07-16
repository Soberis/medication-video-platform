package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

record CameraStreamResponse(
		UUID cameraStreamId,
		UUID clipAssetId,
		UUID clientFileId,
		UUID cameraChannelId,
		int cameraIndex,
		String cameraRole,
		String mountPosition,
		Instant recordStartUtc,
		long timeOffsetMs,
		DeclaredSyncStatus syncStatus,
		CameraStreamStatus streamStatus,
		CaptureQualityStatus qualityStatus) {

	static CameraStreamResponse from(CameraStream stream, CameraSetSnapshotMember member) {
		return new CameraStreamResponse(
				stream.getCameraStreamId(),
				stream.getClipAssetId(),
				stream.getClientFileId(),
				stream.getCameraChannelId(),
				stream.getCameraIndex(),
				member.getCameraRole(),
				member.getMountPosition(),
				stream.getRecordStartUtc(),
				stream.getTimeOffsetMs(),
				stream.getDeclaredSyncStatus(),
				stream.getStreamStatus(),
				stream.getQualityStatus());
	}
}
