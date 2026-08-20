package com.medicationvideo.platform.qualitycleaning;

import java.time.Instant;
import java.util.UUID;

import com.medicationvideo.platform.iamproject.ProjectRole;

/**
 * Internal workflow fact published when a human changes a video's quality conclusion to
 * {@code QUALIFIED}. Consumers must still re-read their own guarded facts before writing.
 */
public record QualityReviewQualifiedEvent(UUID projectId, UUID clipAssetId, UUID qualityReviewId,
		long qualityReviewVersion, String qualityReviewerActorId, ProjectRole qualityReviewerRole, Instant triggeredAt,
		String requestId, String traceId) {
}
