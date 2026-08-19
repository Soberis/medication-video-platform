package com.medicationvideo.platform.mediaprivacy;

import java.time.Instant;
import java.util.UUID;

public interface MediaJobDispatchPort {

	void enqueue(MediaProbeJobEnvelope envelope, Instant availableAt);

	boolean hasConfirmedPublication(UUID mediaJobId, UUID messageId);

}
