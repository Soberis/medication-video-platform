package com.medicationvideo.platform.mediaprivacy;

import java.time.Instant;
import java.util.UUID;

record MediaHeartbeatResponse(
		UUID jobId,
		int attempt,
		Instant leaseUntil,
		long heartbeatIntervalSeconds) {
}
