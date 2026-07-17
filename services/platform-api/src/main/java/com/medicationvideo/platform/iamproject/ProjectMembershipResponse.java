package com.medicationvideo.platform.iamproject;

import java.time.Instant;
import java.util.UUID;

record ProjectMembershipResponse(
		UUID membershipId,
		UUID projectId,
		String actorId,
		ProjectRole role,
		MembershipStatus status,
		long version,
		Instant updatedAt) {

	static ProjectMembershipResponse from(ProjectMembership membership) {
		return new ProjectMembershipResponse(
				membership.getMembershipId(),
				membership.getProjectId(),
				membership.getActorId(),
				membership.getRole(),
				membership.getStatus(),
				membership.getRowVersion(),
				membership.getUpdatedAt());
	}

	static ProjectMembershipResponse from(ProjectMembershipCommand command) {
		return new ProjectMembershipResponse(
				command.getResultMembershipId(),
				command.getProjectId(),
				command.getTargetActorId(),
				command.getResultRole(),
				command.getResultStatus(),
				command.getResultVersion(),
				command.getResultUpdatedAt());
	}
}
