package com.medicationvideo.platform.governance;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatasetExportApprovalService {

	private final ApprovalRequestRepository requestRepository;

	private final ApprovalDecisionRepository decisionRepository;

	DatasetExportApprovalService(ApprovalRequestRepository requestRepository,
			ApprovalDecisionRepository decisionRepository) {
		this.requestRepository = requestRepository;
		this.decisionRepository = decisionRepository;
	}

	@Transactional
	public DatasetExportApprovalSnapshot request(UUID projectId, UUID datasetVersionId, UUID validationId,
			String requestedBy, Instant requestedAt) {
		var existing = findRequest(projectId, datasetVersionId);
		if (existing.isPresent()) {
			if (!existing.get().matchesDatasetExport(projectId, datasetVersionId, validationId, requestedBy)) {
				throw new IllegalStateException(
						"The dataset export approval request does not match its frozen version.");
			}
			return snapshot(existing.get());
		}
		var request = this.requestRepository.saveAndFlush(ApprovalRequest.requestDatasetExport(projectId,
				datasetVersionId, validationId, requestedBy, requestedAt));
		return snapshot(request);
	}

	@Transactional(readOnly = true)
	public Optional<DatasetExportApprovalSnapshot> find(UUID projectId, UUID datasetVersionId) {
		return findRequest(projectId, datasetVersionId).map(this::snapshot);
	}

	@Transactional
	public DatasetExportApprovalSnapshot approve(UUID projectId, UUID datasetVersionId, UUID validationId,
			String approvedBy, Instant approvedAt, boolean selfAdminApprovalAllowed) {
		var request = findRequest(projectId, datasetVersionId)
			.orElseThrow(() -> new IllegalStateException("The dataset export approval request is missing."));
		if (!request.matchesDatasetExport(projectId, datasetVersionId, validationId, request.getRequestedBy())) {
			throw new IllegalStateException("The dataset export approval request does not match its frozen version.");
		}
		if (request.getRequestedBy().equals(approvedBy) && !selfAdminApprovalAllowed) {
			throw new IllegalArgumentException("The dataset export requester cannot approve the same request.");
		}
		if (request.getStatus() != ApprovalRequestStatus.PENDING) {
			throw new IllegalStateException("The dataset export approval request is no longer pending.");
		}
		var decision = this.decisionRepository.saveAndFlush(ApprovalDecision.approve(request, approvedBy, approvedAt));
		request.approve();
		this.requestRepository.saveAndFlush(request);
		return snapshot(request, decision);
	}

	private Optional<ApprovalRequest> findRequest(UUID projectId, UUID datasetVersionId) {
		return this.requestRepository.findByProjectIdAndRequestTypeAndObjectId(projectId,
				ApprovalRequestType.DATASET_EXPORT, datasetVersionId);
	}

	private DatasetExportApprovalSnapshot snapshot(ApprovalRequest request) {
		var decision = this.decisionRepository.findByApprovalRequestId(request.getApprovalRequestId()).orElse(null);
		if (request.getStatus() == ApprovalRequestStatus.APPROVED && decision == null) {
			throw new IllegalStateException("An approved request must have one approval decision.");
		}
		return snapshot(request, decision);
	}

	private static DatasetExportApprovalSnapshot snapshot(ApprovalRequest request, ApprovalDecision decision) {
		return new DatasetExportApprovalSnapshot(request.getApprovalRequestId(), request.getDatasetVersionId(),
				request.getValidationId(), request.getStatus().name(), request.getRequestedBy(),
				request.getRequestedAt(), decision == null ? null : decision.getApprovalDecisionId(),
				decision == null ? null : decision.getDecidedBy(), decision == null ? null : decision.getDecidedAt());
	}

}
