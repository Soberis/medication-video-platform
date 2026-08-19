package com.medicationvideo.platform.assetcapture;

final class UploadFileRegistrationException extends RuntimeException {

	private final String errorCode;

	UploadFileRegistrationException(String errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	String getErrorCode() {
		return this.errorCode;
	}

}
