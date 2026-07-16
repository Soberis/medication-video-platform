package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record CameraSetResponse(
		UUID cameraSetId,
		UUID projectId,
		String cameraSetKey,
		int configurationVersion,
		String layoutVersion,
		int enabledCameraCount,
		List<CameraSetMemberResponse> members,
		Instant createdAt) {

	static CameraSetResponse from(CameraSetConfiguration configuration, List<CameraSetMember> members) {
		var responseMembers = members.stream()
				.sorted(java.util.Comparator.comparingInt(CameraSetMember::getCameraIndex))
				.map(CameraSetMemberResponse::from)
				.toList();
		var enabledCameraCount = Math.toIntExact(members.stream().filter(CameraSetMember::isEnabled).count());
		return new CameraSetResponse(
				configuration.getCameraSetId(),
				configuration.getProjectId(),
				configuration.getCameraSetKey(),
				configuration.getConfigurationVersion(),
				configuration.getLayoutVersion(),
				enabledCameraCount,
				responseMembers,
				configuration.getCreatedAt());
	}
}
