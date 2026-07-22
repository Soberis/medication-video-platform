package com.medicationvideo.platform.assetcapture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.medicationvideo.platform.iamproject.PlatformActor;

@Service
class UploadBatchService {

	private final UploadBatchRepository repository;
	private final AssetFolderRepository folderRepository;
	private final Duration batchTtl;

	UploadBatchService(
			UploadBatchRepository repository,
			AssetFolderRepository folderRepository,
			@Value("${platform.upload.batch-ttl:PT24H}") Duration batchTtl) {
		if (batchTtl.isZero() || batchTtl.isNegative()) {
			throw new IllegalArgumentException("Upload batch TTL must be positive.");
		}
		this.repository = repository;
		this.folderRepository = folderRepository;
		this.batchTtl = batchTtl;
	}

	UploadBatch initialize(PlatformActor actor, String idempotencyKey, UploadBatchInitRequest request) {
		var sourceType = request.uploadSourceType() == null
				? UploadSourceType.HOSPITAL_REMOTE
				: request.uploadSourceType();
		var fingerprint = fingerprint(request, sourceType);
		var existing = this.repository.findByProjectIdAndIdempotencyKey(request.projectId(), idempotencyKey);
		if (existing.isPresent()) {
			return requireSameRequest(existing.get(), fingerprint);
		}
		validateSourceRequest(request, sourceType);

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var batch = UploadBatch.initiate(
				request.projectId(),
				idempotencyKey,
				fingerprint,
				request.deviceId(),
				sourceType,
				request.assetFolderId(),
				actor.actorId(),
				request.fileCount(),
				request.totalSizeBytes(),
				request.manifestSha256(),
				now.plus(this.batchTtl),
				now);

		try {
			return this.repository.saveAndFlush(batch);
		}
		catch (DataIntegrityViolationException exception) {
			var concurrent = this.repository.findByProjectIdAndIdempotencyKey(
					request.projectId(), idempotencyKey);
			if (concurrent.isPresent()) {
				return requireSameRequest(concurrent.get(), fingerprint);
			}
			throw exception;
		}
	}

	private static UploadBatch requireSameRequest(UploadBatch batch, String fingerprint) {
		if (!MessageDigest.isEqual(
				batch.getRequestFingerprint().getBytes(StandardCharsets.US_ASCII),
				fingerprint.getBytes(StandardCharsets.US_ASCII))) {
			throw new UploadBatchConflictException();
		}
		return batch;
	}

	private void validateSourceRequest(UploadBatchInitRequest request, UploadSourceType sourceType) {
		if (sourceType == UploadSourceType.PLATFORM_LOCAL && request.fileCount() != 1) {
			throw new UploadBatchInputException(
					"PLATFORM_LOCAL_SINGLE_VIDEO_REQUIRED",
					"Each platform-local upload batch must contain exactly one video.");
		}
		if (sourceType == UploadSourceType.PLATFORM_LOCAL && !"PLATFORM_LOCAL".equals(request.deviceId())) {
			throw new UploadBatchInputException(
					"PLATFORM_LOCAL_DEVICE_INVALID",
					"Platform-local uploads use the server-defined PLATFORM_LOCAL source identifier.");
		}
		if (sourceType != UploadSourceType.PLATFORM_LOCAL && request.assetFolderId() != null) {
			throw new UploadBatchInputException(
					"UPLOAD_SOURCE_FOLDER_UNSUPPORTED",
					"An asset folder can only be selected for a platform-local upload.");
		}
		if (request.assetFolderId() != null
				&& this.folderRepository.findByFolderIdAndProjectId(request.assetFolderId(), request.projectId()).isEmpty()) {
			throw new UploadBatchInputException(
					"ASSET_FOLDER_NOT_FOUND",
					"The selected asset folder was not found in the current project.");
		}
	}

	private static String fingerprint(UploadBatchInitRequest request, UploadSourceType sourceType) {
		var canonical = String.join(
				"\n",
				request.projectId().toString(),
				request.deviceId(),
				sourceType.name(),
				request.assetFolderId() == null ? "" : request.assetFolderId().toString(),
				Integer.toString(request.fileCount()),
				Long.toString(request.totalSizeBytes()),
				request.manifestSha256());
		try {
			var digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available in this Java runtime.", exception);
		}
	}
}
