package com.medicationvideo.platform.iamproject;

class ProjectApiException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String errorCode;
	private final int statusCode;

	ProjectApiException(String errorCode, String message, int statusCode) {
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
