package com.medicationvideo.platform.assetcapture;

final class CameraConfigurationException extends RuntimeException {

	private final String errorCode;

	CameraConfigurationException(String errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	String getErrorCode() {
		return this.errorCode;
	}
}
