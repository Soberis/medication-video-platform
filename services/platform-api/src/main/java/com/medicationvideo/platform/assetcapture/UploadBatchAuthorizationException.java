package com.medicationvideo.platform.assetcapture;

final class UploadBatchAuthorizationException extends RuntimeException {

	UploadBatchAuthorizationException() {
		super("The authenticated project cannot initialize this upload batch.");
	}
}
