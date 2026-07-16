package com.medicationvideo.platform.assetcapture;

import org.springframework.stereotype.Service;

@Service
class UploadFileCompletionService {

	private final UploadFileCompletionStateService stateService;
	private final MultipartObjectStorage objectStorage;

	UploadFileCompletionService(
			UploadFileCompletionStateService stateService,
			MultipartObjectStorage objectStorage) {
		this.stateService = stateService;
		this.objectStorage = objectStorage;
	}

	UploadFileCompletionResponse complete(
			java.util.UUID projectId,
			java.util.UUID batchId,
			java.util.UUID clientFileId) {
		var attempt = this.stateService.begin(projectId, batchId, clientFileId);
		if (attempt.alreadyCompleted()) {
			return attempt.completedResponse();
		}

		try {
			var completedObject = this.objectStorage.findCompletedObject(attempt.upload()).orElse(null);
			if (completedObject == null) {
				var storedParts = this.objectStorage.listParts(attempt.upload());
				try {
					storedParts = this.stateService.verifyAndConfirmParts(
							attempt.uploadFileId(),
							attempt.attemptId(),
							storedParts);
				}
				catch (UploadMultipartException exception) {
					this.stateService.resumeUploading(attempt.uploadFileId(), attempt.attemptId());
					throw exception;
				}
				completedObject = this.objectStorage.complete(attempt.upload(), storedParts);
			}

			String observedSha256 = null;
			if (completedObject.sizeBytes() == attempt.expectedSizeBytes()) {
				observedSha256 = this.objectStorage.calculateSha256(attempt.upload());
			}
			var result = this.stateService.finish(
					attempt.uploadFileId(),
					attempt.attemptId(),
					completedObject,
					observedSha256);
			if (result.failed()) {
				throw new UploadMultipartException(result.errorCode(), result.errorMessage());
			}
			return result.response();
		}
		catch (MultipartObjectStorageException exception) {
			this.stateService.releaseLease(attempt.uploadFileId(), attempt.attemptId());
			throw new UploadStorageUnavailableException(exception);
		}
	}
}
