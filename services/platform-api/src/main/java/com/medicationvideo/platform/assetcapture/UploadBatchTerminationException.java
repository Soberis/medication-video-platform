package com.medicationvideo.platform.assetcapture;

final class UploadBatchTerminationException extends RuntimeException {

	private final String errorCode;

	private final boolean retryable;

	UploadBatchTerminationException(String errorCode, String message) {
		this(errorCode, message, false);
	}

	UploadBatchTerminationException(String errorCode, String message, boolean retryable) {
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
