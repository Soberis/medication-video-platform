package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

record CameraChannelResponse(
		UUID cameraChannelId,
		UUID projectId,
		String provisioningKey,
		Instant createdAt) {

	static CameraChannelResponse from(CameraChannel channel) {
		return new CameraChannelResponse(
				channel.getCameraChannelId(),
				channel.getProjectId(),
				channel.getProvisioningKey(),
				channel.getCreatedAt());
	}
}
