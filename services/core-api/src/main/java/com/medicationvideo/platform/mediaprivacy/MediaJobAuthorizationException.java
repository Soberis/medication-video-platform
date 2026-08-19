package com.medicationvideo.platform.mediaprivacy;

final class MediaJobAuthorizationException extends RuntimeException {

	MediaJobAuthorizationException() {
		super("The caller is not authorized for the current project.");
	}

}
