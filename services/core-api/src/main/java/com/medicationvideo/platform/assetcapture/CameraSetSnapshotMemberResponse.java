package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

record CameraSetSnapshotMemberResponse(UUID cameraChannelId, int cameraIndex, int cameraNumber, String cameraRole,
		String mountPosition, boolean enabled, boolean enabledForHandDetection) {

	static CameraSetSnapshotMemberResponse from(CameraSetSnapshotMember member) {
		return new CameraSetSnapshotMemberResponse(member.getCameraChannelId(), member.getCameraIndex(),
				member.getCameraIndex() + 1, member.getCameraRole(), member.getMountPosition(), member.isEnabled(),
				member.isEnabledForHandDetection());
	}
}
