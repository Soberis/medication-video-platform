package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UploadPartReconciliationService {

	private final UploadBatchRepository batchRepository;

	private final UploadFileRepository fileRepository;

	private final UploadPartRepository partRepository;

	private final MultipartObjectStorage objectStorage;

	UploadPartReconciliationService(UploadBatchRepository batchRepository, UploadFileRepository fileRepository,
			UploadPartRepository partRepository, MultipartObjectStorage objectStorage) {
		this.batchRepository = batchRepository;
		this.fileRepository = fileRepository;
		this.partRepository = partRepository;
		this.objectStorage = objectStorage;
	}

	@Transactional
	UploadPart confirm(UUID projectId, UUID batchId, UUID clientFileId, int partNumber) {
		var context = requireContext(projectId, batchId, clientFileId);
		if (partNumber > context.file().getTotalParts()) {
			throw new UploadMultipartException("UPLOAD_PART_OUT_OF_RANGE",
					"The part number exceeds the file manifest.");
		}
		var part = this.partRepository.findByUploadFileIdAndPartNumber(context.file().getUploadFileId(), partNumber)
			.orElseThrow(() -> new UploadMultipartException("UPLOAD_PART_NOT_AUTHORIZED",
					"Authorize the upload part before confirming it."));
		MultipartObjectStorage.StoredPart storedPart;
		try {
			storedPart = this.objectStorage.findPart(context.file().getMultipartUpload(), partNumber)
				.orElseThrow(() -> new UploadMultipartException("UPLOAD_PART_NOT_FOUND_IN_STORAGE",
						"Object storage does not contain the upload part."));
		}
		catch (MultipartObjectStorageException exception) {
			throw new UploadStorageUnavailableException(exception);
		}
		part.confirm(storedPart, context.now());
		return this.partRepository.saveAndFlush(part);
	}

	@Transactional
	UploadPartProgressResponse reconcile(UUID projectId, UUID batchId, UUID clientFileId) {
		var context = requireContext(projectId, batchId, clientFileId);
		var parts = this.partRepository.findAllByUploadFileIdOrderByPartNumber(context.file().getUploadFileId());
		List<MultipartObjectStorage.StoredPart> storedParts;
		try {
			storedParts = this.objectStorage.listParts(context.file().getMultipartUpload());
		}
		catch (MultipartObjectStorageException exception) {
			throw new UploadStorageUnavailableException(exception);
		}

		var trackedByNumber = new HashMap<Integer, UploadPart>();
		parts.forEach(part -> trackedByNumber.put(part.getPartNumber(), part));
		var storedNumbers = new HashSet<Integer>();
		for (var storedPart : storedParts) {
			if (!storedNumbers.add(storedPart.partNumber())) {
				throw storageDiverged();
			}
			var trackedPart = trackedByNumber.get(storedPart.partNumber());
			if (trackedPart == null) {
				throw storageDiverged();
			}
			trackedPart.confirm(storedPart, context.now());
		}
		for (var trackedPart : parts) {
			if (trackedPart.getStatus() == UploadPartStatus.UPLOADED
					&& !storedNumbers.contains(trackedPart.getPartNumber())) {
				throw storageDiverged();
			}
		}
		this.partRepository.saveAllAndFlush(parts);
		return UploadPartProgressResponse.from(context.file(), parts);
	}

	@Transactional(readOnly = true)
	UploadPartProgressResponse getProgress(UUID projectId, UUID batchId, UUID clientFileId) {
		var batch = this.batchRepository.findByBatchIdAndProjectId(batchId, projectId)
			.orElseThrow(UploadBatchNotFoundException::new);
		var file = this.fileRepository.findByBatchIdAndClientFileId(batchId, clientFileId)
			.orElseThrow(UploadFileNotFoundException::new);
		if (batch.getStatus() != UploadBatchStatus.UPLOADING || !file.hasMultipartSession()
				|| file.getStatus() != UploadFileStatus.UPLOADING) {
			throw new UploadMultipartException("UPLOAD_FILE_NOT_UPLOADING",
					"The upload file has not started uploading.");
		}
		return UploadPartProgressResponse.from(file,
				this.partRepository.findAllByUploadFileIdOrderByPartNumber(file.getUploadFileId()));
	}

	private UploadContext requireContext(UUID projectId, UUID batchId, UUID clientFileId) {
		var batch = this.batchRepository.findForFileRegistration(batchId, projectId)
			.orElseThrow(UploadBatchNotFoundException::new);
		var file = this.fileRepository.findForUploadCommand(batchId, clientFileId)
			.orElseThrow(UploadFileNotFoundException::new);
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		if (!batch.getExpiresAt().isAfter(now)) {
			throw new UploadMultipartException("UPLOAD_BATCH_EXPIRED", "The upload batch has expired.");
		}
		if (batch.getStatus() != UploadBatchStatus.UPLOADING) {
			throw new UploadMultipartException("UPLOAD_BATCH_NOT_UPLOADING",
					"The upload batch has not started uploading.");
		}
		if (!file.hasMultipartSession() || file.getStatus() != UploadFileStatus.UPLOADING) {
			throw new UploadMultipartException("UPLOAD_FILE_NOT_UPLOADING",
					"The upload file has not started uploading.");
		}
		return new UploadContext(file, now);
	}

	private static UploadMultipartException storageDiverged() {
		return new UploadMultipartException("UPLOAD_PART_STORAGE_DIVERGED",
				"Object storage parts do not match the platform upload state.");
	}

	private record UploadContext(UploadFile file, Instant now) {
	}

}
