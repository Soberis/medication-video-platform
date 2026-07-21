package com.medicationvideo.platform.iamproject;

class PlatformAuthenticationException extends RuntimeException {

	PlatformAuthenticationException() {
		super("The supplied credentials are invalid.");
	}
}
