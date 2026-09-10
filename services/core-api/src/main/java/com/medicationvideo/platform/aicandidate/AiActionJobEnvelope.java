package com.medicationvideo.platform.aicandidate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AiActionJobEnvelope(String schemaVersion, UUID messageId, UUID jobId, int attempt, String leaseToken,
		UUID projectId, UUID captureGroupId, UUID clipAssetId, String inputSha256, long inputSizeBytes,
		String contentType, String algorithmKey, String algorithmVersion, String modelSha256, int samplingFps,
		String profileSha256, List<String> allowedLabels, Instant requestedAt) {

	@Override
	public String toString() {
		return "AiActionJobEnvelope[messageId=%s, jobId=%s, attempt=%d, leaseToken=[REDACTED]]"
			.formatted(this.messageId, this.jobId, this.attempt);
	}

}
