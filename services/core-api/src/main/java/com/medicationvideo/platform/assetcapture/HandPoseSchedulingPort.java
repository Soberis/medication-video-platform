package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

/**
 * Cross-module application port for optional hand-pose scheduling after a clip asset has
 * committed its successful media ingest.
 */
public interface HandPoseSchedulingPort {

	void scheduleAfterMediaIngested(UUID projectId, UUID clipAssetId);

}
