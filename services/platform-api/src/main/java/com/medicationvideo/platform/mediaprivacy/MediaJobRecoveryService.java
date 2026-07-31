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

	private final MediaJobAttemptQueueService attemptQueue;

	private final MediaJobRetryPolicy retryPolicy;

	private final ClipAssetMediaIngestService assetIngest;

	private final TransactionTemplate transactionTemplate;

	private final Duration leaseGrace;

	private final int batchSize;

	MediaJobRecoveryService(MediaJobRepository jobRepository, MediaJobAttemptRepository attemptRepository,
			MediaJobAttemptQueueService attemptQueue, MediaJobRetryPolicy retryPolicy,
			ClipAssetMediaIngestService assetIngest, PlatformTransactionManager transactionManager,
			@Value("${platform.media-probe.lease-grace:PT5M}") Duration leaseGrace,
			@Value("${platform.media-probe.recovery-batch-size:20}") int batchSize) {
		this.jobRepository = jobRepository;
		this.attemptRepository = attemptRepository;
		this.attemptQueue = attemptQueue;
		this.retryPolicy = retryPolicy;
		this.assetIngest = assetIngest;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.leaseGrace = requirePositive(leaseGrace, "Media probe lease grace");
		if (batchSize < 1 || batchSize > 1000) {
			throw new IllegalArgumentException("Media probe recovery batch size must be between 1 and 1000.");
		}
		this.batchSize = batchSize;
	}

	int recoverExpiredAttempts() {
		return recoverExpiredAttempts(Instant.now().truncatedTo(ChronoUnit.MICROS));
	}

	int recoverExpiredAttempts(Instant now) {
		var cutoff = now.minus(this.leaseGrace);
		var recovered = 0;
		for (var mediaJobId : this.attemptRepository.findRecoverableJobIds(cutoff, this.batchSize)) {
			var changed = this.transactionTemplate.execute(status -> recoverOne(mediaJobId, cutoff, now));
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
		if (!this.retryPolicy.hasAttemptsRemaining(attempt.getAttempt())) {
			job.fail(now);
			this.assetIngest.complete(job.getProjectId(), job.getClipAssetId(), false, now);
			return true;
		}

		var nextAttemptNumber = attempt.getAttempt() + 1;
		job.requeueAfterLostAttempt(now);
		this.attemptQueue.enqueue(job, nextAttemptNumber, now, now);
		return true;
	}

	private static Duration requirePositive(Duration value, String label) {
		if (value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(label + " must be positive.");
		}
		return value;
	}

}
