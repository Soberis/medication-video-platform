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

@Service
class UploadBatchService {

	private final UploadBatchRepository repository;
	private final Duration batchTtl;

	UploadBatchService(
			UploadBatchRepository repository,
			@Value("${platform.upload.batch-ttl:PT24H}") Duration batchTtl) {
		if (batchTtl.isZero() || batchTtl.isNegative()) {
			throw new IllegalArgumentException("Upload batch TTL must be positive.");
		}
		this.repository = repository;
		this.batchTtl = batchTtl;
	}

	UploadBatch initialize(String idempotencyKey, UploadBatchInitRequest request) {
		var fingerprint = fingerprint(request);
		var existing = this.repository.findByProjectIdAndIdempotencyKey(request.projectId(), idempotencyKey);
		if (existing.isPresent()) {
			return requireSameRequest(existing.get(), fingerprint);
		}

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var batch = UploadBatch.initiate(
				request.projectId(),
				idempotencyKey,
				fingerprint,
				request.deviceId(),
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

	private static String fingerprint(UploadBatchInitRequest request) {
		var canonical = String.join(
				"\n",
				request.projectId().toString(),
				request.deviceId(),
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
