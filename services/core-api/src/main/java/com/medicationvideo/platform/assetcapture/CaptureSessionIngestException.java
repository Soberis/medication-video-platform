package com.medicationvideo.platform.assetcapture;

final class CaptureSessionIngestException extends RuntimeException {

	private final String errorCode;

	CaptureSessionIngestException(String errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	String getErrorCode() {
		return this.errorCode;
	}

}
