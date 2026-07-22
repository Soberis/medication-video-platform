package com.medicationvideo.platform.qualitycleaning;

final class QualityReviewException extends RuntimeException {

	private final String errorCode;
	private final int statusCode;

	QualityReviewException(String errorCode, String message, int statusCode) {
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
