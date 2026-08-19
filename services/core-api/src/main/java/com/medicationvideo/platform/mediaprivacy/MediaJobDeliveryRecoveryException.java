package com.medicationvideo.platform.mediaprivacy;

class MediaJobDeliveryRecoveryException extends RuntimeException {

	private final String errorCode;

	MediaJobDeliveryRecoveryException(String errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	String getErrorCode() {
		return this.errorCode;
	}

}
