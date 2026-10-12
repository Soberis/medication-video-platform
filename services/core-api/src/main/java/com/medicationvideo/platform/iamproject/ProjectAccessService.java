package com.medicationvideo.platform.iamproject;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectAccessService {

	private final DataProjectRepository projectRepository;

	private final ProjectMembershipRepository membershipRepository;

	private final boolean localSyntheticBypass;

	@Autowired
	ProjectAccessService(DataProjectRepository projectRepository, ProjectMembershipRepository membershipRepository,
			@Value("${platform.security.local-synthetic-bypass:false}") boolean localSyntheticBypass) {
		this.projectRepository = projectRepository;
		this.membershipRepository = membershipRepository;
		this.localSyntheticBypass = localSyntheticBypass;
	}

	ProjectAccessService(DataProjectRepository projectRepository, ProjectMembershipRepository membershipRepository) {
		this(projectRepository, membershipRepository, false);
	}

	@Transactional(readOnly = true)
	public ProjectRole requireMember(UUID projectId, String actorId) {
		requireActiveProject(projectId);
		var membership = this.membershipRepository.findByProjectIdAndActorId(projectId, actorId);
		if (membership.isPresent()) {
			if (!membership.get().isActive()) {
				throw projectNotFound();
			}
			return membership.get().getRole();
		}
		if (isLocalSyntheticActor(actorId) || isPlatformAdministrator(actorId)) {
			return ProjectRole.ADMIN;
		}
		throw projectNotFound();
	}

	@Transactional(readOnly = true)
	public ProjectRole requireAdministrator(UUID projectId, String actorId) {
		if (!isLocalSyntheticActor(actorId)) {
			requireEnabledPlatformAdministrator();
		}
		requireActiveProject(projectId);
		var membership = this.membershipRepository.findByProjectIdAndActorId(projectId, actorId);
		if (membership.isPresent()) {
			if (!membership.get().isActive()) {
				throw projectNotFound();
			}
			if (membership.get().getRole() != ProjectRole.ADMIN) {
				throw new ProjectAccessException("PROJECT_ACCESS_DENIED",
						"Project administrator membership is required.", 403);
			}
			return membership.get().getRole();
		}
		if (isLocalSyntheticActor(actorId) || isPlatformAdministrator(actorId)) {
			return ProjectRole.ADMIN;
		}
		throw projectNotFound();
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public ProjectRole lockAndRequireMember(UUID projectId, String actorId) {
		lockActiveProjectForSystemProcessing(projectId);
		var membership = this.membershipRepository.findByProjectIdAndActorId(projectId, actorId);
		if (membership.isPresent()) {
			if (!membership.get().isActive()) {
				throw projectNotFound();
			}
			return membership.get().getRole();
		}
		if (isLocalSyntheticActor(actorId) || isPlatformAdministrator(actorId)) {
			return ProjectRole.ADMIN;
		}
		throw projectNotFound();
	}

	/**
	 * Internal background processing only; does not grant user access or submission
	 * rights.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void lockActiveProjectForSystemProcessing(UUID projectId) {
		var project = this.projectRepository.findForUpdate(projectId)
			.orElseThrow(ProjectAccessService::projectNotFound);
		if (project.getStatus() != ProjectStatus.ACTIVE) {
			throw projectNotFound();
		}
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public ProjectRole lockAndRequireAdministrator(UUID projectId, String actorId) {
		if (!isLocalSyntheticActor(actorId)) {
			requireEnabledPlatformAdministrator();
		}
		var project = this.projectRepository.findForUpdate(projectId)
			.orElseThrow(ProjectAccessService::projectNotFound);
		if (project.getStatus() != ProjectStatus.ACTIVE) {
			throw projectNotFound();
		}
		var membership = this.membershipRepository.findByProjectIdAndActorId(projectId, actorId);
		if (membership.isPresent()) {
			if (!membership.get().isActive()) {
				throw projectNotFound();
			}
			if (membership.get().getRole() != ProjectRole.ADMIN) {
				throw new ProjectAccessException("PROJECT_ACCESS_DENIED",
						"Project administrator membership is required.", 403);
			}
			return membership.get().getRole();
		}
		if (isLocalSyntheticActor(actorId) || isPlatformAdministrator(actorId)) {
			return ProjectRole.ADMIN;
		}
		throw projectNotFound();
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

	private boolean isLocalSyntheticActor(String actorId) {
		return this.localSyntheticBypass && isCurrentActor(actorId)
				&& ("local-synthetic-admin".equals(actorId) || "local-synthetic-uploader".equals(actorId));
	}

	static boolean isPlatformAdministrator() {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		return authentication != null && authentication.isAuthenticated()
				&& authentication.getAuthorities()
					.stream()
					.anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
	}

	static boolean isPlatformAdministrator(String actorId) {
		return isPlatformAdministrator() && isCurrentActor(actorId);
	}

	private static boolean isCurrentActor(String actorId) {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()) {
			return false;
		}
		if (authentication.getPrincipal() instanceof ProjectActor actor) {
			return actor.actorId().equals(actorId);
		}
		return actorId.equals(authentication.getName());
	}

	private void requireEnabledPlatformAdministrator() {
		if (!isPlatformAdministrator()) {
			throw administratorRequired();
		}
	}

	private static ProjectAccessException administratorRequired() {
		return new ProjectAccessException("PROJECT_ACCESS_DENIED", "A platform administrator account is required.",
				403);
	}

	private static ProjectAccessException projectNotFound() {
		return new ProjectAccessException("PROJECT_NOT_FOUND", "The project was not found for the current actor.", 404);
	}

}
