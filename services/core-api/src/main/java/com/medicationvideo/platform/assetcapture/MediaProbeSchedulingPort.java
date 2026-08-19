package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

/**
 * Asset-capture-owned port for scheduling the media fact check after upload integrity
 * completes. The implementation remains in the media-privacy module without making this
 * module depend on it.
 */
public interface MediaProbeSchedulingPort {

	void ensureForUploadedClip(UUID projectId, UUID clipAssetId);

}
