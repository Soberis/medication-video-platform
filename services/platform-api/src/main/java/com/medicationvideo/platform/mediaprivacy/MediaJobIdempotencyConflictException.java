package com.medicationvideo.platform.mediaprivacy;

final class MediaJobIdempotencyConflictException extends RuntimeException {

	MediaJobIdempotencyConflictException() {
		super("The idempotency key was reused with a different request body.");
	}
}
