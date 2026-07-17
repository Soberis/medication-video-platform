package com.medicationvideo.platform.iamproject;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "membership")
class ProjectMembership {

	@Id
	@Column(name = "membership_id", nullable = false, updatable = false)
	private UUID membershipId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "actor_id", nullable = false, length = 128, updatable = false)
	private String actorId;

	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false, length = 32)
	private ProjectRole role;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private MembershipStatus status;

	@Column(name = "created_by", nullable = false, length = 128, updatable = false)
	private String createdBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_by", nullable = false, length = 128)
	private String updatedBy;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "removed_by", length = 128)
	private String removedBy;

	@Column(name = "removed_at")
	private Instant removedAt;

	@Version
	@Column(name = "row_version", nullable = false)
	private long rowVersion;

	protected ProjectMembership() {
	}

	private ProjectMembership(
			UUID projectId,
			String actorId,
			ProjectRole role,
			String performedBy,
			Instant occurredAt) {
		this.membershipId = UUID.randomUUID();
		this.projectId = projectId;
		this.actorId = actorId;
		this.role = role;
		this.status = MembershipStatus.ACTIVE;
		this.createdBy = performedBy;
		this.createdAt = occurredAt;
		this.updatedBy = performedBy;
		this.updatedAt = occurredAt;
	}

	static ProjectMembership grant(
			UUID projectId,
			String actorId,
			ProjectRole role,
			String performedBy,
			Instant occurredAt) {
		return new ProjectMembership(projectId, actorId, role, performedBy, occurredAt);
	}

	void activate(ProjectRole newRole, String performedBy, Instant occurredAt) {
		this.role = newRole;
		this.status = MembershipStatus.ACTIVE;
		this.updatedBy = performedBy;
		this.updatedAt = occurredAt;
		this.removedBy = null;
		this.removedAt = null;
	}

	void remove(String performedBy, Instant occurredAt) {
		this.status = MembershipStatus.REMOVED;
		this.updatedBy = performedBy;
		this.updatedAt = occurredAt;
		this.removedBy = performedBy;
		this.removedAt = occurredAt;
	}

	boolean isActive() {
		return this.status == MembershipStatus.ACTIVE;
	}

	boolean isActiveAdministrator() {
		return isActive() && this.role == ProjectRole.ADMIN;
	}

	UUID getMembershipId() {
		return this.membershipId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	String getActorId() {
		return this.actorId;
	}

	ProjectRole getRole() {
		return this.role;
	}

	MembershipStatus getStatus() {
		return this.status;
	}

	Instant getUpdatedAt() {
		return this.updatedAt;
	}

	long getRowVersion() {
		return this.rowVersion;
	}
}
