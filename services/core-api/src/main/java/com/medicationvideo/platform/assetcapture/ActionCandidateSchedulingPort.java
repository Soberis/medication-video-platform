package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

/**
 * Cross-module trigger for the optional action-candidate branch. Implementations must not
 * change the committed capture or quality state when AI dispatch is unavailable.
 */
public interface ActionCandidateSchedulingPort {

	void scheduleAfterStateChange(UUID projectId, UUID captureGroupId);

	void scheduleAfterMediaIngested(UUID projectId, UUID clipAssetId);

}
