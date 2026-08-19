package com.medicationvideo.platform.assetcapture;

public final class CaptureSyncExecutionException extends RuntimeException {

	private final String errorCode;

	private final boolean retryable;

	public CaptureSyncExecutionException(String errorCode, String message, boolean retryable) {
		super(message);
		this.errorCode = errorCode;
		this.retryable = retryable;
	}

	public String getErrorCode() {
		return this.errorCode;
	}

	public boolean isRetryable() {
		return this.retryable;
	}

}
