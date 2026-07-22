package com.medicationvideo.platform.assetcapture;

final class RawReviewPlaybackException extends RuntimeException {

	private final String errorCode;
	private final int statusCode;
	private final boolean retryable;

	RawReviewPlaybackException(String errorCode, String message, int statusCode, boolean retryable) {
		super(message);
		this.errorCode = errorCode;
		this.statusCode = statusCode;
		this.retryable = retryable;
	}

	RawReviewPlaybackException(
			String errorCode,
			String message,
			int statusCode,
			boolean retryable,
			Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
		this.statusCode = statusCode;
		this.retryable = retryable;
	}

	String getErrorCode() {
		return this.errorCode;
	}

	int getStatusCode() {
		return this.statusCode;
	}

	boolean isRetryable() {
		return this.retryable;
	}
}
