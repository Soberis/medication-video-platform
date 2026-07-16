package com.medicationvideo.platform.assetcapture;

final class UploadBatchAuthorizationException extends RuntimeException {

	UploadBatchAuthorizationException() {
		this("The authenticated identity cannot access this upload batch.");
	}

	UploadBatchAuthorizationException(String message) {
		super(message);
	}
}
