package com.medicationvideo.platform.aicandidate;

class HandPoseJobException extends RuntimeException {

	private final String errorCode;

	private final int statusCode;

	HandPoseJobException(String errorCode, String message, int statusCode) {
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
