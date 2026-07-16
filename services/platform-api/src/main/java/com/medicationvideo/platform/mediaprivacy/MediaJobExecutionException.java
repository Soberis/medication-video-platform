package com.medicationvideo.platform.mediaprivacy;

final class MediaJobExecutionException extends RuntimeException {

	private final String errorCode;
	private final boolean retryable;

	MediaJobExecutionException(String errorCode, String message, boolean retryable) {
		super(message);
		this.errorCode = errorCode;
		this.retryable = retryable;
	}

	String getErrorCode() {
		return this.errorCode;
	}

	boolean isRetryable() {
		return this.retryable;
	}
}
