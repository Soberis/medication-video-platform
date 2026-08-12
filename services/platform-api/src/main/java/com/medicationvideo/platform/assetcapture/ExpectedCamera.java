package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

/** Frozen CameraSetSnapshot member identity and stable one-based display number. */
public record ExpectedCamera(UUID cameraSetSnapshotMemberId, int cameraNumber) {

	public ExpectedCamera {
		if (cameraSetSnapshotMemberId == null) {
			throw new IllegalArgumentException("cameraSetSnapshotMemberId is required.");
		}
		if (cameraNumber < 1 || cameraNumber > 64) {
			throw new IllegalArgumentException("cameraNumber must be between 1 and 64.");
		}
	}

}
