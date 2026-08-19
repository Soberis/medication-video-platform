package com.medicationvideo.platform.mediaprivacy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.assetcapture.AssetCatalogQueryService;
import com.medicationvideo.platform.assetcapture.ClipAssetInputSummary;
import com.medicationvideo.platform.assetcapture.ClipAssetStatus;

@Service
class MediaJobService {

	private final MediaJobRepository repository;

	private final MediaJobAttemptRepository attemptRepository;

	private final AssetCatalogQueryService assetCatalog;

	private final MediaJobAttemptQueueService attemptQueue;

	MediaJobService(MediaJobRepository repository, MediaJobAttemptRepository attemptRepository,
			AssetCatalogQueryService assetCatalog, MediaJobAttemptQueueService attemptQueue) {
		this.repository = repository;
		this.attemptRepository = attemptRepository;
		this.assetCatalog = assetCatalog;
		this.attemptQueue = attemptQueue;
	}

	@Transactional
	MediaJob create(UUID projectId, String idempotencyKey, MediaJobRequest request) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new IllegalArgumentException("Idempotency-Key header is required.");
		}
		var fingerprint = fingerprint(projectId, request);
		var existing = this.repository.findByProjectIdAndIdempotencyKey(projectId, idempotencyKey);
		if (existing.isPresent()) {
			return requireSameRequest(existing.get(), fingerprint);
		}

		var summary = this.assetCatalog.getClipAssetInputSummary(projectId, request.clipAssetId());
		ensureProbeable(summary);

		var natural = this.repository.findByProjectIdAndJobTypeAndClipAssetIdAndInputDigestAndRecipeDigest(projectId,
				request.jobType(), summary.clipAssetId(), summary.inputDigest(), MediaProbeProfile.RECIPE_DIGEST);
		if (natural.isPresent()) {
			return requireSameRequest(natural.get(), fingerprint);
		}

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var job = MediaJob.request(projectId, summary.clipAssetId(), request.jobType(), summary.inputDigest(),
				summary.inputSizeBytes(), summary.contentType(), MediaProbeProfile.PROFILE_KEY,
				MediaProbeProfile.RECIPE_DIGEST, idempotencyKey, fingerprint, now);

		try {
			job = this.repository.saveAndFlush(job);
			job.queue(now);
			this.attemptQueue.enqueue(job, 1, now, now);
			return job;
		}
		catch (DataIntegrityViolationException exception) {
			var concurrentByIdem = this.repository.findByProjectIdAndIdempotencyKey(projectId, idempotencyKey);
			if (concurrentByIdem.isPresent()) {
				return requireSameRequest(concurrentByIdem.get(), fingerprint);
			}
			var concurrentByNatural = this.repository
				.findByProjectIdAndJobTypeAndClipAssetIdAndInputDigestAndRecipeDigest(projectId, request.jobType(),
						summary.clipAssetId(), summary.inputDigest(), MediaProbeProfile.RECIPE_DIGEST);
			if (concurrentByNatural.isPresent()) {
				return requireSameRequest(concurrentByNatural.get(), fingerprint);
			}
			throw exception;
		}
	}

	@Transactional(readOnly = true)
	MediaJob get(UUID projectId, UUID mediaJobId) {
		var job = this.repository.findById(mediaJobId)
			.orElseThrow(() -> new MediaJobNotFoundException("The media job was not found."));
		if (!job.getProjectId().equals(projectId)) {
			throw new MediaJobNotFoundException("The media job was not found.");
		}
		return job;
	}

	@Transactional(readOnly = true)
	MediaJobAttempt currentAttempt(MediaJob job) {
		return this.attemptRepository.findTopByMediaJobIdOrderByAttemptDesc(job.getMediaJobId())
			.orElseThrow(() -> new IllegalStateException("The media job has no current attempt."));
	}

	private static void ensureProbeable(ClipAssetInputSummary summary) {
		if (!summary.contentType().toLowerCase(Locale.ROOT).startsWith("video/")) {
			throw new ClipAssetNotProbeableException("The clip asset content type is not a probeable video.");
		}
		if (summary.status() != ClipAssetStatus.UPLOADED) {
			throw new ClipAssetNotProbeableException(
					"The clip asset is not in a probeable state: " + summary.status().name());
		}
	}

	private static MediaJob requireSameRequest(MediaJob job, String fingerprint) {
		if (!MessageDigest.isEqual(job.getRequestFingerprint().getBytes(StandardCharsets.US_ASCII),
				fingerprint.getBytes(StandardCharsets.US_ASCII))) {
			throw new MediaJobIdempotencyConflictException();
		}
		return job;
	}

	private static String fingerprint(UUID projectId, MediaJobRequest request) {
		var canonical = String.join("\n", projectId.toString(), request.jobType().name(),
				request.clipAssetId().toString(), MediaProbeProfile.PROFILE_KEY, MediaProbeProfile.RECIPE_DIGEST);
		try {
			var digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available in this Java runtime.", exception);
		}
	}

}
