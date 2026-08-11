package com.medicationvideo.platform.aicandidate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reclaims AI attempts whose Worker lease has been absent beyond the grace period.
 * Candidate jobs have no separate RETRY_WAIT state, therefore a reclaimed job is put back
 * into QUEUED in the same transaction that creates the next capability/outbox message.
 */
@Service
class AiJobRecoveryService {

	private final AiCandidateJobRepository jobRepository;

	private final AiCandidateJobAttemptRepository attemptRepository;

	private final AiCandidateJobAttemptQueueService attemptQueue;

	private final TransactionTemplate transactionTemplate;

	private final Duration leaseGrace;

	private final int maxAttempts;

	private final int batchSize;

	AiJobRecoveryService(AiCandidateJobRepository jobRepository, AiCandidateJobAttemptRepository attemptRepository,
			AiCandidateJobAttemptQueueService attemptQueue, PlatformTransactionManager transactionManager,
			@Value("${platform.ai.hand-pose.lease-grace:PT5M}") Duration leaseGrace,
			@Value("${platform.ai.hand-pose.max-attempts:3}") int maxAttempts,
			@Value("${platform.ai.hand-pose.recovery-batch-size:20}") int batchSize) {
		this.jobRepository = jobRepository;
		this.attemptRepository = attemptRepository;
		this.attemptQueue = attemptQueue;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.leaseGrace = requirePositive(leaseGrace, "AI hand pose lease grace");
		HandPoseRetryPolicy.validateMaxAttempts(maxAttempts);
		this.maxAttempts = maxAttempts;
		if (batchSize < 1 || batchSize > 1000) {
			throw new IllegalArgumentException("AI hand pose recovery batch size must be between 1 and 1000.");
		}
		this.batchSize = batchSize;
	}

	int recoverExpiredAttempts() {
		return recoverExpiredAttempts(Instant.now().truncatedTo(ChronoUnit.MICROS));
	}

	int recoverExpiredAttempts(Instant now) {
		var cutoff = now.minus(this.leaseGrace);
		var recovered = 0;
		for (var jobId : this.attemptRepository.findRecoverableJobIds(cutoff, this.batchSize)) {
			var changed = this.transactionTemplate.execute(status -> recoverOne(jobId, cutoff, now));
			if (Boolean.TRUE.equals(changed)) {
				recovered++;
			}
		}
		return recovered;
	}

	private boolean recoverOne(UUID jobId, Instant cutoff, Instant now) {
		var job = this.jobRepository.findForUpdate(jobId).orElse(null);
		if (job == null) {
			return false;
		}
		var attempt = this.attemptRepository.findCurrentForUpdate(jobId).orElse(null);
		if (attempt == null) {
			return false;
		}
		if (job.getStatus() != AiCandidateJobStatus.RUNNING || !attempt.isRecoverableAt(cutoff)) {
			return false;
		}
		attempt.lose(now);
		return queueNextOrFail(job, attempt, now);
	}

	private boolean queueNextOrFail(AiCandidateJob job, AiCandidateJobAttempt attempt, Instant now) {
		if (attempt.getAttempt() >= this.maxAttempts) {
			job.fail(now);
			return true;
		}

		job.requeueAfterLostAttempt(now);
		this.attemptQueue.enqueue(job, attempt.getAttempt() + 1, now);
		return true;
	}

	private static Duration requirePositive(Duration value, String label) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(label + " must be positive.");
		}
		return value;
	}

}
