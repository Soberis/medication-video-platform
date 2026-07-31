package com.medicationvideo.platform.mediaprivacy;

import java.time.Instant;

public interface MediaJobDispatchPort {

	void enqueue(MediaProbeJobEnvelope envelope, Instant availableAt);

}
