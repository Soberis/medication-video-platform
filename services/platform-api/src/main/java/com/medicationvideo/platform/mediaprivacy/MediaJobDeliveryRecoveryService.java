package com.medicationvideo.platform.mediaprivacy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;

/**
 * Explicit disaster-recovery command for a broker message known to be absent after it was
 * publisher-confirmed. It never scans or republishes attempts automatically.
 */
@Service
class MediaJobDeliveryRecoveryService {

	private final MediaJobRepository jobRepository;

	private final MediaJobAttemptRepository attemptRepository;

	private final MediaJobDeliveryRecoveryRepository recoveryRepository;

	private final MediaJobAttemptQueueService attemptQueue;

	private final MediaJobRetryPolicy retryPolicy;

	private final AuditEventRecorder auditRecorder;

	MediaJobDeliveryRecoveryService(MediaJobRepository jobRepository, MediaJobAttemptRepository attemptRepository,
			MediaJobDeliveryRecoveryRepository recoveryRepository, MediaJobAttemptQueueService attemptQueue,
			MediaJobRetryPolicy retryPolicy, AuditEventRecorder auditRecorder) {
		this.jobRepository = jobRepository;
		this.attemptRepository = attemptRepository;
		this.recoveryRepository = recoveryRepository;
		this.attemptQueue = attemptQueue;
		this.retryPolicy = retryPolicy;
		this.auditRecorder = auditRecorder;
	}

	@Transactional
	MediaJobDeliveryRecovery recover(UUID projectId, String actorId, UUID mediaJobId, String idempotencyKey,
			String requestId, String traceId, MediaJobDeliveryRecoveryRequest request) {
		var fingerprint = fingerprint(projectId, mediaJobId, request);
		var byIdempotency = this.recoveryRepository.findByProjectIdAndIdempotencyKey(projectId, idempotencyKey);
		if (byIdempotency.isPresent()) {
			return requireSameRequest(byIdempotency.get(), fingerprint);
		}

		var job = this.jobRepository.findForUpdate(mediaJobId)
			.filter(candidate -> candidate.getProjectId().equals(projectId))
			.orElseThrow(() -> new MediaJobNotFoundException("The media job was not found."));
		var bySource = this.recoveryRepository.findBySourceMessageId(request.sourceMessageId());
		if (bySource.isPresent()) {
			throw new MediaJobDeliveryRecoveryException("MEDIA_JOB_DELIVERY_RECOVERY_SOURCE_REUSED",
					"The source message was already recovered.");
		}
		var attempt = this.attemptRepository.findCurrentForUpdate(mediaJobId)
			.orElseThrow(() -> new MediaJobDeliveryRecoveryException("MEDIA_JOB_DELIVERY_RECOVERY_STALE",
					"The media job has no current queued attempt."));
		if (attempt.getAttempt() != request.sourceAttempt()
				|| !attempt.getMessageId().equals(request.sourceMessageId())) {
			throw new MediaJobDeliveryRecoveryException("MEDIA_JOB_DELIVERY_RECOVERY_STALE",
					"The recovery command does not identify the current attempt.");
		}
		if (job.getStatus() != MediaJobStatus.QUEUED || attempt.getStatus() != MediaJobAttemptStatus.QUEUED) {
			throw new MediaJobDeliveryRecoveryException("MEDIA_JOB_DELIVERY_RECOVERY_STATE_CONFLICT",
					"Only a queued media job attempt can recover a confirmed missing delivery.");
		}
		if (!this.attemptQueue.hasConfirmedPublication(mediaJobId, request.sourceMessageId())) {
			throw new MediaJobDeliveryRecoveryException("MEDIA_JOB_DELIVERY_RECOVERY_NOT_CONFIRMED",
					"The source message has not been publisher-confirmed.");
		}
		if (!this.retryPolicy.hasAttemptsRemaining(attempt.getAttempt())) {
			throw new MediaJobDeliveryRecoveryException("MEDIA_JOB_DELIVERY_RECOVERY_LIMIT_REACHED",
					"The media job reached its maximum attempt limit.");
		}

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var nextAttempt = attempt.getAttempt() + 1;
		attempt.recoverMissingBrokerDelivery(now);
		job.requeueAfterDeliveryRecovery(now);
		this.attemptQueue.enqueue(job, nextAttempt, now, now);
		var recovery = this.recoveryRepository.saveAndFlush(MediaJobDeliveryRecovery.create(projectId, mediaJobId,
				request, nextAttempt, actorId, idempotencyKey, fingerprint, now));
		this.auditRecorder.record(new AuditEventCommand(now, AuditActorType.USER, actorId, projectId,
				"MEDIA_JOB_DELIVERY_RECOVERED", "MediaJob", mediaJobId.toString(), "attempt-" + nextAttempt,
				AuditOutcome.SUCCEEDED, request.recoveryReason().name(), requestId, traceId, "ROLE_ADMIN",
				Map.of("source_message_id", request.sourceMessageId().toString(), "source_attempt",
						Integer.toString(request.sourceAttempt()), "new_attempt", Integer.toString(nextAttempt),
						"change_reference", request.changeReference())));
		return recovery;
	}

	private static MediaJobDeliveryRecovery requireSameRequest(MediaJobDeliveryRecovery recovery, String fingerprint) {
		if (!MessageDigest.isEqual(recovery.getRequestFingerprint().getBytes(StandardCharsets.US_ASCII),
				fingerprint.getBytes(StandardCharsets.US_ASCII))) {
			throw new MediaJobIdempotencyConflictException();
		}
		return recovery;
	}

	private static String fingerprint(UUID projectId, UUID mediaJobId, MediaJobDeliveryRecoveryRequest request) {
		var canonical = String.join("\n", projectId.toString(), mediaJobId.toString(),
				request.sourceMessageId().toString(), Integer.toString(request.sourceAttempt()),
				request.recoveryReason().name(), request.changeReference());
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

}
