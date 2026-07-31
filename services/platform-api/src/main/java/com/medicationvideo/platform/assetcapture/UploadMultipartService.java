package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class UploadMultipartService {

	private final UploadBatchRepository batchRepository;

	private final UploadFileRepository fileRepository;

	private final MultipartObjectStorage objectStorage;

	private final TransactionTemplate transactionTemplate;

	UploadMultipartService(UploadBatchRepository batchRepository, UploadFileRepository fileRepository,
			MultipartObjectStorage objectStorage, TransactionTemplate transactionTemplate) {
		this.batchRepository = batchRepository;
		this.fileRepository = fileRepository;
		this.objectStorage = objectStorage;
		this.transactionTemplate = transactionTemplate;
	}

	UploadFile start(UUID projectId, UUID batchId, UUID clientFileId) {
		var createdUpload = new AtomicReference<MultipartObjectStorage.CreatedUpload>();
		try {
			var file = this.transactionTemplate.execute(status -> {
				var batch = this.batchRepository.findForFileRegistration(batchId, projectId)
					.orElseThrow(UploadBatchNotFoundException::new);
				var uploadFile = this.fileRepository.findForUploadCommand(batchId, clientFileId)
					.orElseThrow(UploadFileNotFoundException::new);
				var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
				requireActiveBatch(batch, now);
				requireCompleteManifest(batch);

				if (uploadFile.hasMultipartSession()) {
					return uploadFile;
				}

				var objectKey = "projects/%s/uploads/%s/%s".formatted(projectId, batchId, uploadFile.getUploadFileId());
				var created = this.objectStorage.create(objectKey, uploadFile.getContentType());
				createdUpload.set(created);
				uploadFile.attachMultipartSession(created, now);
				return this.fileRepository.saveAndFlush(uploadFile);
			});
			if (file == null) {
				throw new IllegalStateException("Multipart transaction returned no upload file.");
			}
			return file;
		}
		catch (RuntimeException exception) {
			abortAfterFailedPersistence(createdUpload.get(), exception);
			if (exception instanceof MultipartObjectStorageException) {
				throw new UploadStorageUnavailableException(exception);
			}
			throw exception;
		}
	}

	private void requireCompleteManifest(UploadBatch batch) {
		var fileCount = this.fileRepository.countByBatchId(batch.getBatchId());
		var totalSize = this.fileRepository.sumSizeBytesByBatchId(batch.getBatchId());
		if (fileCount != batch.getFileCount() || totalSize != batch.getTotalSizeBytes()) {
			throw new UploadMultipartException("UPLOAD_BATCH_MANIFEST_INCOMPLETE",
					"Every declared file must be registered before multipart upload starts.");
		}
	}

	private static void requireActiveBatch(UploadBatch batch, Instant now) {
		if (!batch.getExpiresAt().isAfter(now)) {
			throw new UploadMultipartException("UPLOAD_BATCH_EXPIRED", "The upload batch has expired.");
		}
		if (batch.getStatus() != UploadBatchStatus.INITIATED && batch.getStatus() != UploadBatchStatus.UPLOADING) {
			throw new UploadMultipartException("UPLOAD_BATCH_NOT_UPLOADABLE",
					"The upload batch does not accept multipart operations.");
		}
	}

	private void abortAfterFailedPersistence(MultipartObjectStorage.CreatedUpload createdUpload,
			RuntimeException originalFailure) {
		if (createdUpload == null || originalFailure instanceof MultipartObjectStorageException) {
			return;
		}
		try {
			this.objectStorage.abort(createdUpload);
		}
		catch (RuntimeException abortFailure) {
			originalFailure.addSuppressed(abortFailure);
		}
	}

}
