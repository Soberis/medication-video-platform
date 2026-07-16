package com.medicationvideo.platform.mediaprivacy;

public interface MediaJobDispatchPort {

	void enqueue(MediaProbeJobEnvelope envelope);
}
