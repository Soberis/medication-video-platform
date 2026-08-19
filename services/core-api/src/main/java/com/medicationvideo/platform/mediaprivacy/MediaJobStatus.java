package com.medicationvideo.platform.mediaprivacy;

/**
 * Lifecycle states for an asynchronous media job. Mirrors the generic async job state
 * machine (state-machines §9).
 */
public enum MediaJobStatus {

	REQUESTED, QUEUED, RUNNING, RETRY_WAIT, SUCCEEDED, FAILED, CANCEL_REQUESTED, CANCELLED

}
