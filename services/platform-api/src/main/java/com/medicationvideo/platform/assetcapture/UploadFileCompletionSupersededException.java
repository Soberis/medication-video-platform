package com.medicationvideo.platform.assetcapture;

final class UploadFileCompletionSupersededException extends RuntimeException {

	UploadFileCompletionSupersededException() {
		super("The upload file completion attempt was superseded by batch termination.");
	}
}
