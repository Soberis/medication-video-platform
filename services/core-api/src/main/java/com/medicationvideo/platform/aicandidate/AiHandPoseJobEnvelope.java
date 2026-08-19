package com.medicationvideo.platform.aicandidate;

import java.time.Instant;
import java.util.UUID;

public record AiHandPoseJobEnvelope(String schemaVersion, UUID messageId, UUID jobId, int attempt, String leaseToken,
		UUID projectId, UUID clipAssetId, String inputSha256, long inputSizeBytes, String contentType,
		String algorithmKey, String algorithmVersion, String modelSha256, int samplingFps, String profileSha256,
		Instant requestedAt) {

	@Override
	public String toString() {
		return "AiHandPoseJobEnvelope[messageId=%s, jobId=%s, attempt=%d, leaseToken=[REDACTED]]"
			.formatted(this.messageId, this.jobId, this.attempt);
	}

}
