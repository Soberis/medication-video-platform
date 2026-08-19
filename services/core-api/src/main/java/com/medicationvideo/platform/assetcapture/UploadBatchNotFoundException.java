package com.medicationvideo.platform.assetcapture;

final class UploadBatchNotFoundException extends RuntimeException {

	UploadBatchNotFoundException() {
		super("The upload batch was not found.");
	}

}
