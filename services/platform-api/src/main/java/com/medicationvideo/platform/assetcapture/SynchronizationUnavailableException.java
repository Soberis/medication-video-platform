package com.medicationvideo.platform.assetcapture;

final class SynchronizationUnavailableException extends IllegalStateException {

	static final String TRUSTED_MAPPING_REQUIRED = "TRUSTED_STREAM_TO_CAPTURE_MAPPING_REQUIRED";

	private final int cameraNumber;

	SynchronizationUnavailableException(int cameraNumber) {
		super(TRUSTED_MAPPING_REQUIRED + ": camera_number=" + cameraNumber);
		this.cameraNumber = cameraNumber;
	}

	int getCameraNumber() {
		return this.cameraNumber;
	}

	String getErrorCode() {
		return TRUSTED_MAPPING_REQUIRED;
	}

}
