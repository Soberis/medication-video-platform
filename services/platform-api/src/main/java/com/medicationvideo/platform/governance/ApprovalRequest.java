package com.medicationvideo.platform.governance;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "approval_request")
class ApprovalRequest {

	private static final String DATASET_VERSION_OBJECT_TYPE = "DatasetVersion";

	@Id
	@Column(name = "approval_request_id", nullable = false, updatable = false)
	private UUID approvalRequestId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Enumerated(EnumType.STRING)
	@Column(name = "request_type", nullable = false, updatable = false, length = 64)
	private ApprovalRequestType requestType;

	@Column(name = "object_type", nullable = false, updatable = false, length = 64)
	private String objectType;

	@Column(name = "object_id", nullable = false, updatable = false)
	private UUID objectId;

	@Column(name = "object_version", nullable = false, updatable = false)
	private UUID objectVersion;

	@Column(name = "requested_by", nullable = false, updatable = false, length = 128)
	private String requestedBy;

	@Column(name = "requested_at", nullable = false, updatable = false)
	private Instant requestedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16)
	private ApprovalRequestStatus status;

	protected ApprovalRequest() {
	}

	private ApprovalRequest(
			UUID projectId,
			UUID datasetVersionId,
			UUID validationId,
			String requestedBy,
			Instant requestedAt) {
		this.approvalRequestId = UUID.randomUUID();
		this.projectId = projectId;
		this.requestType = ApprovalRequestType.DATASET_VERSION_FREEZE;
		this.objectType = DATASET_VERSION_OBJECT_TYPE;
		this.objectId = datasetVersionId;
		this.objectVersion = validationId;
		this.requestedBy = requestedBy;
		this.requestedAt = requestedAt;
		this.status = ApprovalRequestStatus.PENDING;
	}

	static ApprovalRequest requestDatasetFreeze(
			UUID projectId,
			UUID datasetVersionId,
			UUID validationId,
			String requestedBy,
			Instant requestedAt) {
		return new ApprovalRequest(projectId, datasetVersionId, validationId, requestedBy, requestedAt);
	}

	boolean matchesDatasetFreeze(
			UUID projectId,
			UUID datasetVersionId,
			UUID validationId,
			String requestedBy) {
		return this.projectId.equals(projectId)
				&& this.requestType == ApprovalRequestType.DATASET_VERSION_FREEZE
				&& DATASET_VERSION_OBJECT_TYPE.equals(this.objectType)
				&& this.objectId.equals(datasetVersionId)
				&& this.objectVersion.equals(validationId)
				&& this.requestedBy.equals(requestedBy);
	}

	boolean matchesDatasetFreeze(UUID projectId, UUID datasetVersionId, UUID validationId) {
		return this.projectId.equals(projectId)
				&& this.requestType == ApprovalRequestType.DATASET_VERSION_FREEZE
				&& DATASET_VERSION_OBJECT_TYPE.equals(this.objectType)
				&& this.objectId.equals(datasetVersionId)
				&& this.objectVersion.equals(validationId);
	}

	void approve() {
		if (this.status != ApprovalRequestStatus.PENDING) {
			throw new IllegalStateException("Only a pending approval request can be approved.");
		}
		this.status = ApprovalRequestStatus.APPROVED;
	}

	UUID getApprovalRequestId() {
		return this.approvalRequestId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	UUID getDatasetVersionId() {
		return this.objectId;
	}

	UUID getValidationId() {
		return this.objectVersion;
	}

	String getRequestedBy() {
		return this.requestedBy;
	}

	Instant getRequestedAt() {
		return this.requestedAt;
	}

	ApprovalRequestStatus getStatus() {
		return this.status;
	}
}

enum ApprovalRequestType {
	DATASET_VERSION_FREEZE
}

enum ApprovalRequestStatus {
	PENDING,
	APPROVED
}
