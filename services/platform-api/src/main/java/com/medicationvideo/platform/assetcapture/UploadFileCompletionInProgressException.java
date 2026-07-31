package com.medicationvideo.platform.assetcapture;

class UploadFileCompletionInProgressException extends RuntimeException {

	UploadFileCompletionInProgressException() {
		super("Another request is already completing the upload file.");
	}

}
