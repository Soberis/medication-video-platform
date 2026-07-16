package com.medicationvideo.platform.assetcapture;

final class UploadBatchCompletionException extends RuntimeException {

	private final String errorCode;

	UploadBatchCompletionException(String errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	String getErrorCode() {
		return this.errorCode;
	}
}
