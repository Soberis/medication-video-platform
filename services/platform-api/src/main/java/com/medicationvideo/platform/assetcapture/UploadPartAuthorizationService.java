package com.medicationvideo.platform.assetcapture;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UploadPartAuthorizationService {

	private final UploadBatchRepository batchRepository;
	private final UploadFileRepository fileRepository;
	private final UploadPartRepository partRepository;
	private final MultipartObjectStorage objectStorage;
	private final Duration authorizationTtl;

	UploadPartAuthorizationService(
			UploadBatchRepository batchRepository,
			UploadFileRepository fileRepository,
			UploadPartRepository partRepository,
			MultipartObjectStorage objectStorage,
			@Value("${platform.upload.part-authorization-ttl}") Duration authorizationTtl) {
		this.batchRepository = batchRepository;
		this.fileRepository = fileRepository;
		this.partRepository = partRepository;
		this.objectStorage = objectStorage;
		if (authorizationTtl.isZero()
				|| authorizationTtl.isNegative()
				|| authorizationTtl.compareTo(Duration.ofDays(7)) > 0) {
			throw new IllegalArgumentException("Part authorization TTL must be positive and no longer than seven days.");
		}
		this.authorizationTtl = authorizationTtl;
	}

	@Transactional
	AuthorizedPart authorize(
			UUID projectId,
			UUID batchId,
			UUID clientFileId,
			int partNumber,
			UploadPartAuthorizationRequest request) {
		var batch = this.batchRepository.findForFileRegistration(batchId, projectId)
				.orElseThrow(UploadBatchNotFoundException::new);
		var file = this.fileRepository.findForUploadCommand(batchId, clientFileId)
				.orElseThrow(UploadFileNotFoundException::new);
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		requireAuthorizedState(batch, file, partNumber, now);

		var expectedSize = expectedPartSize(file, partNumber);
		var expiresAt = earlier(now.plus(this.authorizationTtl), batch.getExpiresAt());
		if (!expiresAt.isAfter(now)) {
			throw new UploadMultipartException("UPLOAD_BATCH_EXPIRED", "The upload batch has expired.");
		}

		var existingPart = this.partRepository.findByUploadFileIdAndPartNumber(file.getUploadFileId(), partNumber);
		var part = existingPart.orElseGet(() -> UploadPart.authorize(
				file.getUploadFileId(),
				partNumber,
				expectedSize,
				request.sha256(),
				expiresAt,
				now));
		if (existingPart.isPresent()) {
			requireSamePart(part, expectedSize, request.sha256());
			if (part.getStatus() == UploadPartStatus.UPLOADED) {
				throw new UploadMultipartException(
						"UPLOAD_PART_ALREADY_CONFIRMED",
						"A confirmed upload part cannot be authorized again.");
			}
			part.reauthorize(expiresAt, now);
		}

		URI uploadUrl;
		try {
			uploadUrl = this.objectStorage.authorizePart(
					file.getMultipartUpload(),
					partNumber,
					Duration.between(now, expiresAt));
		}
		catch (MultipartObjectStorageException exception) {
			throw new UploadStorageUnavailableException(exception);
		}

		this.partRepository.saveAndFlush(part);
		file.markUploading(now);
		batch.markUploading(now);
		return new AuthorizedPart(file, part, uploadUrl);
	}

	private static void requireAuthorizedState(
			UploadBatch batch,
			UploadFile file,
			int partNumber,
			Instant now) {
		if (!batch.getExpiresAt().isAfter(now)) {
			throw new UploadMultipartException("UPLOAD_BATCH_EXPIRED", "The upload batch has expired.");
		}
		if (batch.getStatus() != UploadBatchStatus.INITIATED
				&& batch.getStatus() != UploadBatchStatus.UPLOADING) {
			throw new UploadMultipartException(
					"UPLOAD_BATCH_NOT_UPLOADABLE",
					"The upload batch does not accept multipart operations.");
		}
		if (!file.hasMultipartSession()) {
			throw new UploadMultipartException(
					"UPLOAD_MULTIPART_SESSION_REQUIRED",
					"Create the multipart session before authorizing a part.");
		}
		if (file.getStatus() != UploadFileStatus.SESSION_CREATED
				&& file.getStatus() != UploadFileStatus.UPLOADING) {
			throw new UploadMultipartException(
					"UPLOAD_FILE_NOT_UPLOADABLE",
					"The upload file does not accept new part authorizations.");
		}
		if (partNumber > file.getTotalParts()) {
			throw new UploadMultipartException(
					"UPLOAD_PART_OUT_OF_RANGE",
					"The part number exceeds the file manifest.");
		}
	}

	private static long expectedPartSize(UploadFile file, int partNumber) {
		if (partNumber < file.getTotalParts()) {
			return file.getPartSizeBytes();
		}
		return file.getSizeBytes() - file.getPartSizeBytes() * (file.getTotalParts() - 1L);
	}

	private static void requireSamePart(UploadPart part, long expectedSize, String sha256) {
		if (part.getExpectedSizeBytes() != expectedSize || !part.getSha256().equals(sha256)) {
			throw new UploadMultipartException(
					"UPLOAD_PART_HASH_CONFLICT",
					"The upload part was already registered with different immutable metadata.");
		}
	}

	private static Instant earlier(Instant first, Instant second) {
		return first.isBefore(second) ? first : second;
	}

	record AuthorizedPart(UploadFile file, UploadPart part, URI uploadUrl) {
	}
}
