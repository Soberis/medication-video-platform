package com.medicationvideo.platform.governance;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatasetExportApprovalService {

	private final ApprovalRequestRepository requestRepository;

	DatasetExportApprovalService(ApprovalRequestRepository requestRepository) {
		this.requestRepository = requestRepository;
	}

	@Transactional
	public DatasetExportApprovalSnapshot request(
			UUID projectId,
			UUID datasetVersionId,
			UUID validationId,
			String requestedBy,
			Instant requestedAt) {
		var existing = findRequest(projectId, datasetVersionId);
		if (existing.isPresent()) {
			if (!existing.get().matchesDatasetExport(
					projectId, datasetVersionId, validationId, requestedBy)) {
				throw new IllegalStateException(
						"The dataset export approval request does not match its frozen version.");
			}
			return snapshot(existing.get());
		}
		var request = this.requestRepository.saveAndFlush(ApprovalRequest.requestDatasetExport(
				projectId, datasetVersionId, validationId, requestedBy, requestedAt));
		return snapshot(request);
	}

	@Transactional(readOnly = true)
	public Optional<DatasetExportApprovalSnapshot> find(UUID projectId, UUID datasetVersionId) {
		return findRequest(projectId, datasetVersionId).map(DatasetExportApprovalService::snapshot);
	}

	private Optional<ApprovalRequest> findRequest(UUID projectId, UUID datasetVersionId) {
		return this.requestRepository.findByProjectIdAndRequestTypeAndObjectId(
				projectId, ApprovalRequestType.DATASET_EXPORT, datasetVersionId);
	}

	private static DatasetExportApprovalSnapshot snapshot(ApprovalRequest request) {
		return new DatasetExportApprovalSnapshot(
				request.getApprovalRequestId(),
				request.getDatasetVersionId(),
				request.getValidationId(),
				request.getStatus().name(),
				request.getRequestedBy(),
				request.getRequestedAt());
	}
}
