package com.medicationvideo.platform.assetcapture;

public final class ClipAssetMediaIngestException extends RuntimeException {

	private final String errorCode;
	private final boolean retryable;

	public ClipAssetMediaIngestException(String errorCode, String message, boolean retryable) {
		super(message);
		this.errorCode = errorCode;
		this.retryable = retryable;
	}

	public ClipAssetMediaIngestException(
			String errorCode, String message, boolean retryable, Throwable cause) {
		super(message, cause);
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
