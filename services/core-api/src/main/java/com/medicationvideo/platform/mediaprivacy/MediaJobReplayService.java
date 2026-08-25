package com.medicationvideo.platform.mediaprivacy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;
import com.medicationvideo.platform.iamproject.ProjectAccessService;

@Service
class MediaJobReplayService {

	private final MediaJobRepository jobRepository;

	private final MediaJobAttemptRepository attemptRepository;

	private final MediaJobManualReplayRepository replayRepository;

	private final MediaJobAttemptQueueService attemptQueue;

	private final AuditEventRecorder auditRecorder;

	private final ProjectAccessService projectAccessService;

	private final int maxManualReplays;

	MediaJobReplayService(MediaJobRepository jobRepository, MediaJobAttemptRepository attemptRepository,
			MediaJobManualReplayRepository replayRepository, MediaJobAttemptQueueService attemptQueue,
			AuditEventRecorder auditRecorder, ProjectAccessService projectAccessService,
			@Value("${platform.media-probe.max-manual-replays:2}") int maxManualReplays) {
		if (maxManualReplays < 1 || maxManualReplays > 10) {
			throw new IllegalArgumentException("Media probe max manual replays must be between 1 and 10.");
		}
		this.jobRepository = jobRepository;
		this.attemptRepository = attemptRepository;
		this.replayRepository = replayRepository;
		this.attemptQueue = attemptQueue;
		this.auditRecorder = auditRecorder;
		this.projectAccessService = projectAccessService;
		this.maxManualReplays = maxManualReplays;
	}

	@Transactional
	MediaJobManualReplay replay(String actorId, UUID mediaJobId, String idempotencyKey, String requestId,
			String traceId, MediaJobReplayRequest request) {
		var job = this.jobRepository.findForUpdate(mediaJobId)
			.orElseThrow(() -> new MediaJobNotFoundException("The media job was not found."));
		var projectId = job.getProjectId();
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		var fingerprint = fingerprint(projectId, mediaJobId, request);
		var byIdempotency = this.replayRepository.findByProjectIdAndIdempotencyKey(projectId, idempotencyKey);
		if (byIdempotency.isPresent()) {
			return requireSameRequest(byIdempotency.get(), fingerprint);
		}

		var byDeadLetter = this.replayRepository.findByDeadLetterId(request.deadLetterId());
		if (byDeadLetter.isPresent()) {
			return requireSameRequest(byDeadLetter.get(), fingerprint);
		}
		if (this.replayRepository.countByMediaJobId(mediaJobId) >= this.maxManualReplays) {
			throw new MediaJobReplayException("MEDIA_JOB_REPLAY_LIMIT_REACHED",
					"The media job reached its controlled manual replay limit.");
		}

		var expectedDeadLetterId = MediaDeadLetterIdentity.from(request.deadLetterReason(), request.bodySha256());
		if (!MessageDigest.isEqual(request.deadLetterId().toString().getBytes(StandardCharsets.US_ASCII),
				expectedDeadLetterId.toString().getBytes(StandardCharsets.US_ASCII))) {
			throw new MediaJobReplayException("MEDIA_JOB_REPLAY_DIAGNOSTIC_MISMATCH",
					"The dead-letter diagnostic identity is invalid.");
		}

		var attempt = this.attemptRepository.findCurrentForUpdate(mediaJobId)
			.orElseThrow(() -> new MediaJobReplayException("MEDIA_JOB_REPLAY_STALE",
					"The media job has no current replayable attempt."));
		if (attempt.getAttempt() != request.sourceAttempt()
				|| !attempt.getMessageId().equals(request.sourceMessageId())) {
			throw new MediaJobReplayException("MEDIA_JOB_REPLAY_STALE",
					"The dead-letter diagnostic does not identify the current attempt.");
		}

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var nextAttempt = attempt.getAttempt() + 1;
		attempt.deadLetter(request.deadLetterReason(), now);
		job.requeueAfterDeadLetter(now);
		this.attemptQueue.enqueue(job, nextAttempt, now, now);

		var replay = this.replayRepository.save(MediaJobManualReplay.create(projectId, mediaJobId, request, nextAttempt,
				actorId, idempotencyKey, fingerprint, now));
		this.auditRecorder.record(new AuditEventCommand(now, AuditActorType.USER, actorId, projectId,
				"MEDIA_JOB_MANUAL_REPLAY", "MediaJob", mediaJobId.toString(), "attempt-" + nextAttempt,
				AuditOutcome.SUCCEEDED, request.operatorReasonCode().name(), requestId, traceId, "ROLE_ADMIN",
				Map.of("dead_letter_id", request.deadLetterId().toString(), "dead_letter_reason",
						request.deadLetterReason().name(), "source_attempt", Integer.toString(request.sourceAttempt()),
						"new_attempt", Integer.toString(nextAttempt), "change_reference", request.changeReference())));
		return replay;
	}

	private static MediaJobManualReplay requireSameRequest(MediaJobManualReplay replay, String fingerprint) {
		if (!MessageDigest.isEqual(replay.getRequestFingerprint().getBytes(StandardCharsets.US_ASCII),
				fingerprint.getBytes(StandardCharsets.US_ASCII))) {
			throw new MediaJobIdempotencyConflictException();
		}
		return replay;
	}

	private static String fingerprint(UUID projectId, UUID mediaJobId, MediaJobReplayRequest request) {
		var canonical = String.join("\n", projectId.toString(), mediaJobId.toString(),
				request.deadLetterId().toString(), request.sourceMessageId().toString(),
				Integer.toString(request.sourceAttempt()), request.deadLetterReason().name(), request.bodySha256(),
				request.operatorReasonCode().name(), request.changeReference());
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

}
