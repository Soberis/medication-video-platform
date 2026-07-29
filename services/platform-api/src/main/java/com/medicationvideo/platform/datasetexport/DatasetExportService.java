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
class DatasetExportService {

	private static final String COMMAND_VERSION = "DATASET_EXPORT_REQUEST_V1";

	private final ProjectAccessService projectAccessService;
	private final DatasetVersionRepository versionRepository;
	private final DatasetBuildSpecificationRepository specificationRepository;
	private final DatasetBuildSnapshotRepository snapshotRepository;
	private final DatasetVersionValidationRepository validationRepository;
	private final DatasetExportRepository exportRepository;
	private final DatasetExportApprovalService approvalService;
	private final AuditEventRecorder auditRecorder;

	DatasetExportService(
			ProjectAccessService projectAccessService,
			DatasetVersionRepository versionRepository,
			DatasetBuildSpecificationRepository specificationRepository,
			DatasetBuildSnapshotRepository snapshotRepository,
			DatasetVersionValidationRepository validationRepository,
			DatasetExportRepository exportRepository,
			DatasetExportApprovalService approvalService,
			AuditEventRecorder auditRecorder) {
		this.projectAccessService = projectAccessService;
		this.versionRepository = versionRepository;
		this.specificationRepository = specificationRepository;
		this.snapshotRepository = snapshotRepository;
		this.validationRepository = validationRepository;
		this.exportRepository = exportRepository;
		this.approvalService = approvalService;
		this.auditRecorder = auditRecorder;
	}

	@Transactional(isolation = Isolation.REPEATABLE_READ)
	DatasetExportResponse request(
			UUID projectId,
			UUID datasetVersionId,
			String actorId,
			String idempotencyKey,
			String requestId,
			String traceId,
			DatasetExportPurpose purpose,
			Instant expiresAt) {
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		var normalizedExpiry = expiresAt.truncatedTo(ChronoUnit.MICROS);
		var fingerprint = requestFingerprint(datasetVersionId, purpose, normalizedExpiry);
		var replay = this.exportRepository.findByProjectIdAndRequestedByAndIdempotencyKey(
				projectId, actorId, idempotencyKey);
		if (replay.isPresent()) {
			if (!replay.get().matchesRequest(actorId, idempotencyKey, fingerprint)) {
				throw new DatasetVersionException(
						"DATASET_EXPORT_IDEMPOTENCY_KEY_REUSED",
						"The idempotency key was already used for a different dataset export request.",
						409);
			}
			return response(replay.get());
		}

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		if (!normalizedExpiry.isAfter(now)) {
			throw new DatasetVersionException(
					"DATASET_EXPORT_EXPIRY_INVALID",
					"The dataset export request must expire in the future.",
					409);
		}
		var version = this.versionRepository.findByProjectIdAndDatasetVersionId(projectId, datasetVersionId)
				.orElseThrow(DatasetExportService::notFound);
		if (version.getStatus() != DatasetVersionStatus.FROZEN) {
			throw new DatasetVersionException(
					"DATASET_EXPORT_VERSION_NOT_FROZEN",
					"Only a frozen dataset version can be requested for export.",
					409);
		}
		if (this.exportRepository.findByDatasetVersionId(datasetVersionId).isPresent()) {
			throw new DatasetVersionException(
					"DATASET_EXPORT_ALREADY_REQUESTED",
					"The approved P0 export profile was already requested for this dataset version.",
					409);
		}

		var specification = this.specificationRepository.findByDatasetVersionId(datasetVersionId)
				.orElseThrow(DatasetExportService::frozenEvidenceMissing);
		if (!DatasetExportProfile.PROFILE_KEY.equals(specification.getExportProfileKey())
				|| !DatasetExportProfile.PROFILE_DIGEST.equals(specification.getExportProfileDigest())) {
			throw frozenEvidenceMissing();
		}
		var snapshot = this.snapshotRepository.findByDatasetVersionId(datasetVersionId)
				.orElseThrow(DatasetExportService::frozenEvidenceMissing);
		if (!snapshot.getDatasetBuildSnapshotId().equals(version.getBuildSnapshotId())
				|| !snapshot.getDatasetBuildSpecificationId()
						.equals(specification.getDatasetBuildSpecificationId())) {
			throw frozenEvidenceMissing();
		}
		var validation = this.validationRepository.findByDatasetVersionId(datasetVersionId)
				.orElseThrow(DatasetExportService::frozenEvidenceMissing);
		if (validation.getOutcome() != DatasetVersionValidationOutcome.PASS
				|| !validation.matchesSnapshot(snapshot)) {
			throw frozenEvidenceMissing();
		}

		var approval = this.approvalService.request(
				projectId,
				datasetVersionId,
				validation.getDatasetVersionValidationId(),
				actorId,
				now);
		var export = this.exportRepository.saveAndFlush(DatasetExport.request(
				version,
				specification,
				snapshot,
				validation,
				approval.approvalRequestId(),
				purpose,
				normalizedExpiry,
				actorId,
				idempotencyKey,
				fingerprint,
				now));
		this.auditRecorder.record(new AuditEventCommand(
				now,
				AuditActorType.USER,
				actorId,
				projectId,
				"DATASET_EXPORT_REQUESTED",
				"DatasetExport",
				export.getDatasetExportId().toString(),
				Long.toString(export.getVersion()),
				AuditOutcome.SUCCEEDED,
				"FROZEN_VERSION_EXPORT_REQUESTED",
				requestId,
				traceId,
				"PROJECT_MEMBERSHIP_ADMIN",
				Map.of(
						"dataset_version_id", datasetVersionId.toString(),
						"dataset_version_validation_id", validation.getDatasetVersionValidationId().toString(),
						"approval_request_id", approval.approvalRequestId().toString(),
						"purpose", purpose.name(),
						"expires_at", normalizedExpiry.toString(),
						"export_profile_key", specification.getExportProfileKey(),
						"export_profile_digest", specification.getExportProfileDigest(),
						"snapshot_digest", snapshot.getSnapshotDigest(),
						"request_fingerprint", fingerprint)));
		return DatasetExportResponse.from(export, approval);
	}

	private DatasetExportResponse response(DatasetExport export) {
		var approval = this.approvalService.find(export.getProjectId(), export.getDatasetVersionId())
				.orElseThrow(() -> new IllegalStateException(
						"The dataset export approval request is missing."));
		return DatasetExportResponse.from(export, approval);
	}

	private static DatasetVersionException notFound() {
		return new DatasetVersionException(
				"DATASET_VERSION_NOT_FOUND",
				"The dataset version was not found for the current project.",
				404);
	}

	private static DatasetVersionException frozenEvidenceMissing() {
		return new DatasetVersionException(
				"DATASET_EXPORT_FROZEN_EVIDENCE_INVALID",
				"The frozen dataset version does not have one consistent approved P0 build snapshot.",
				409);
	}

	static String requestFingerprint(
			UUID datasetVersionId, DatasetExportPurpose purpose, Instant expiresAt) {
		var canonical = String.join(
				"\n",
				COMMAND_VERSION,
				datasetVersionId.toString(),
				DatasetExportProfile.PROFILE_KEY,
				DatasetExportProfile.PROFILE_DIGEST,
				purpose.name(),
				expiresAt.truncatedTo(ChronoUnit.MICROS).toString());
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 must be available.", exception);
		}
	}
}
