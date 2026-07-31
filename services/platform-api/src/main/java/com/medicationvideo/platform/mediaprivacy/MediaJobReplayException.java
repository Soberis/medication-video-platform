package com.medicationvideo.platform.mediaprivacy;

class MediaJobReplayException extends RuntimeException {

	private final String errorCode;

	MediaJobReplayException(String errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	String getErrorCode() {
		return this.errorCode;
	}

}
