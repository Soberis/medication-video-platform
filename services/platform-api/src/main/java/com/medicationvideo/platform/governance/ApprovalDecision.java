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
@Table(name = "approval_decision")
class ApprovalDecision {

	@Id
	@Column(name = "approval_decision_id", nullable = false, updatable = false)
	private UUID approvalDecisionId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "approval_request_id", nullable = false, updatable = false)
	private UUID approvalRequestId;

	@Enumerated(EnumType.STRING)
	@Column(name = "decision", nullable = false, updatable = false, length = 16)
	private ApprovalDecisionValue decision;

	@Column(name = "decided_by", nullable = false, updatable = false, length = 128)
	private String decidedBy;

	@Column(name = "decided_at", nullable = false, updatable = false)
	private Instant decidedAt;

	protected ApprovalDecision() {
	}

	private ApprovalDecision(ApprovalRequest request, String decidedBy, Instant decidedAt) {
		this.approvalDecisionId = UUID.randomUUID();
		this.projectId = request.getProjectId();
		this.approvalRequestId = request.getApprovalRequestId();
		this.decision = ApprovalDecisionValue.APPROVED;
		this.decidedBy = decidedBy;
		this.decidedAt = decidedAt;
	}

	static ApprovalDecision approve(ApprovalRequest request, String decidedBy, Instant decidedAt) {
		return new ApprovalDecision(request, decidedBy, decidedAt);
	}

	UUID getApprovalDecisionId() {
		return this.approvalDecisionId;
	}

	UUID getApprovalRequestId() {
		return this.approvalRequestId;
	}

	String getDecidedBy() {
		return this.decidedBy;
	}

	Instant getDecidedAt() {
		return this.decidedAt;
	}

}

enum ApprovalDecisionValue {

	APPROVED

}
