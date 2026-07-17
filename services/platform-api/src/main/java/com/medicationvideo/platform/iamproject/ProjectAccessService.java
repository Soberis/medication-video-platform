package com.medicationvideo.platform.iamproject;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectAccessService {

	private final DataProjectRepository projectRepository;
	private final ProjectMembershipRepository membershipRepository;

	ProjectAccessService(
			DataProjectRepository projectRepository,
			ProjectMembershipRepository membershipRepository) {
		this.projectRepository = projectRepository;
		this.membershipRepository = membershipRepository;
	}

	@Transactional(readOnly = true)
	public ProjectRole requireMember(UUID projectId, String actorId) {
		requireActiveProject(projectId);
		return requireActiveMembership(projectId, actorId).getRole();
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public ProjectRole lockAndRequireAdministrator(UUID projectId, String actorId) {
		var project = this.projectRepository.findForUpdate(projectId)
				.orElseThrow(ProjectAccessService::projectNotFound);
		if (project.getStatus() != ProjectStatus.ACTIVE) {
			throw projectNotFound();
		}
		var membership = requireActiveMembership(projectId, actorId);
		if (membership.getRole() != ProjectRole.ADMIN) {
			throw new ProjectAccessException(
					"PROJECT_ACCESS_DENIED",
					"Project administrator membership is required.",
					403);
		}
		return membership.getRole();
	}

	private void requireActiveProject(UUID projectId) {
		this.projectRepository.findByProjectIdAndStatus(projectId, ProjectStatus.ACTIVE)
				.orElseThrow(ProjectAccessService::projectNotFound);
	}

	private ProjectMembership requireActiveMembership(UUID projectId, String actorId) {
		return this.membershipRepository.findByProjectIdAndActorId(projectId, actorId)
				.filter(ProjectMembership::isActive)
				.orElseThrow(ProjectAccessService::projectNotFound);
	}

	private static ProjectAccessException projectNotFound() {
		return new ProjectAccessException(
				"PROJECT_NOT_FOUND",
				"The project was not found for the current actor.",
				404);
	}
}
