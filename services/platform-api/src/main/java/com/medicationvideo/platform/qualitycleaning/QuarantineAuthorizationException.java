package com.medicationvideo.platform.qualitycleaning;

final class QuarantineAuthorizationException extends RuntimeException {

	QuarantineAuthorizationException() {
		super("Administrator access is required for quarantine metadata.");
	}
}
