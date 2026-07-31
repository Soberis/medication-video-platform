package com.medicationvideo.platform.assetcapture;

final class UploadBatchConflictException extends RuntimeException {

	UploadBatchConflictException() {
		super("The idempotency key has already been used with a different request.");
	}

}
