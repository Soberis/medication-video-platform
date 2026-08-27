package com.medicationvideo.platform.qualitycleaning;

import java.time.Instant;
import java.util.UUID;

import com.medicationvideo.platform.iamproject.ProjectRole;

/**
 * Internal transaction event emitted when a group-quality fact admits a group to
 * annotation. Consumers must re-read the current group-quality fact before writing.
 */
public record CaptureGroupQualityQualifiedEvent(String schemaVersion, UUID projectId, UUID captureGroupId,
		UUID captureGroupQualityReviewId, long reviewRevision, String reviewerActorId, ProjectRole reviewerRole,
		String inputVersion, Instant triggeredAt, String requestId, String traceId) {

	public CaptureGroupQualityQualifiedEvent(UUID projectId, UUID captureGroupId, UUID captureGroupQualityReviewId,
			long reviewRevision, String reviewerActorId, ProjectRole reviewerRole, String inputVersion,
			Instant triggeredAt, String requestId, String traceId) {
		this("capture-group-quality-qualified.v1", projectId, captureGroupId, captureGroupQualityReviewId,
				reviewRevision, reviewerActorId, reviewerRole, inputVersion, triggeredAt, requestId, traceId);
	}

}
