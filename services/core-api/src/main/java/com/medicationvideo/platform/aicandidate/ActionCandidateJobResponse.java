package com.medicationvideo.platform.aicandidate;

import java.time.Instant;
import java.util.UUID;

public record ActionCandidateJobResponse(UUID aiJobId, UUID projectId, UUID captureGroupId, UUID clipAssetId,
		String status, int attempt, String failureReason, Instant createdAt, Instant updatedAt) {
}
