package com.medicationvideo.platform.aicandidate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ActionCandidateResponse(UUID actionCandidateId, UUID aiJobId, UUID projectId, UUID captureGroupId,
		UUID clipAssetId, UUID annotationTaskId, UUID labelSchemaId, String labelSchemaDigest, long startMs, long endMs,
		String actionLabel, Double confidence, int sourceWindow, List<Integer> stitchedWindows, String targetObject,
		Instant createdAt) {

	public ActionCandidateResponse(UUID actionCandidateId, UUID aiJobId, UUID projectId, UUID captureGroupId,
			UUID clipAssetId, UUID annotationTaskId, UUID labelSchemaId, String labelSchemaDigest, long startMs,
			long endMs, String actionLabel, Double confidence, int sourceWindow, List<Integer> stitchedWindows,
			Instant createdAt) {
		this(actionCandidateId, aiJobId, projectId, captureGroupId, clipAssetId, annotationTaskId, labelSchemaId,
				labelSchemaDigest, startMs, endMs, actionLabel, confidence, sourceWindow, stitchedWindows, null,
				createdAt);
	}
}
