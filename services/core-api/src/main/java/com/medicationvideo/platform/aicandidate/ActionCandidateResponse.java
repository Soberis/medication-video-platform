package com.medicationvideo.platform.aicandidate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ActionCandidateResponse(UUID actionCandidateId, UUID aiJobId, UUID projectId, UUID captureGroupId,
		UUID clipAssetId, long startMs, long endMs, String actionLabel, double confidence, int sourceWindow,
		List<Integer> stitchedWindows, Instant createdAt) {
}
