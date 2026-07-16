package com.medicationvideo.platform.mediaprivacy;

import java.time.Instant;
import java.util.UUID;

record MediaResultAcceptedResponse(
		UUID messageId,
		UUID jobId,
		int attempt,
		MediaProbeResultStatus status,
		Instant acceptedAt) {
}
