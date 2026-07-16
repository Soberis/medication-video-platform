package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;

@Service
class UploadBatchTerminationService {

	private static final String EXPIRY_ACTOR = "upload-expiry-scheduler";

	private final UploadBatchRepository batchRepository;
	private final UploadFileRepository fileRepository;
	private final UploadCleanupTaskStore cleanupStore;
	private final AuditEventRecorder auditRecorder;

	UploadBatchTerminationService(
			UploadBatchRepository batchRepository,
			UploadFileRepository fileRepository,
			UploadCleanupTaskStore cleanupStore,
			AuditEventRecorder auditRecorder) {
		this.batchRepository = batchRepository;
		this.fileRepository = fileRepository;
		this.cleanupStore = cleanupStore;
		this.auditRecorder = auditRecorder;
	}

	@Transactional
	UploadBatchTerminationResponse abort(
			UUID projectId,
			String actorId,
			boolean administrator,
			UUID batchId,
			String requestId) {
		var batch = this.batchRepository.findForTermination(batchId, projectId)
				.orElseThrow(UploadBatchNotFoundException::new);
		if (!administrator && !batch.getInitiatedByActorId().equals(actorId)) {
			throw new UploadBatchAuthorizationException(
					"Only the upload batch initiator or a project administrator can abort it.");
		}
		if (batch.getStatus() == UploadBatchStatus.ABORTED
				|| batch.getStatus() == UploadBatchStatus.EXPIRED) {
			return UploadBatchTerminationResponse.from(
					batch, this.cleanupStore.summarize(batch.getBatchId()));
		}
		if (batch.getStatus() != UploadBatchStatus.INITIATED
				&& batch.getStatus() != UploadBatchStatus.UPLOADING) {
			throw new UploadBatchTerminationException(
					"UPLOAD_BATCH_NOT_ABORTABLE",
					"The upload batch cannot be aborted from its current state.");
		}

		var now = now();
		var expired = !batch.getExpiresAt().isAfter(now);
		var status = expired ? UploadBatchStatus.EXPIRED : UploadBatchStatus.ABORTED;
		var reason = expired ? "UPLOAD_BATCH_TTL_EXPIRED" : "USER_REQUESTED";
		var files = this.fileRepository.findAllForTermination(batch.getBatchId());
		if (files.stream().anyMatch(file -> file.hasActiveCompletionLease(now))) {
			throw new UploadBatchTerminationException(
					"UPLOAD_FILE_COMPLETION_IN_PROGRESS",
					"An upload file is still completing; retry after its completion lease ends.",
					true);
		}
		terminate(batch, files, status, reason, now);
		recordAudit(
				batch,
				expired ? AuditActorType.SYSTEM : AuditActorType.USER,
				expired ? EXPIRY_ACTOR : actorId,
				expired ? "UPLOAD_BATCH_EXPIRE" : "UPLOAD_BATCH_ABORT",
				reason,
				requestId,
				expired ? "TTL_POLICY" : administrator ? "ROLE_ADMIN" : "BATCH_INITIATOR");
		return UploadBatchTerminationResponse.from(
				batch, this.cleanupStore.summarize(batch.getBatchId()));
	}

	@Transactional
	boolean expireNextDue() {
		var now = now();
		var batch = this.batchRepository.findNextExpiredForUpdate(now).orElse(null);
		if (batch == null) {
			return false;
		}
		var files = this.fileRepository.findAllForTermination(batch.getBatchId());
		if (files.stream().anyMatch(file -> file.hasActiveCompletionLease(now))) {
			return false;
		}
		terminate(
				batch,
				files,
				UploadBatchStatus.EXPIRED,
				"UPLOAD_BATCH_TTL_EXPIRED",
				now);
		recordAudit(
				batch,
				AuditActorType.SYSTEM,
				EXPIRY_ACTOR,
				"UPLOAD_BATCH_EXPIRE",
				"UPLOAD_BATCH_TTL_EXPIRED",
				"upload-expiry-" + batch.getBatchId(),
				"TTL_POLICY");
		return true;
	}

	private void terminate(
			UploadBatch batch,
			java.util.List<UploadFile> files,
			UploadBatchStatus batchStatus,
			String reasonCode,
			Instant now) {
		var fileStatus = batchStatus == UploadBatchStatus.EXPIRED
				? UploadFileStatus.EXPIRED
				: UploadFileStatus.ABORTED;
		for (var file : files) {
			if (file.hasMultipartSession()) {
				this.cleanupStore.schedule(batch.getBatchId(), file.getUploadFileId(), now);
			}
			file.terminate(fileStatus, now);
		}
		batch.terminate(batchStatus, reasonCode, now);
		this.fileRepository.saveAll(files);
		this.batchRepository.save(batch);
	}

	private void recordAudit(
			UploadBatch batch,
			AuditActorType actorType,
			String actorId,
			String action,
			String reasonCode,
			String requestId,
			String authorizationReference) {
		var cleanup = this.cleanupStore.summarize(batch.getBatchId());
		this.auditRecorder.record(new AuditEventCommand(
				batch.getTerminatedAt(),
				actorType,
				actorId,
				batch.getProjectId(),
				action,
				"UploadBatch",
				batch.getBatchId().toString(),
				batch.getStatus().name(),
				AuditOutcome.SUCCEEDED,
				reasonCode,
				requestId,
				null,
				authorizationReference,
				Map.of(
						"status", batch.getStatus().name(),
						"cleanup_task_count", Integer.toString(cleanup.taskCount()))));
	}

	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}
}
