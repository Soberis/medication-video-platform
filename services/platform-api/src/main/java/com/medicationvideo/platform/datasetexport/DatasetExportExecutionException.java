package com.medicationvideo.platform.datasetexport;

final class DatasetExportExecutionException extends RuntimeException {

	private final String errorCode;

	private final boolean retryable;

	DatasetExportExecutionException(String errorCode, String message, boolean retryable) {
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
