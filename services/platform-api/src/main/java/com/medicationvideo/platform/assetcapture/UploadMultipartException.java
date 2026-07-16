package com.medicationvideo.platform.assetcapture;

class UploadMultipartException extends RuntimeException {

	private final String errorCode;

	UploadMultipartException(String errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	String getErrorCode() {
		return this.errorCode;
	}
}
