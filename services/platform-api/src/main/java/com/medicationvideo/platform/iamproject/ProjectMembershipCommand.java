package com.medicationvideo.platform.iamproject;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "project_membership_command")
class ProjectMembershipCommand {

	@Id
	@Column(name = "command_id", nullable = false, updatable = false)
	private UUID commandId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "performed_by", nullable = false, length = 128, updatable = false)
	private String performedBy;

	@Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
	private String requestFingerprint;

	@Enumerated(EnumType.STRING)
	@Column(name = "command_type", nullable = false, length = 32, updatable = false)
	private MembershipCommandType commandType;

	@Column(name = "target_actor_id", nullable = false, length = 128, updatable = false)
	private String targetActorId;

	@Column(name = "result_membership_id", nullable = false, updatable = false)
	private UUID resultMembershipId;

	@Enumerated(EnumType.STRING)
	@Column(name = "result_role", nullable = false, length = 32, updatable = false)
	private ProjectRole resultRole;

	@Enumerated(EnumType.STRING)
	@Column(name = "result_status", nullable = false, length = 32, updatable = false)
	private MembershipStatus resultStatus;

	@Column(name = "result_version", nullable = false, updatable = false)
	private long resultVersion;

	@Column(name = "result_updated_at", nullable = false, updatable = false)
	private Instant resultUpdatedAt;

	@Column(name = "completed_at", nullable = false, updatable = false)
	private Instant completedAt;

	protected ProjectMembershipCommand() {
	}

	private ProjectMembershipCommand(
			UUID projectId,
			String performedBy,
			String idempotencyKey,
			String requestFingerprint,
			MembershipCommandType commandType,
			ProjectMembership result,
			Instant completedAt) {
		this.commandId = UUID.randomUUID();
		this.projectId = projectId;
		this.performedBy = performedBy;
		this.idempotencyKey = idempotencyKey;
		this.requestFingerprint = requestFingerprint;
		this.commandType = commandType;
		this.targetActorId = result.getActorId();
		this.resultMembershipId = result.getMembershipId();
		this.resultRole = result.getRole();
		this.resultStatus = result.getStatus();
		this.resultVersion = result.getRowVersion();
		this.resultUpdatedAt = result.getUpdatedAt();
		this.completedAt = completedAt;
	}

	static ProjectMembershipCommand completed(
			UUID projectId,
			String performedBy,
			String idempotencyKey,
			String requestFingerprint,
			MembershipCommandType commandType,
			ProjectMembership result,
			Instant completedAt) {
		return new ProjectMembershipCommand(
				projectId,
				performedBy,
				idempotencyKey,
				requestFingerprint,
				commandType,
				result,
				completedAt);
	}

	String getRequestFingerprint() {
		return this.requestFingerprint;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	String getTargetActorId() {
		return this.targetActorId;
	}

	UUID getResultMembershipId() {
		return this.resultMembershipId;
	}

	ProjectRole getResultRole() {
		return this.resultRole;
	}

	MembershipStatus getResultStatus() {
		return this.resultStatus;
	}

	long getResultVersion() {
		return this.resultVersion;
	}

	Instant getResultUpdatedAt() {
		return this.resultUpdatedAt;
	}
}
