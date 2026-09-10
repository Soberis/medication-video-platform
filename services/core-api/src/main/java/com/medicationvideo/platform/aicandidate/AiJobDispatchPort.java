package com.medicationvideo.platform.aicandidate;

import java.time.Instant;

public interface AiJobDispatchPort {

	void enqueue(AiHandPoseJobEnvelope envelope, Instant availableAt);

	void enqueueAction(AiActionJobEnvelope envelope, Instant availableAt);

}
