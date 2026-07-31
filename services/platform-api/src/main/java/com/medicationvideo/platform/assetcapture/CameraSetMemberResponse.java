package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

record CameraSetMemberResponse(UUID cameraChannelId, int cameraIndex, String cameraRole, String mountPosition,
		boolean enabled) {

	static CameraSetMemberResponse from(CameraSetMember member) {
		return new CameraSetMemberResponse(member.getCameraChannelId(), member.getCameraIndex(), member.getCameraRole(),
				member.getMountPosition(), member.isEnabled());
	}
}
