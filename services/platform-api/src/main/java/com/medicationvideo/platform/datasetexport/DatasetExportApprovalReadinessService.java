package com.medicationvideo.platform.datasetexport;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.governance.DatasetExportApprovalService;
import com.medicationvideo.platform.iamproject.ProjectAccessService;
import com.medicationvideo.platform.iamproject.ProjectRole;

@Service
class DatasetExportApprovalReadinessService {

	static final String APPROVER_ADMIN_REQUIRED = "APPROVER_ADMIN_REQUIRED";
	static final String SELF_APPROVAL_FORBIDDEN = "SELF_APPROVAL_FORBIDDEN";
	static final String EXPORT_AUTHORIZATION_EXPIRED = "EXPORT_AUTHORIZATION_EXPIRED";
	static final String DATASET_VERSION_NOT_FROZEN = "DATASET_VERSION_NOT_FROZEN";
	static final String EXPORT_REQUEST_INVALID = "EXPORT_REQUEST_INVALID";
	static final String APPROVAL_REQUEST_INVALID = "APPROVAL_REQUEST_INVALID";
	static final String FROZEN_EVIDENCE_INVALID = "FROZEN_EVIDENCE_INVALID";
	static final String SNAPSHOT_VALIDATION_MISMATCH = "SNAPSHOT_VALIDATION_MISMATCH";

	private final ProjectAccessService projectAccessService;
	private final DatasetVersionRepository versionRepository;
	private final DatasetBuildSpecificationRepository specificationRepository;
	private final DatasetBuildSnapshotRepository snapshotRepository;
	private final DatasetVersionValidationRepository validationRepository;
	private final DatasetExportRepository exportRepository;
	private final DatasetExportApprovalService approvalService;
	private final DatasetVersionValidationService validationService;

	DatasetExportApprovalReadinessService(
			ProjectAccessService projectAccessService,
			DatasetVersionRepository versionRepository,
			DatasetBuildSpecificationRepository specificationRepository,
			DatasetBuildSnapshotRepository snapshotRepository,
			DatasetVersionValidationRepository validationRepository,
			DatasetExportRepository exportRepository,
			DatasetExportApprovalService approvalService,
			DatasetVersionValidationService validationService) {
		this.projectAccessService = projectAccessService;
		this.versionRepository = versionRepository;
		this.specificationRepository = specificationRepository;
		this.snapshotRepository = snapshotRepository;
		this.validationRepository = validationRepository;
		this.exportRepository = exportRepository;
		this.approvalService = approvalService;
		this.validationService = validationService;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	DatasetExportApprovalReadinessResponse get(
			UUID projectId, UUID datasetVersionId, String actorId) {
		var role = this.projectAccessService.requireMember(projectId, actorId);
		var version = this.versionRepository.findByProjectIdAndDatasetVersionId(projectId, datasetVersionId)
				.orElseThrow(DatasetExportApprovalReadinessService::notFound);
		var export = this.exportRepository.findByDatasetVersionId(datasetVersionId)
				.orElseThrow(() -> new DatasetVersionException(
						"DATASET_EXPORT_NOT_FOUND",
						"The dataset version does not have an export request.",
						404));
		var approval = this.approvalService.find(projectId, datasetVersionId)
				.orElseThrow(() -> new IllegalStateException(
						"The dataset export approval request is missing."));
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var blockers = new LinkedHashSet<String>();

		if (role != ProjectRole.ADMIN) {
			blockers.add(APPROVER_ADMIN_REQUIRED);
		}
		if (export.getRequestedBy().equals(actorId)) {
			blockers.add(SELF_APPROVAL_FORBIDDEN);
		}
		if (!export.getExpiresAt().isAfter(now)) {
			blockers.add(EXPORT_AUTHORIZATION_EXPIRED);
		}
		if (version.getStatus() != DatasetVersionStatus.FROZEN) {
			blockers.add(DATASET_VERSION_NOT_FROZEN);
		}
		if (export.getStatus() != DatasetExportStatus.REQUESTED
				|| !export.getProjectId().equals(projectId)
				|| !export.getDatasetVersionId().equals(datasetVersionId)) {
			blockers.add(EXPORT_REQUEST_INVALID);
		}
		if (!"PENDING".equals(approval.status())
				|| !export.getApprovalRequestId().equals(approval.approvalRequestId())
				|| !datasetVersionId.equals(approval.datasetVersionId())
				|| !export.getDatasetVersionValidationId().equals(approval.validationId())
				|| !export.getRequestedBy().equals(approval.requestedBy())) {
			blockers.add(APPROVAL_REQUEST_INVALID);
		}

		var specification = this.specificationRepository.findByDatasetVersionId(datasetVersionId);
		var snapshot = this.snapshotRepository.findByDatasetVersionId(datasetVersionId);
		var validation = this.validationRepository.findByDatasetVersionId(datasetVersionId);
		if (specification.isEmpty()
				|| snapshot.isEmpty()
				|| validation.isEmpty()
				|| !frozenEvidenceMatches(export, version, specification.get(), snapshot.get(), validation.get())) {
			blockers.add(FROZEN_EVIDENCE_INVALID);
		}
		else {
			try {
				var evaluation = this.validationService.evaluateCurrent(projectId, version, actorId);
				blockers.addAll(evaluation.reasons());
				if (!validation.get().matchesSnapshot(evaluation.snapshot())) {
					blockers.add(SNAPSHOT_VALIDATION_MISMATCH);
				}
			}
			catch (DatasetVersionException exception) {
				blockers.add(FROZEN_EVIDENCE_INVALID);
			}
		}

		return new DatasetExportApprovalReadinessResponse(
				export.getDatasetExportId(),
				datasetVersionId,
				approval.approvalRequestId(),
				blockers.isEmpty(),
				blockers.stream().toList(),
				now);
	}

	private static boolean frozenEvidenceMatches(
			DatasetExport export,
			DatasetVersion version,
			DatasetBuildSpecification specification,
			DatasetBuildSnapshot snapshot,
			DatasetVersionValidation validation) {
		return version.getBuildSnapshotId() != null
				&& version.getBuildSnapshotId().equals(snapshot.getDatasetBuildSnapshotId())
				&& export.getDatasetBuildSpecificationId()
						.equals(specification.getDatasetBuildSpecificationId())
				&& export.getDatasetBuildSnapshotId().equals(snapshot.getDatasetBuildSnapshotId())
				&& export.getDatasetVersionValidationId()
						.equals(validation.getDatasetVersionValidationId())
				&& export.getExportProfileKey().equals(specification.getExportProfileKey())
				&& export.getExportProfileDigest().equals(specification.getExportProfileDigest())
				&& export.getSnapshotDigest().equals(snapshot.getSnapshotDigest())
				&& validation.getOutcome() == DatasetVersionValidationOutcome.PASS
				&& validation.matchesSnapshot(snapshot);
	}

	private static DatasetVersionException notFound() {
		return new DatasetVersionException(
				"DATASET_VERSION_NOT_FOUND",
				"The dataset version was not found for the current project.",
				404);
	}
}
