package com.medicationvideo.platform.assetcapture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.iamproject.PlatformActor;

@Service
class UploadBatchService {

	private final UploadBatchRepository repository;

	private final ExternalVideoIngestManifestRepository externalManifestRepository;

	private final JdbcTemplate jdbcTemplate;

	private final Duration batchTtl;

	UploadBatchService(UploadBatchRepository repository,
			ExternalVideoIngestManifestRepository externalManifestRepository,
			@Value("${platform.upload.batch-ttl:PT24H}") Duration batchTtl, JdbcTemplate jdbcTemplate) {
		if (batchTtl.isZero() || batchTtl.isNegative()) {
			throw new IllegalArgumentException("Upload batch TTL must be positive.");
		}
		this.repository = repository;
		this.externalManifestRepository = externalManifestRepository;
		this.jdbcTemplate = jdbcTemplate;
		this.batchTtl = batchTtl;
	}

	@Transactional
	UploadBatch initialize(PlatformActor actor, String idempotencyKey, UploadBatchInitRequest request) {
		var sourceType = request.uploadSourceType() == null ? UploadSourceType.HOSPITAL_REMOTE
				: request.uploadSourceType();
		var fingerprint = fingerprint(request, sourceType);
		lockIdempotencyKey(request.projectId(), idempotencyKey);
		var existing = this.repository.findByProjectIdAndIdempotencyKey(request.projectId(), idempotencyKey);
		if (existing.isPresent()) {
			return requireSameRequest(existing.get(), fingerprint);
		}
		var externalManifest = validateSourceRequest(request, sourceType, idempotencyKey);
		// A manifest row is pessimistically locked by validateSourceRequest. Re-read
		// the idempotency key after that lock: another request with the same key may
		// have committed while this request was waiting for the manifest lock.
		existing = this.repository.findByProjectIdAndIdempotencyKey(request.projectId(), idempotencyKey);
		if (existing.isPresent()) {
			return requireSameRequest(existing.get(), fingerprint);
		}

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var batch = UploadBatch.initiate(request.projectId(), idempotencyKey, fingerprint, request.deviceId(),
				sourceType, actor.actorId(), request.fileCount(), request.totalSizeBytes(), request.manifestSha256(),
				request.externalManifestId(), now.plus(this.batchTtl), now);

		var saved = this.repository.saveAndFlush(batch);
		if (externalManifest != null) {
			externalManifest.markBound(now);
			this.externalManifestRepository.saveAndFlush(externalManifest);
		}
		return saved;
	}

	private static UploadBatch requireSameRequest(UploadBatch batch, String fingerprint) {
		if (!MessageDigest.isEqual(batch.getRequestFingerprint().getBytes(StandardCharsets.US_ASCII),
				fingerprint.getBytes(StandardCharsets.US_ASCII))) {
			throw new UploadBatchConflictException();
		}
		return batch;
	}

	private ExternalVideoIngestManifest validateSourceRequest(UploadBatchInitRequest request,
			UploadSourceType sourceType, String idempotencyKey) {
		if (sourceType == UploadSourceType.PLATFORM_LOCAL && request.fileCount() != 1) {
			throw new UploadBatchInputException("PLATFORM_LOCAL_SINGLE_VIDEO_REQUIRED",
					"Each platform-local upload batch must contain exactly one video.");
		}
		if (sourceType == UploadSourceType.PLATFORM_LOCAL && !"PLATFORM_LOCAL".equals(request.deviceId())) {
			throw new UploadBatchInputException("PLATFORM_LOCAL_DEVICE_INVALID",
					"Platform-local uploads use the server-defined PLATFORM_LOCAL source identifier.");
		}
		ExternalVideoIngestManifest externalManifest = null;
		if (request.externalManifestId() != null) {
			if (sourceType != UploadSourceType.HOSPITAL_REMOTE) {
				throw new UploadBatchInputException("EXTERNAL_MANIFEST_SOURCE_UNSUPPORTED",
						"An external ingest manifest can only be bound to a hospital-remote upload batch.");
			}
			externalManifest = this.externalManifestRepository
				.findForBinding(request.externalManifestId(), request.projectId())
				.orElseThrow(() -> new UploadBatchInputException("EXTERNAL_MANIFEST_NOT_FOUND",
						"The external ingest manifest was not found in the current project."));
			if (!externalManifest.getSourceDeviceId().equals(request.deviceId())) {
				throw new UploadBatchInputException("EXTERNAL_MANIFEST_DEVICE_MISMATCH",
						"The external manifest source_device_id must match the upload batch device_id.");
			}
			if (externalManifest.isUploadSetFinalized()) {
				throw new UploadBatchInputException("EXTERNAL_MANIFEST_FINALIZED",
						"A finalized external capture group cannot accept another upload batch.");
			}
			if (externalManifest.getStatus() == ExternalVideoIngestManifestStatus.BOUND
					&& this.repository.findByProjectIdAndIdempotencyKey(request.projectId(), idempotencyKey)
						.isEmpty()) {
				throw new UploadBatchInputException("EXTERNAL_MANIFEST_ALREADY_BOUND",
						"The external ingest manifest is already bound to an upload batch.");
			}
		}
		return externalManifest;
	}

	private void lockIdempotencyKey(java.util.UUID projectId, String idempotencyKey) {
		this.jdbcTemplate.execute((java.sql.Connection connection) -> {
			try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
				statement.setString(1, projectId + "\nUPLOAD_BATCH\n" + idempotencyKey);
				statement.execute();
				return null;
			}
		});
	}

	private static String fingerprint(UploadBatchInitRequest request, UploadSourceType sourceType) {
		var canonical = String.join("\n", request.projectId().toString(), request.deviceId(), sourceType.name(),
				Integer.toString(request.fileCount()), Long.toString(request.totalSizeBytes()),
				request.manifestSha256(),
				request.externalManifestId() == null ? "" : request.externalManifestId().toString());
		try {
			var digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available in this Java runtime.", exception);
		}
	}

}
