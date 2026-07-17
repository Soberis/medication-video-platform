package com.medicationvideo.platform.mediaprivacy;

class PrivacyAssessmentException extends RuntimeException {

	private final String errorCode;
	private final int statusCode;

	PrivacyAssessmentException(String errorCode, String message, int statusCode) {
		super(message);
		this.errorCode = errorCode;
		this.statusCode = statusCode;
	}

	String getErrorCode() {
		return this.errorCode;
	}

	int getStatusCode() {
		return this.statusCode;
	}
}
