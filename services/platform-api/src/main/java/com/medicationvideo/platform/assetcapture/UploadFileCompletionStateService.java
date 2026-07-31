package com.medicationvideo.platform.assetcapture;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UploadFileCompletionStateService {

	private final UploadBatchRepository batchRepository;

	private final UploadFileRepository fileRepository;

	private final UploadPartRepository partRepository;

	private final Duration completionLease;

	UploadFileCompletionStateService(UploadBatchRepository batchRepository, UploadFileRepository fileRepository,
			UploadPartRepository partRepository,
			@Value("${platform.upload.file-completion-lease}") Duration completionLease) {
		this.batchRepository = batchRepository;
		this.fileRepository = fileRepository;
		this.partRepository = partRepository;
		if (completionLease.compareTo(Duration.ofSeconds(10)) < 0
				|| completionLease.compareTo(Duration.ofHours(1)) > 0) {
			throw new IllegalArgumentException("File completion lease must be between ten seconds and one hour.");
		}
		this.completionLease = completionLease;
	}

	@Transactional
	CompletionAttempt begin(UUID projectId, UUID batchId, UUID clientFileId) {
		var batch = this.batchRepository.findForFileRegistration(batchId, projectId)
			.orElseThrow(UploadBatchNotFoundException::new);
		var file = this.fileRepository.findForUploadCommand(batchId, clientFileId)
			.orElseThrow(UploadFileNotFoundException::new);
		if (file.getStatus() == UploadFileStatus.UPLOADED) {
			return CompletionAttempt.completed(UploadFileCompletionResponse.from(file));
		}
		if (file.getStatus() == UploadFileStatus.FAILED) {
			throw previousCompletionFailure(file);
		}

		var now = now();
		if (!batch.getExpiresAt().isAfter(now)) {
			throw new UploadMultipartException("UPLOAD_BATCH_EXPIRED", "The upload batch has expired.");
		}
		if (batch.getStatus() != UploadBatchStatus.UPLOADING) {
			throw new UploadMultipartException("UPLOAD_BATCH_NOT_UPLOADING",
					"The upload batch has not started uploading.");
		}
		if (file.getStatus() == UploadFileStatus.COMPLETING && file.hasActiveCompletionLease(now)) {
			throw new UploadFileCompletionInProgressException();
		}
		if (file.getStatus() != UploadFileStatus.UPLOADING && file.getStatus() != UploadFileStatus.COMPLETING) {
			throw new UploadMultipartException("UPLOAD_FILE_NOT_UPLOADING",
					"The upload file has not started uploading.");
		}

		var attemptId = UUID.randomUUID();
		file.startCompletion(attemptId, now.plus(this.completionLease), now);
		this.fileRepository.saveAndFlush(file);
		return CompletionAttempt.active(attemptId, file.getUploadFileId(), file.getMultipartUpload(),
				file.getTotalParts(), file.getSizeBytes(), file.getSha256());
	}

	@Transactional
	List<MultipartObjectStorage.StoredPart> verifyAndConfirmParts(UUID uploadFileId, UUID attemptId,
			List<MultipartObjectStorage.StoredPart> storedParts) {
		var file = requireAttempt(uploadFileId, attemptId);
		var sortedParts = storedParts.stream()
			.sorted(Comparator.comparingInt(MultipartObjectStorage.StoredPart::partNumber))
			.toList();
		if (sortedParts.size() < file.getTotalParts()) {
			throw new UploadMultipartException("UPLOAD_FILE_PARTS_INCOMPLETE",
					"Object storage does not contain every declared upload part.");
		}
		if (sortedParts.size() > file.getTotalParts()) {
			throw storageDiverged();
		}

		var trackedParts = this.partRepository.findAllByUploadFileIdOrderByPartNumber(uploadFileId);
		if (trackedParts.size() != file.getTotalParts()) {
			throw new UploadMultipartException("UPLOAD_FILE_PARTS_INCOMPLETE",
					"Every declared upload part must be authorized before file completion.");
		}
		var trackedByNumber = new HashMap<Integer, UploadPart>();
		trackedParts.forEach(part -> trackedByNumber.put(part.getPartNumber(), part));

		long totalSize = 0;
		for (var index = 0; index < sortedParts.size(); index++) {
			var expectedPartNumber = index + 1;
			var storedPart = sortedParts.get(index);
			if (storedPart.partNumber() != expectedPartNumber) {
				throw new UploadMultipartException("UPLOAD_FILE_PARTS_INCOMPLETE",
						"Upload part numbers must be continuous from one through the declared total.");
			}
			var trackedPart = trackedByNumber.get(expectedPartNumber);
			if (trackedPart == null) {
				throw storageDiverged();
			}
			trackedPart.confirm(storedPart, now());
			totalSize = Math.addExact(totalSize, storedPart.sizeBytes());
		}
		if (totalSize != file.getSizeBytes()) {
			throw new UploadMultipartException("UPLOAD_FILE_PARTS_SIZE_MISMATCH",
					"The total stored part size does not match the immutable file manifest.");
		}
		this.partRepository.saveAllAndFlush(trackedParts);
		return List.copyOf(sortedParts);
	}

	@Transactional
	void resumeUploading(UUID uploadFileId, UUID attemptId) {
		var file = this.fileRepository.findForCompletion(uploadFileId).orElseThrow(UploadFileNotFoundException::new);
		if (file.matchesCompletionAttempt(attemptId)) {
			file.resumeUploading(attemptId, now());
		}
	}

	@Transactional
	void releaseLease(UUID uploadFileId, UUID attemptId) {
		var file = this.fileRepository.findForCompletion(uploadFileId).orElseThrow(UploadFileNotFoundException::new);
		if (file.matchesCompletionAttempt(attemptId)) {
			file.releaseCompletionLease(attemptId, now());
		}
	}

	@Transactional
	FinishResult finish(UUID uploadFileId, UUID attemptId, MultipartObjectStorage.CompletedObject completedObject,
			String observedSha256) {
		var file = requireAttempt(uploadFileId, attemptId);
		var now = now();
		if (completedObject.sizeBytes() != file.getSizeBytes()) {
			var errorCode = "UPLOAD_FILE_SIZE_MISMATCH";
			file.markCompletionFailed(attemptId, completedObject, null, errorCode, now);
			this.fileRepository.saveAndFlush(file);
			return FinishResult.failed(errorCode,
					"The completed object size does not match the immutable file manifest.");
		}
		if (!file.getSha256().equals(observedSha256)) {
			var errorCode = "UPLOAD_FILE_CHECKSUM_MISMATCH";
			file.markCompletionFailed(attemptId, completedObject, observedSha256, errorCode, now);
			this.fileRepository.saveAndFlush(file);
			return FinishResult.failed(errorCode,
					"The completed object SHA-256 does not match the immutable file manifest.");
		}

		file.markUploaded(attemptId, completedObject, observedSha256, now);
		this.fileRepository.saveAndFlush(file);
		return FinishResult.completed(UploadFileCompletionResponse.from(file));
	}

	private UploadFile requireAttempt(UUID uploadFileId, UUID attemptId) {
		var file = this.fileRepository.findForCompletion(uploadFileId).orElseThrow(UploadFileNotFoundException::new);
		try {
			file.requireCompletionAttempt(attemptId);
		}
		catch (IllegalStateException exception) {
			if (file.getStatus() == UploadFileStatus.ABORTED || file.getStatus() == UploadFileStatus.EXPIRED) {
				throw new UploadFileCompletionSupersededException();
			}
			throw exception;
		}
		return file;
	}

	private static UploadMultipartException previousCompletionFailure(UploadFile file) {
		return new UploadMultipartException(file.getCompletionErrorCode(),
				"The upload file previously failed final integrity verification.");
	}

	private static UploadMultipartException storageDiverged() {
		return new UploadMultipartException("UPLOAD_PART_STORAGE_DIVERGED",
				"Object storage parts do not match the platform upload state.");
	}

	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

	record CompletionAttempt(UUID attemptId, UUID uploadFileId, MultipartObjectStorage.CreatedUpload upload,
			int totalParts, long expectedSizeBytes, String expectedSha256,
			UploadFileCompletionResponse completedResponse) {

		static CompletionAttempt active(UUID attemptId, UUID uploadFileId, MultipartObjectStorage.CreatedUpload upload,
				int totalParts, long expectedSizeBytes, String expectedSha256) {
			return new CompletionAttempt(attemptId, uploadFileId, upload, totalParts, expectedSizeBytes, expectedSha256,
					null);
		}

		static CompletionAttempt completed(UploadFileCompletionResponse response) {
			return new CompletionAttempt(null, response.uploadFileId(), null, 0, 0, null, response);
		}

		boolean alreadyCompleted() {
			return this.completedResponse != null;
		}
	}

	record FinishResult(UploadFileCompletionResponse response, String errorCode, String errorMessage) {

		static FinishResult completed(UploadFileCompletionResponse response) {
			return new FinishResult(response, null, null);
		}

		static FinishResult failed(String errorCode, String errorMessage) {
			return new FinishResult(null, errorCode, errorMessage);
		}

		boolean failed() {
			return this.errorCode != null;
		}
	}

}
