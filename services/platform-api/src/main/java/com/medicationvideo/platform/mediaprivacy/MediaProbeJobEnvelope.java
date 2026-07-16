package com.medicationvideo.platform.mediaprivacy;

import java.time.Instant;
import java.util.UUID;

public record MediaProbeJobEnvelope(
		String schemaVersion,
		UUID messageId,
		UUID jobId,
		MediaJobType jobType,
		int attempt,
		String leaseToken,
		UUID projectId,
		UUID clipAssetId,
		String inputSha256,
		long inputSizeBytes,
		String contentType,
		String probeProfileKey,
		String recipeDigest,
		Instant requestedAt) {

	@Override
	public String toString() {
		return "MediaProbeJobEnvelope[messageId=%s, jobId=%s, attempt=%d, leaseToken=[REDACTED]]"
				.formatted(this.messageId, this.jobId, this.attempt);
	}
}
