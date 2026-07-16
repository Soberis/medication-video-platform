package com.medicationvideo.platform.mediaprivacy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
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
	private final MediaJobDispatchPort dispatchPort;
	private final AssetCatalogQueryService assetCatalog;
	private final SecureRandom secureRandom = new SecureRandom();

	MediaJobService(
			MediaJobRepository repository,
			MediaJobAttemptRepository attemptRepository,
			MediaJobDispatchPort dispatchPort,
			AssetCatalogQueryService assetCatalog) {
		this.repository = repository;
		this.attemptRepository = attemptRepository;
		this.dispatchPort = dispatchPort;
		this.assetCatalog = assetCatalog;
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

		var natural = this.repository.findByProjectIdAndJobTypeAndClipAssetIdAndInputDigestAndRecipeDigest(
				projectId,
				request.jobType(),
				summary.clipAssetId(),
				summary.inputDigest(),
				MediaProbeProfile.RECIPE_DIGEST);
		if (natural.isPresent()) {
			return requireSameRequest(natural.get(), fingerprint);
		}

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var job = MediaJob.request(
				projectId,
				summary.clipAssetId(),
				request.jobType(),
				summary.inputDigest(),
				summary.inputSizeBytes(),
				summary.contentType(),
				MediaProbeProfile.PROFILE_KEY,
				MediaProbeProfile.RECIPE_DIGEST,
				idempotencyKey,
				fingerprint,
				now);

		try {
			job = this.repository.saveAndFlush(job);
			var messageId = UUID.randomUUID();
			var leaseToken = leaseToken();
			this.attemptRepository.save(MediaJobAttempt.queue(
					job.getMediaJobId(),
					messageId,
					sha256(leaseToken),
					now));
			job.queue(now);
			this.dispatchPort.enqueue(new MediaProbeJobEnvelope(
					"media-probe-job.v1",
					messageId,
					job.getMediaJobId(),
					job.getJobType(),
					1,
					leaseToken,
					job.getProjectId(),
					job.getClipAssetId(),
					job.getInputDigest(),
					job.getInputSizeBytes(),
					job.getContentType(),
					job.getProbeProfileKey(),
					job.getRecipeDigest(),
					now));
			return job;
		}
		catch (DataIntegrityViolationException exception) {
			var concurrentByIdem = this.repository
					.findByProjectIdAndIdempotencyKey(projectId, idempotencyKey);
			if (concurrentByIdem.isPresent()) {
				return requireSameRequest(concurrentByIdem.get(), fingerprint);
			}
			var concurrentByNatural = this.repository
					.findByProjectIdAndJobTypeAndClipAssetIdAndInputDigestAndRecipeDigest(
							projectId,
							request.jobType(),
							summary.clipAssetId(),
							summary.inputDigest(),
							MediaProbeProfile.RECIPE_DIGEST);
			if (concurrentByNatural.isPresent()) {
				return requireSameRequest(concurrentByNatural.get(), fingerprint);
			}
			throw exception;
		}
	}

	private String leaseToken() {
		var bytes = new byte[32];
		this.secureRandom.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.US_ASCII)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available in this Java runtime.", exception);
		}
	}

	@Transactional(readOnly = true)
	MediaJob get(UUID projectId, UUID mediaJobId) {
		var job = this.repository.findById(mediaJobId)
				.orElseThrow(() -> new MediaJobNotFoundException(
						"The media job was not found."));
		if (!job.getProjectId().equals(projectId)) {
			throw new MediaJobNotFoundException("The media job was not found.");
		}
		return job;
	}

	@Transactional(readOnly = true)
	MediaJobAttempt currentAttempt(MediaJob job) {
		return this.attemptRepository.findTopByMediaJobIdOrderByAttemptDesc(job.getMediaJobId())
				.orElseThrow(() -> new IllegalStateException(
						"The media job has no current attempt."));
	}

	private static void ensureProbeable(ClipAssetInputSummary summary) {
		if (!summary.contentType().toLowerCase(Locale.ROOT).startsWith("video/")) {
			throw new ClipAssetNotProbeableException(
					"The clip asset content type is not a probeable video.");
		}
		if (summary.status() != ClipAssetStatus.UPLOADED) {
			throw new ClipAssetNotProbeableException(
					"The clip asset is not in a probeable state: " + summary.status().name());
		}
	}

	private static MediaJob requireSameRequest(MediaJob job, String fingerprint) {
		if (!MessageDigest.isEqual(
				job.getRequestFingerprint().getBytes(StandardCharsets.US_ASCII),
				fingerprint.getBytes(StandardCharsets.US_ASCII))) {
			throw new MediaJobIdempotencyConflictException();
		}
		return job;
	}

	private static String fingerprint(UUID projectId, MediaJobRequest request) {
		var canonical = String.join(
				"\n",
				projectId.toString(),
				request.jobType().name(),
				request.clipAssetId().toString(),
				MediaProbeProfile.PROFILE_KEY,
				MediaProbeProfile.RECIPE_DIGEST);
		try {
			var digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available in this Java runtime.", exception);
		}
	}
}
