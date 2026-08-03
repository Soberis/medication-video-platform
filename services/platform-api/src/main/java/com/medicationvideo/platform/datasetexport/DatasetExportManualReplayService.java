package com.medicationvideo.platform.datasetexport;

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
class DatasetExportManualReplayService {

	private final ProjectAccessService projectAccessService;

	private final DatasetExportRepository exportRepository;

	private final DatasetExportAttemptRepository attemptRepository;

	private final DatasetExportManualReplayRepository replayRepository;

	private final DatasetExportAttemptQueueService attemptQueue;

	private final DatasetExportOutputCleanup outputCleanup;

	private final AuditEventRecorder auditRecorder;

	private final int maxManualReplays;

	DatasetExportManualReplayService(ProjectAccessService projectAccessService,
			DatasetExportRepository exportRepository, DatasetExportAttemptRepository attemptRepository,
			DatasetExportManualReplayRepository replayRepository, DatasetExportAttemptQueueService attemptQueue,
			DatasetExportOutputCleanup outputCleanup, AuditEventRecorder auditRecorder,
			@Value("${platform.dataset-export.max-manual-replays:2}") int maxManualReplays) {
		if (maxManualReplays < 1 || maxManualReplays > 10) {
			throw new IllegalArgumentException("Dataset export max manual replays must be between 1 and 10.");
		}
		this.projectAccessService = projectAccessService;
		this.exportRepository = exportRepository;
		this.attemptRepository = attemptRepository;
		this.replayRepository = replayRepository;
		this.attemptQueue = attemptQueue;
		this.outputCleanup = outputCleanup;
		this.auditRecorder = auditRecorder;
		this.maxManualReplays = maxManualReplays;
	}

	@Transactional
	DatasetExportManualReplay replay(UUID projectId, String actorId, UUID datasetExportId, String idempotencyKey,
			String requestId, String traceId, DatasetExportManualReplayRequest request) {
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		var fingerprint = fingerprint(projectId, datasetExportId, request);
		var byIdempotency = this.replayRepository.findByProjectIdAndIdempotencyKey(projectId, idempotencyKey);
		if (byIdempotency.isPresent()) {
			return requireSameRequest(byIdempotency.get(), fingerprint);
		}
		if (!request.deadLetterReason().replayable()) {
			throw conflict("DATASET_EXPORT_REPLAY_DIAGNOSTIC_UNREPLAYABLE",
					"This dataset export dead-letter diagnostic has no trusted replay identity.");
		}

		var export = this.exportRepository.findForUpdate(datasetExportId)
			.filter(candidate -> candidate.getProjectId().equals(projectId))
			.orElseThrow(() -> new DatasetVersionException("DATASET_EXPORT_NOT_FOUND",
					"The dataset export was not found for the current project.", 404));
		if (this.replayRepository.findByDeadLetterId(request.deadLetterId()).isPresent()) {
			throw conflict("DATASET_EXPORT_REPLAY_DEAD_LETTER_REUSED",
					"The dataset export dead-letter diagnostic was already replayed.");
		}
		if (this.replayRepository.countByDatasetExportId(datasetExportId) >= this.maxManualReplays) {
			throw conflict("DATASET_EXPORT_REPLAY_LIMIT_REACHED",
					"The dataset export reached its controlled manual replay limit.");
		}

		var expectedDeadLetterId = DatasetExportDeadLetterIdentity.from(request.deadLetterReason(),
				request.bodySha256());
		if (!MessageDigest.isEqual(request.deadLetterId().toString().getBytes(StandardCharsets.US_ASCII),
				expectedDeadLetterId.toString().getBytes(StandardCharsets.US_ASCII))) {
			throw conflict("DATASET_EXPORT_REPLAY_DIAGNOSTIC_MISMATCH",
					"The dead-letter diagnostic identity is invalid.");
		}

		var attempt = this.attemptRepository.findCurrentForUpdate(datasetExportId)
			.orElseThrow(() -> conflict("DATASET_EXPORT_REPLAY_STALE",
					"The dataset export has no current replayable attempt."));
		if (attempt.getAttempt() != request.sourceAttempt()
				|| !attempt.getMessageId().equals(request.sourceMessageId())) {
			throw conflict("DATASET_EXPORT_REPLAY_STALE",
					"The dead-letter diagnostic does not identify the current dataset export attempt.");
		}
		if ((export.getStatus() != DatasetExportStatus.QUEUED && export.getStatus() != DatasetExportStatus.RUNNING)
				|| (attempt.getStatus() != DatasetExportAttemptStatus.QUEUED
						&& attempt.getStatus() != DatasetExportAttemptStatus.RUNNING)) {
			throw conflict("DATASET_EXPORT_REPLAY_STATE_INVALID",
					"Only a queued or running dataset export attempt can be manually replayed.");
		}

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		try {
			this.outputCleanup.discard(attempt);
		}
		catch (RuntimeException exception) {
			throw conflict("DATASET_EXPORT_REPLAY_CLEANUP_FAILED",
					"The existing dataset export output could not be cleaned before replay.");
		}

		attempt.failAfterManualDeadLetter(request.deadLetterReason(), now);
		export.requeueAfterManualReplay();
		this.attemptRepository.saveAndFlush(attempt);
		this.exportRepository.saveAndFlush(export);
		var newAttempt = this.attemptQueue.enqueue(export, attempt.getAttempt() + 1, now, now);
		var replay = this.replayRepository.saveAndFlush(DatasetExportManualReplay.create(projectId, datasetExportId,
				request, newAttempt.getAttempt(), actorId, idempotencyKey, fingerprint, now));
		this.auditRecorder
			.record(new AuditEventCommand(now, AuditActorType.USER, actorId, projectId, "DATASET_EXPORT_MANUAL_REPLAY",
					"DatasetExport", datasetExportId.toString(), Long.toString(export.getVersion()),
					AuditOutcome.SUCCEEDED, request.operatorReasonCode().name(), requestId, traceId, "ROLE_ADMIN",
					Map.of("dead_letter_id", request.deadLetterId().toString(), "dead_letter_reason",
							request.deadLetterReason().name(), "source_attempt",
							Integer.toString(request.sourceAttempt()), "new_attempt",
							Integer.toString(newAttempt.getAttempt()), "change_reference", request.changeReference())));
		return replay;
	}

	private static DatasetExportManualReplay requireSameRequest(DatasetExportManualReplay replay, String fingerprint) {
		if (!MessageDigest.isEqual(replay.getRequestFingerprint().getBytes(StandardCharsets.US_ASCII),
				fingerprint.getBytes(StandardCharsets.US_ASCII))) {
			throw conflict("DATASET_EXPORT_REPLAY_IDEMPOTENCY_KEY_REUSED",
					"The idempotency key was already used for a different dataset export replay request.");
		}
		return replay;
	}

	private static DatasetVersionException conflict(String errorCode, String message) {
		return new DatasetVersionException(errorCode, message, 409);
	}

	private static String fingerprint(UUID projectId, UUID datasetExportId, DatasetExportManualReplayRequest request) {
		var canonical = String.join("\n", projectId.toString(), datasetExportId.toString(),
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
