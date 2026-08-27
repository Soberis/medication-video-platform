package com.medicationvideo.platform.assetcapture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UploadFileService {

	private static final int MAX_PARTS = 10_000;

	private final UploadBatchRepository batchRepository;

	private final UploadFileRepository fileRepository;

	private final ExternalVideoIngestManifestVideoRepository externalManifestVideoRepository;

	private final ExternalVideoIngestManifestRepository externalManifestRepository;

	UploadFileService(UploadBatchRepository batchRepository, UploadFileRepository fileRepository,
			ExternalVideoIngestManifestVideoRepository externalManifestVideoRepository,
			ExternalVideoIngestManifestRepository externalManifestRepository) {
		this.batchRepository = batchRepository;
		this.fileRepository = fileRepository;
		this.externalManifestVideoRepository = externalManifestVideoRepository;
		this.externalManifestRepository = externalManifestRepository;
	}

	@Transactional
	UploadFile register(UUID projectId, UUID batchId, UUID clientFileId, String idempotencyKey,
			UploadFileRegistrationRequest request) {
		var fingerprint = fingerprint(batchId, clientFileId, request);
		var batch = this.batchRepository.findForFileRegistration(batchId, projectId)
			.orElseThrow(UploadBatchNotFoundException::new);

		var byKey = this.fileRepository.findByBatchIdAndIdempotencyKey(batchId, idempotencyKey);
		if (byKey.isPresent()) {
			return requireSameRegistration(byKey.get(), clientFileId, fingerprint, "IDEMPOTENCY_KEY_REUSED");
		}

		var byClientFile = this.fileRepository.findByBatchIdAndClientFileId(batchId, clientFileId);
		if (byClientFile.isPresent()) {
			return requireSameRegistration(byClientFile.get(), clientFileId, fingerprint, "UPLOAD_FILE_ID_REUSED");
		}

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		if (!batch.getExpiresAt().isAfter(now)) {
			throw new UploadFileRegistrationException("UPLOAD_BATCH_EXPIRED", "The upload batch has expired.");
		}
		if (batch.getStatus() != UploadBatchStatus.INITIATED) {
			throw new UploadFileRegistrationException("UPLOAD_BATCH_NOT_ACCEPTING_FILES",
					"The upload batch no longer accepts file manifest entries.");
		}
		if (batch.getUploadSourceType() == UploadSourceType.PLATFORM_LOCAL
				&& request.fileKind() != UploadFileKind.VIDEO) {
			throw new UploadFileRegistrationException("PLATFORM_LOCAL_VIDEO_REQUIRED",
					"A platform-local upload batch accepts exactly one VIDEO file.");
		}
		if (batch.getUploadSourceType() == UploadSourceType.PLATFORM_LOCAL && request.fileKind() == UploadFileKind.VIDEO
				&& (request.fileName() == null || request.fileName().isBlank())) {
			throw new UploadFileRegistrationException("PLATFORM_LOCAL_FILE_NAME_REQUIRED",
					"A platform-local video registration must include the original file name.");
		}
		if (batch.getExternalManifestId() != null && request.fileKind() == UploadFileKind.VIDEO) {
			var manifest = this.externalManifestRepository.findById(batch.getExternalManifestId())
				.orElseThrow(() -> new UploadFileRegistrationException("EXTERNAL_MANIFEST_VIDEO_MISMATCH",
						"The bound external manifest no longer exists."));
			if (manifest.isUploadSetFinalized()) {
				throw new UploadFileRegistrationException("EXTERNAL_MANIFEST_FINALIZED",
						"A finalized external capture group cannot accept another upload file.");
			}
			var expected = this.externalManifestVideoRepository
				.findByManifestIdAndClientFileId(batch.getExternalManifestId(), clientFileId)
				.orElseThrow(() -> new UploadFileRegistrationException("EXTERNAL_MANIFEST_VIDEO_MISMATCH",
						"The video client_file_id is not declared by the bound external manifest."));
			if (expected.getDeclaredSizeBytes() != request.sizeBytes() || !expected.getSha256().equals(request.sha256())
					|| !expected.getFileName().equals(request.fileName())) {
				throw new UploadFileRegistrationException("EXTERNAL_MANIFEST_VIDEO_MISMATCH",
						"The video registration does not match the bound external manifest.");
			}
		}

		var registeredCount = this.fileRepository.countByBatchId(batchId);
		var registeredSize = this.fileRepository.sumSizeBytesByBatchId(batchId);
		if (registeredCount >= batch.getFileCount()
				|| exceedsDeclaredSize(registeredSize, request.sizeBytes(), batch.getTotalSizeBytes())) {
			throw new UploadFileRegistrationException("UPLOAD_BATCH_LIMIT_EXCEEDED",
					"The file would exceed the batch declaration.");
		}

		var totalParts = calculateTotalParts(request.sizeBytes(), request.partSizeBytes());
		var file = UploadFile.register(batchId, clientFileId, idempotencyKey, fingerprint, request, totalParts, now);
		return this.fileRepository.saveAndFlush(file);
	}

	private static UploadFile requireSameRegistration(UploadFile file, UUID clientFileId, String fingerprint,
			String conflictCode) {
		if (!file.getClientFileId().equals(clientFileId)
				|| !MessageDigest.isEqual(file.getRequestFingerprint().getBytes(StandardCharsets.US_ASCII),
						fingerprint.getBytes(StandardCharsets.US_ASCII))) {
			throw new UploadFileRegistrationException(conflictCode,
					"The immutable upload file registration conflicts with an existing request.");
		}
		return file;
	}

	private static boolean exceedsDeclaredSize(long registeredSize, long nextSize, long declaredSize) {
		return registeredSize > declaredSize - nextSize;
	}

	private static int calculateTotalParts(long sizeBytes, long partSizeBytes) {
		var totalParts = Math.floorDiv(sizeBytes - 1, partSizeBytes) + 1;
		if (totalParts > MAX_PARTS) {
			throw new UploadFileRegistrationException("UPLOAD_FILE_TOO_MANY_PARTS",
					"The planned part count exceeds the supported maximum.");
		}
		return Math.toIntExact(totalParts);
	}

	private static String fingerprint(UUID batchId, UUID clientFileId, UploadFileRegistrationRequest request) {
		var canonical = String.join("\n", batchId.toString(), clientFileId.toString(), request.fileKind().name(),
				request.contentType(), Long.toString(request.sizeBytes()), request.sha256(),
				Long.toString(request.partSizeBytes()), request.fileName() == null ? "" : request.fileName());
		try {
			var digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available in this Java runtime.", exception);
		}
	}

}
