package com.medicationvideo.platform.mediaprivacy;

/**
 * Job type discriminator. The set is allowlist-driven; unknown values are rejected at the API
 * boundary and never persisted. New types require a contract version bump and an ADR.
 */
public enum MediaJobType {
	MEDIA_PROBE
}
