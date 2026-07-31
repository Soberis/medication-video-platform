package com.medicationvideo.platform.assetcapture;

class UploadFileNotFoundException extends RuntimeException {

	UploadFileNotFoundException() {
		super("The upload file was not found in the authenticated project.");
	}

}
