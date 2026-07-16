package com.medicationvideo.platform.mediaprivacy;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.medicationvideo.platform.assetcapture.ClipAssetMediaIngestService;

@Service
class MediaJobRecoveryService {

	private final MediaJobRepository jobRepository;
	private final MediaJobAttemptRepository attemptRepository;
	private final MediaJobDispatchPort dispatchPort;
	private final MediaLeaseCapabilityFactory capabilityFactory;
	private final ClipAssetMediaIngestService assetIngest;
	private final TransactionTemplate transactionTemplate;
	private final Duration leaseGrace;
	private final int maxAttempts;
	private final int batchSize;

	MediaJobRecoveryService(
			MediaJobRepository jobRepository,
			MediaJobAttemptRepository attemptRepository,
			MediaJobDispatchPort dispatchPort,
			MediaLeaseCapabilityFactory capabilityFactory,
			ClipAssetMediaIngestService assetIngest,
			PlatformTransactionManager transactionManager,
			@Value("${platform.media-probe.lease-grace:PT5M}") Duration leaseGrace,
			@Value("${platform.media-probe.max-attempts:3}") int maxAttempts,
			@Value("${platform.media-probe.recovery-batch-size:20}") int batchSize) {
		this.jobRepository = jobRepository;
		this.attemptRepository = attemptRepository;
		this.dispatchPort = dispatchPort;
		this.capabilityFactory = capabilityFactory;
		this.assetIngest = assetIngest;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.leaseGrace = requirePositive(leaseGrace, "Media probe lease grace");
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("Media probe max attempts must be positive.");
		}
		if (batchSize < 1 || batchSize > 1000) {
			throw new IllegalArgumentException(
					"Media probe recovery batch size must be between 1 and 1000.");
		}
		this.maxAttempts = maxAttempts;
		this.batchSize = batchSize;
	}

	int recoverExpiredAttempts() {
		return recoverExpiredAttempts(Instant.now().truncatedTo(ChronoUnit.MICROS));
	}

	int recoverExpiredAttempts(Instant now) {
		var cutoff = now.minus(this.leaseGrace);
		var recovered = 0;
		for (var mediaJobId : this.attemptRepository.findRecoverableJobIds(cutoff, this.batchSize)) {
			var changed = this.transactionTemplate.execute(
					status -> recoverOne(mediaJobId, cutoff, now));
			if (Boolean.TRUE.equals(changed)) {
				recovered++;
			}
		}
		return recovered;
	}

	private boolean recoverOne(UUID mediaJobId, Instant cutoff, Instant now) {
		var job = this.jobRepository.findForUpdate(mediaJobId).orElse(null);
		if (job == null || job.getStatus() != MediaJobStatus.RUNNING) {
			return false;
		}
		var attempt = this.attemptRepository.findCurrentForUpdate(mediaJobId).orElse(null);
		if (attempt == null || !attempt.isRecoverableAt(cutoff)) {
			return false;
		}

		attempt.lose(now);
		if (attempt.getAttempt() >= this.maxAttempts) {
			job.fail(now);
			this.assetIngest.complete(job.getProjectId(), job.getClipAssetId(), false, now);
			return true;
		}

		var nextAttemptNumber = attempt.getAttempt() + 1;
		var messageId = UUID.randomUUID();
		var capability = this.capabilityFactory.create();
		this.attemptRepository.save(MediaJobAttempt.queue(
				job.getMediaJobId(),
				nextAttemptNumber,
				messageId,
				capability.tokenHash(),
				now));
		job.requeueAfterLostAttempt(now);
		this.dispatchPort.enqueue(new MediaProbeJobEnvelope(
				"media-probe-job.v1",
				messageId,
				job.getMediaJobId(),
				job.getJobType(),
				nextAttemptNumber,
				capability.token(),
				job.getProjectId(),
				job.getClipAssetId(),
				job.getInputDigest(),
				job.getInputSizeBytes(),
				job.getContentType(),
				job.getProbeProfileKey(),
				job.getRecipeDigest(),
				now));
		return true;
	}

	private static Duration requirePositive(Duration value, String label) {
		if (value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(label + " must be positive.");
		}
		return value;
	}
}
