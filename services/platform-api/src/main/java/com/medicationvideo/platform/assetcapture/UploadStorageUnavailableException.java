package com.medicationvideo.platform.assetcapture;

class UploadStorageUnavailableException extends RuntimeException {

	UploadStorageUnavailableException(Throwable cause) {
		super("Object storage is temporarily unavailable.", cause);
	}
}
