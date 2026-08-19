package com.medicationvideo.platform.assetcapture;

final class UploadBatchInputException extends RuntimeException {

	private final String errorCode;

	UploadBatchInputException(String errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	String getErrorCode() {
		return this.errorCode;
	}

}
