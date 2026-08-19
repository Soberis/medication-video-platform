package com.medicationvideo.platform.aicandidate;

import java.time.Instant;
import java.util.UUID;

record AiWorkerHeartbeatResponse(UUID jobId, int attempt, Instant leaseUntil, long heartbeatIntervalSeconds) {
}
