package com.medicationvideo.platform.assetcapture;

import java.util.List;
import java.util.UUID;

record CameraSetSnapshotResponse(
		UUID cameraSetSnapshotId,
		UUID sourceCameraSetId,
		String cameraSetKey,
		int configurationVersion,
		String layoutVersion,
		int expectedCameraCount,
		List<CameraSetSnapshotMemberResponse> members) {

	static CameraSetSnapshotResponse from(
			CameraSetSnapshot snapshot,
			List<CameraSetSnapshotMember> members) {
		return new CameraSetSnapshotResponse(
				snapshot.getCameraSetSnapshotId(),
				snapshot.getSourceCameraSetId(),
				snapshot.getCameraSetKey(),
				snapshot.getConfigurationVersion(),
				snapshot.getLayoutVersion(),
				snapshot.getExpectedCameraCount(),
				members.stream()
						.sorted(java.util.Comparator.comparingInt(CameraSetSnapshotMember::getCameraIndex))
						.map(CameraSetSnapshotMemberResponse::from)
						.toList());
	}
}
