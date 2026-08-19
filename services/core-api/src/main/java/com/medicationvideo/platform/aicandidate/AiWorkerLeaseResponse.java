package com.medicationvideo.platform.aicandidate;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

record AiWorkerLeaseResponse(UUID jobId, int attempt, URI inputUrl, Instant expiresAt, Instant leaseUntil,
		long heartbeatIntervalSeconds, String inputSha256, long inputSizeBytes, String contentType, String modelSha256,
		int samplingFps, String profileSha256) {
}
