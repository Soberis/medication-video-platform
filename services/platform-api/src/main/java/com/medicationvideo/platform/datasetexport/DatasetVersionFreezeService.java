package com.medicationvideo.platform.datasetexport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;
import com.medicationvideo.platform.governance.DatasetFreezeApprovalService;
import com.medicationvideo.platform.governance.DatasetFreezeApprovalSnapshot;
import com.medicationvideo.platform.iamproject.ProjectAccessService;

@Service
class DatasetVersionFreezeService {

	private static final String COMMAND_VERSION = "DATASET_VERSION_FREEZE_V1";

	private final ProjectAccessService projectAccessService;
	private final DatasetVersionRepository versionRepository;
	private final DatasetVersionValidationRepository validationRepository;
	private final DatasetVersionValidationService validationService;
	private final DatasetFreezeApprovalService approvalService;
	private final AuditEventRecorder auditRecorder;

	DatasetVersionFreezeService(
			ProjectAccessService projectAccessService,
			DatasetVersionRepository versionRepository,
			DatasetVersionValidationRepository validationRepository,
			DatasetVersionValidationService validationService,
			DatasetFreezeApprovalService approvalService,
			AuditEventRecorder auditRecorder) {
		this.projectAccessService = projectAccessService;
		this.versionRepository = versionRepository;
		this.validationRepository = validationRepository;
		this.validationService = validationService;
		this.approvalService = approvalService;
		this.auditRecorder = auditRecorder;
	}

	@Transactional(isolation = Isolation.REPEATABLE_READ)
	DatasetVersionFreezeResponse freeze(
			UUID projectId,
			UUID datasetVersionId,
			String actorId,
			String idempotencyKey,
			String requestId,
			String traceId) {
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		var fingerprint = requestFingerprint(datasetVersionId);
		var replay = this.versionRepository.findByProjectIdAndFrozenByAndFreezeIdempotencyKey(
				projectId, actorId, idempotencyKey);
		if (replay.isPresent()) {
			if (!replay.get().matchesFreezeRequest(actorId, idempotencyKey, fingerprint)) {
				throw new DatasetVersionException(
						"DATASET_VERSION_FREEZE_IDEMPOTENCY_KEY_REUSED",
						"The idempotency key was already used to freeze another dataset version.",
						409);
			}
			return response(replay.get());
		}

		var version = this.versionRepository.findByProjectIdAndDatasetVersionId(projectId, datasetVersionId)
				.orElseThrow(DatasetVersionFreezeService::notFound);
		if (version.getStatus() == DatasetVersionStatus.FROZEN) {
			throw new DatasetVersionException(
					"DATASET_VERSION_ALREADY_FROZEN",
					"The dataset version is already frozen.",
					409);
		}
		if (version.getStatus() != DatasetVersionStatus.VALIDATING) {
			throw new DatasetVersionException(
					"DATASET_VERSION_FREEZE_STATUS_INVALID",
					"Only a validating dataset version can be frozen.",
					409);
		}

		var validation = this.validationRepository.findByDatasetVersionId(datasetVersionId)
				.orElseThrow(() -> new DatasetVersionException(
						"DATASET_VERSION_FREEZE_VALIDATION_REQUIRED",
						"The dataset version must have an immutable freeze-preflight validation.",
						409));
		if (validation.getOutcome() != DatasetVersionValidationOutcome.PASS) {
			throw new DatasetVersionException(
					"DATASET_VERSION_FREEZE_VALIDATION_FAILED",
					"The freeze-preflight validation did not pass.",
					409);
		}
		var approval = this.approvalService.find(projectId, datasetVersionId)
				.orElseThrow(() -> new DatasetVersionException(
						"DATASET_VERSION_FREEZE_APPROVAL_MISSING",
						"The dataset freeze approval request is missing.",
						409));
		if (!approval.pending() || !approval.validationId().equals(validation.getDatasetVersionValidationId())) {
			throw new DatasetVersionException(
					"DATASET_VERSION_FREEZE_APPROVAL_INVALID",
					"The dataset freeze approval request is not pending for the current validation.",
					409);
		}
		if (approval.requestedBy().equals(actorId)) {
			throw new DatasetVersionException(
					"DATASET_VERSION_FREEZE_SELF_APPROVAL_FORBIDDEN",
					"The freeze requester cannot approve the same dataset version.",
					409);
		}

		var evaluation = this.validationService.evaluateCurrent(projectId, version, actorId);
		var reasons = new LinkedHashSet<>(evaluation.reasons());
		if (!validation.matchesSnapshot(evaluation.snapshot())) {
			reasons.add("SNAPSHOT_VALIDATION_MISMATCH");
		}
		if (!reasons.isEmpty()) {
			throw new DatasetVersionException(
					"DATASET_VERSION_FREEZE_EVIDENCE_CHANGED",
					"The dataset evidence changed after freeze-preflight validation.",
					409,
					Map.of("blocking_reasons", String.join(",", reasons)));
		}

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var approved = this.approvalService.approve(
				projectId,
				datasetVersionId,
				validation.getDatasetVersionValidationId(),
				actorId,
				now);
		if (approved.approvalDecisionId() == null) {
			throw new IllegalStateException("An approved dataset freeze must have one decision.");
		}
		version.freeze(
				approved.approvalRequestId(),
				approved.approvalDecisionId(),
				actorId,
				idempotencyKey,
				fingerprint,
				now);
		this.versionRepository.saveAndFlush(version);
		this.auditRecorder.record(new AuditEventCommand(
				now,
				AuditActorType.USER,
				actorId,
				projectId,
				"DATASET_VERSION_FROZEN",
				"DatasetVersion",
				datasetVersionId.toString(),
				Long.toString(version.getVersion()),
				AuditOutcome.SUCCEEDED,
				"FREEZE_APPROVED_AFTER_REVALIDATION",
				requestId,
				traceId,
				"APPROVAL_DECISION:" + approved.approvalDecisionId(),
				Map.of(
						"dataset_version_validation_id",
						validation.getDatasetVersionValidationId().toString(),
						"approval_request_id", approved.approvalRequestId().toString(),
						"approval_decision_id", approved.approvalDecisionId().toString(),
						"snapshot_digest", evaluation.snapshot().getSnapshotDigest(),
						"request_fingerprint", fingerprint)));
		return DatasetVersionFreezeResponse.from(version, validation, approved);
	}

	private DatasetVersionFreezeResponse response(DatasetVersion version) {
		var validation = this.validationRepository.findByDatasetVersionId(version.getDatasetVersionId())
				.orElseThrow(() -> new IllegalStateException("A frozen dataset version must have validation."));
		var approval = this.approvalService.find(version.getProjectId(), version.getDatasetVersionId())
				.filter(DatasetFreezeApprovalSnapshot::approved)
				.orElseThrow(() -> new IllegalStateException("A frozen dataset version must have approval."));
		return DatasetVersionFreezeResponse.from(version, validation, approval);
	}

	private static DatasetVersionException notFound() {
		return new DatasetVersionException(
				"DATASET_VERSION_NOT_FOUND",
				"The dataset version was not found for the current project.",
				404);
	}

	static String requestFingerprint(UUID datasetVersionId) {
		var canonical = COMMAND_VERSION + "\n" + datasetVersionId;
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 must be available.", exception);
		}
	}
}
