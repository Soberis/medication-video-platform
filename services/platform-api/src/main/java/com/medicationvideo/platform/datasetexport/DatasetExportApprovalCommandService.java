package com.medicationvideo.platform.datasetexport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;
import com.medicationvideo.platform.governance.DatasetExportApprovalService;
import com.medicationvideo.platform.iamproject.ProjectAccessService;

@Service
class DatasetExportApprovalCommandService {

	private static final String COMMAND_VERSION = "DATASET_EXPORT_APPROVE_V1";

	private final ProjectAccessService projectAccessService;

	private final DatasetExportRepository exportRepository;

	private final DatasetExportApprovalReadinessService readinessService;

	private final DatasetExportApprovalService approvalService;

	private final DatasetExportAttemptQueueService attemptQueue;

	private final DatasetExportAttemptRepository attemptRepository;

	private final ExportArtifactRepository artifactRepository;

	private final AuditEventRecorder auditRecorder;

	DatasetExportApprovalCommandService(ProjectAccessService projectAccessService,
			DatasetExportRepository exportRepository, DatasetExportApprovalReadinessService readinessService,
			DatasetExportApprovalService approvalService, DatasetExportAttemptQueueService attemptQueue,
			DatasetExportAttemptRepository attemptRepository, ExportArtifactRepository artifactRepository,
			AuditEventRecorder auditRecorder) {
		this.projectAccessService = projectAccessService;
		this.exportRepository = exportRepository;
		this.readinessService = readinessService;
		this.approvalService = approvalService;
		this.attemptQueue = attemptQueue;
		this.attemptRepository = attemptRepository;
		this.artifactRepository = artifactRepository;
		this.auditRecorder = auditRecorder;
	}

	@Transactional(isolation = Isolation.REPEATABLE_READ)
	DatasetExportResponse approve(UUID projectId, UUID datasetVersionId, String actorId, String idempotencyKey,
			String requestId, String traceId) {
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		var fingerprint = requestFingerprint(datasetVersionId);
		var replay = this.exportRepository.findByProjectIdAndApprovedByAndApprovalIdempotencyKey(projectId, actorId,
				idempotencyKey);
		if (replay.isPresent()) {
			if (!replay.get().matchesApproval(actorId, idempotencyKey, fingerprint)) {
				throw new DatasetVersionException("DATASET_EXPORT_APPROVAL_IDEMPOTENCY_KEY_REUSED",
						"The idempotency key was already used for another export approval.", 409);
			}
			return response(replay.get());
		}

		var export = this.exportRepository.findForUpdateByProjectAndVersion(projectId, datasetVersionId)
			.orElseThrow(() -> new DatasetVersionException("DATASET_EXPORT_NOT_FOUND",
					"The dataset version does not have an export request.", 404));
		if (export.getStatus() != DatasetExportStatus.REQUESTED) {
			throw new DatasetVersionException("DATASET_EXPORT_APPROVAL_STATE_INVALID",
					"Only a requested dataset export can be approved and queued.", 409);
		}
		var readiness = this.readinessService.get(projectId, datasetVersionId, actorId);
		if (!readiness.readyForApproval()) {
			throw new DatasetVersionException("DATASET_EXPORT_APPROVAL_BLOCKED",
					"The dataset export is not ready for approval and execution.", 409,
					Map.of("blocking_reasons", String.join(",", readiness.blockingReasons())));
		}

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var approval = this.approvalService.approve(projectId, datasetVersionId, export.getDatasetVersionValidationId(),
				actorId, now);
		if (approval.approvalDecisionId() == null) {
			throw new IllegalStateException("An approved dataset export must have one decision.");
		}
		export.queue(approval.approvalDecisionId(), actorId, idempotencyKey, fingerprint, now);
		this.exportRepository.saveAndFlush(export);
		var attempt = this.attemptQueue.enqueue(export, 1, now, now);
		this.auditRecorder.record(new AuditEventCommand(now, AuditActorType.USER, actorId, projectId,
				"DATASET_EXPORT_APPROVED_AND_QUEUED", "DatasetExport", export.getDatasetExportId().toString(),
				Long.toString(export.getVersion()), AuditOutcome.SUCCEEDED, "EXPORT_APPROVED_AFTER_REVALIDATION",
				requestId, traceId, "APPROVAL_DECISION:" + approval.approvalDecisionId(),
				Map.of("dataset_version_id", datasetVersionId.toString(), "approval_request_id",
						approval.approvalRequestId().toString(), "approval_decision_id",
						approval.approvalDecisionId().toString(), "dataset_export_attempt_id",
						attempt.getDatasetExportAttemptId().toString(), "message_id", attempt.getMessageId().toString(),
						"snapshot_digest", export.getSnapshotDigest(), "export_profile_digest",
						export.getExportProfileDigest(), "request_fingerprint", fingerprint)));
		return DatasetExportResponse.from(export, approval, attempt, null);
	}

	private DatasetExportResponse response(DatasetExport export) {
		var approval = this.approvalService.find(export.getProjectId(), export.getDatasetVersionId())
			.orElseThrow(() -> new IllegalStateException("The dataset export approval request is missing."));
		var attempt = this.attemptRepository.findTopByDatasetExportIdOrderByAttemptDesc(export.getDatasetExportId())
			.orElse(null);
		return DatasetExportResponse.from(export, approval, attempt,
				this.artifactRepository.findByDatasetExportId(export.getDatasetExportId()).orElse(null));
	}

	static String requestFingerprint(UUID datasetVersionId) {
		var canonical = COMMAND_VERSION + "\n" + datasetVersionId;
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 must be available.", exception);
		}
	}

}
