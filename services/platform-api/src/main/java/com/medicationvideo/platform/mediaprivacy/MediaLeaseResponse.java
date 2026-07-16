package com.medicationvideo.platform.mediaprivacy;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

record MediaLeaseResponse(
		UUID jobId,
		int attempt,
		URI inputUrl,
		Instant expiresAt,
		Instant leaseUntil,
		long heartbeatIntervalSeconds,
		String inputSha256,
		long inputSizeBytes,
		String contentType) {
}
