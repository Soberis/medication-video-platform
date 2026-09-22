package com.medicationvideo.platform.iamproject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;

@Service
class ProjectApplicationService {

	private final DataProjectRepository projectRepository;

	private final DataProjectWriter projectWriter;

	private final ProjectMembershipRepository membershipRepository;

	private final ProjectMembershipCommandRepository commandRepository;

	private final AuditEventRecorder auditRecorder;

	ProjectApplicationService(DataProjectRepository projectRepository, DataProjectWriter projectWriter,
			ProjectMembershipRepository membershipRepository, ProjectMembershipCommandRepository commandRepository,
			AuditEventRecorder auditRecorder) {
		this.projectRepository = projectRepository;
		this.projectWriter = projectWriter;
		this.membershipRepository = membershipRepository;
		this.commandRepository = commandRepository;
		this.auditRecorder = auditRecorder;
	}

	@Transactional
	ProjectResponse create(String actorId, String idempotencyKey, String requestId, String traceId,
			ProjectCreateRequest request) {
		var displayName = request.displayName().trim();
		var requestFingerprint = fingerprint("CREATE_PROJECT", actorId, displayName);
		var now = now();
		var project = DataProject.create(displayName, actorId, idempotencyKey, requestFingerprint, now);
		if (!this.projectWriter.insertIfAbsent(project)) {
			var existing = this.projectRepository.findByCreatedByAndIdempotencyKey(actorId, idempotencyKey)
				.orElseThrow(() -> new IllegalStateException("A conflicting project command could not be reloaded."));
			requireSameRequest(existing.getRequestFingerprint(), requestFingerprint, "PROJECT_IDEMPOTENCY_KEY_REUSED");
			return ProjectResponse.from(existing, ProjectRole.ADMIN);
		}

		var creatorMembership = this.membershipRepository
			.saveAndFlush(ProjectMembership.grant(project.getProjectId(), actorId, ProjectRole.ADMIN, actorId, now));
		recordProjectCreated(project, actorId, requestId, traceId);
		recordMembershipChanged(creatorMembership, actorId, "PROJECT_MEMBERSHIP_GRANTED", "PROJECT_CREATOR", requestId,
				traceId, "ROLE_ADMIN");
		return ProjectResponse.from(project, ProjectRole.ADMIN);
	}

	@Transactional(readOnly = true)
	ProjectListResponse list(String actorId, boolean canCreateProjects) {
		if (canCreateProjects) {
			// 平台管理员可以看到并操作所有项目
			var projects = this.projectRepository.findAllByStatusOrderByCreatedAtAscProjectIdAsc(ProjectStatus.ACTIVE);
			return new ProjectListResponse(
					projects.stream().map(project -> ProjectResponse.from(project, ProjectRole.ADMIN)).toList(), true);
		}
		var memberships = this.membershipRepository.findAllByActorIdAndStatusOrderByCreatedAtAscMembershipIdAsc(actorId,
				MembershipStatus.ACTIVE);
		if (memberships.isEmpty()) {
			return new ProjectListResponse(java.util.List.of(), canCreateProjects);
		}
		var roleByProject = memberships.stream()
			.collect(Collectors.toMap(ProjectMembership::getProjectId, ProjectMembership::getRole));
		var projects = this.projectRepository.findVisibleProjects(roleByProject.keySet(), ProjectStatus.ACTIVE);
		return new ProjectListResponse(projects.stream()
			.map(project -> ProjectResponse.from(project, roleByProject.get(project.getProjectId())))
			.toList(), canCreateProjects);
	}

	@Transactional(readOnly = true)
	ProjectResponse get(UUID projectId, String actorId) {
		var project = this.projectRepository.findByProjectIdAndStatus(projectId, ProjectStatus.ACTIVE)
			.orElseThrow(ProjectApplicationService::projectNotFound);
		if (ProjectAccessService.isPlatformAdministrator(actorId)) {
			return ProjectResponse.from(project, ProjectRole.ADMIN);
		}
		return ProjectResponse.from(project, requireVisibleMembership(projectId, actorId).getRole());
	}

	@Transactional
	ProjectResponse rename(UUID projectId, String actorId, String requestId, String traceId,
			ProjectRenameRequest request) {
		var project = lockActiveProject(projectId);
		var role = requireAdministrator(projectId, actorId);
		var displayName = request.displayName().trim();
		if (!project.getDisplayName().equals(displayName)) {
			var oldDisplayName = project.getDisplayName();
			project.rename(displayName);
			project = this.projectRepository.saveAndFlush(project);
			recordProjectChanged(project, actorId, "PROJECT_RENAMED", "PROJECT_RENAMED", requestId, traceId,
					Map.of("old_display_name", oldDisplayName, "new_display_name", displayName));
		}
		return ProjectResponse.from(project, role);
	}

	@Transactional
	void archive(UUID projectId, String actorId, String requestId, String traceId) {
		var project = this.projectRepository.findForUpdate(projectId)
			.orElseThrow(ProjectApplicationService::projectNotFound);
		this.requireAdministrator(projectId, actorId);
		if (project.getStatus() == ProjectStatus.ARCHIVED) {
			return;
		}
		project.archive();
		project = this.projectRepository.saveAndFlush(project);
		recordProjectChanged(project, actorId, "PROJECT_ARCHIVED", "PROJECT_ARCHIVED", requestId, traceId,
				Map.of("previous_status", ProjectStatus.ACTIVE.name()));
	}

	@Transactional(readOnly = true)
	ProjectMembershipListResponse listMemberships(UUID projectId, String actorId) {
		requireAdministrator(projectId, actorId);
		var memberships = this.membershipRepository
			.findAllByProjectIdAndStatusOrderByCreatedAtAscMembershipIdAsc(projectId, MembershipStatus.ACTIVE);
		return new ProjectMembershipListResponse(memberships.stream().map(ProjectMembershipResponse::from).toList());
	}

	@Transactional
	ProjectMembershipResponse upsertMembership(UUID projectId, String actorId, String targetActorId,
			String idempotencyKey, String requestId, String traceId, ProjectMembershipRequest request) {
		lockActiveProject(projectId);
		requireAdministrator(projectId, actorId);
		var requestFingerprint = fingerprint(MembershipCommandType.UPSERT.name(), projectId.toString(), targetActorId,
				request.role().name());
		var replay = findCommandReplay(projectId, actorId, idempotencyKey, requestFingerprint);
		if (replay != null) {
			return replay;
		}

		var now = now();
		var existing = this.membershipRepository.findByProjectIdAndActorId(projectId, targetActorId);
		ProjectMembership membership;
		String auditAction = null;
		String reasonCode = null;
		if (existing.isEmpty()) {
			membership = ProjectMembership.grant(projectId, targetActorId, request.role(), actorId, now);
			auditAction = "PROJECT_MEMBERSHIP_GRANTED";
			reasonCode = "MEMBERSHIP_GRANTED";
		}
		else {
			membership = existing.get();
			if (membership.isActive() && membership.getRole() == ProjectRole.ADMIN
					&& request.role() == ProjectRole.MEMBER) {
				requireAnotherAdministrator(projectId);
			}
			if (!membership.isActive()) {
				membership.activate(request.role(), actorId, now);
				auditAction = "PROJECT_MEMBERSHIP_REACTIVATED";
				reasonCode = "MEMBERSHIP_REACTIVATED";
			}
			else if (membership.getRole() != request.role()) {
				membership.activate(request.role(), actorId, now);
				auditAction = "PROJECT_MEMBERSHIP_ROLE_CHANGED";
				reasonCode = "MEMBERSHIP_ROLE_CHANGED";
			}
		}

		if (auditAction != null) {
			membership = this.membershipRepository.saveAndFlush(membership);
			recordMembershipChanged(membership, actorId, auditAction, reasonCode, requestId, traceId,
					"PROJECT_MEMBERSHIP_ADMIN");
		}
		var command = ProjectMembershipCommand.completed(projectId, actorId, idempotencyKey, requestFingerprint,
				MembershipCommandType.UPSERT, membership, now);
		this.commandRepository.saveAndFlush(command);
		return ProjectMembershipResponse.from(command);
	}

	@Transactional
	ProjectMembershipResponse removeMembership(UUID projectId, String actorId, String targetActorId,
			String idempotencyKey, String requestId, String traceId) {
		lockActiveProject(projectId);
		requireAdministrator(projectId, actorId);
		var requestFingerprint = fingerprint(MembershipCommandType.REMOVE.name(), projectId.toString(), targetActorId);
		var replay = findCommandReplay(projectId, actorId, idempotencyKey, requestFingerprint);
		if (replay != null) {
			return replay;
		}

		var membership = this.membershipRepository.findByProjectIdAndActorId(projectId, targetActorId)
			.orElseThrow(() -> new ProjectApiException("PROJECT_MEMBERSHIP_NOT_FOUND",
					"The project membership was not found.", 404));
		var now = now();
		if (membership.isActive()) {
			if (membership.isActiveAdministrator()) {
				requireAnotherAdministrator(projectId);
			}
			membership.remove(actorId, now);
			membership = this.membershipRepository.saveAndFlush(membership);
			recordMembershipChanged(membership, actorId, "PROJECT_MEMBERSHIP_REMOVED", "MEMBERSHIP_REMOVED", requestId,
					traceId, "PROJECT_MEMBERSHIP_ADMIN");
		}
		var command = ProjectMembershipCommand.completed(projectId, actorId, idempotencyKey, requestFingerprint,
				MembershipCommandType.REMOVE, membership, now);
		this.commandRepository.saveAndFlush(command);
		return ProjectMembershipResponse.from(command);
	}

	private ProjectMembershipResponse findCommandReplay(UUID projectId, String actorId, String idempotencyKey,
			String requestFingerprint) {
		return this.commandRepository.findByProjectIdAndPerformedByAndIdempotencyKey(projectId, actorId, idempotencyKey)
			.map(command -> {
				requireSameRequest(command.getRequestFingerprint(), requestFingerprint,
						"MEMBERSHIP_IDEMPOTENCY_KEY_REUSED");
				return ProjectMembershipResponse.from(command);
			})
			.orElse(null);
	}

	private DataProject lockActiveProject(UUID projectId) {
		var project = this.projectRepository.findForUpdate(projectId)
			.orElseThrow(ProjectApplicationService::projectNotFound);
		if (project.getStatus() != ProjectStatus.ACTIVE) {
			throw projectNotFound();
		}
		return project;
	}

	private ProjectMembership requireVisibleMembership(UUID projectId, String actorId) {
		return this.membershipRepository.findByProjectIdAndActorId(projectId, actorId)
			.filter(ProjectMembership::isActive)
			.orElseThrow(ProjectApplicationService::projectNotFound);
	}

	private ProjectRole requireAdministrator(UUID projectId, String actorId) {
		if (ProjectAccessService.isPlatformAdministrator(actorId)) {
			return ProjectRole.ADMIN;
		}
		var membership = this.membershipRepository.findByProjectIdAndActorId(projectId, actorId)
			.orElseThrow(ProjectApplicationService::projectNotFound);
		if (!membership.isActive()) {
			throw projectNotFound();
		}
		if (membership.getRole() != ProjectRole.ADMIN) {
			throw new ProjectApiException("PROJECT_ACCESS_DENIED", "Project administrator membership is required.",
					403);
		}
		return membership.getRole();
	}

	private void requireAnotherAdministrator(UUID projectId) {
		if (this.membershipRepository.countByProjectIdAndStatusAndRole(projectId, MembershipStatus.ACTIVE,
				ProjectRole.ADMIN) <= 1) {
			throw new ProjectApiException("LAST_PROJECT_ADMIN",
					"A project must retain at least one active administrator.", 409);
		}
	}

	private void recordProjectCreated(DataProject project, String actorId, String requestId, String traceId) {
		this.auditRecorder.record(new AuditEventCommand(project.getCreatedAt(), AuditActorType.USER, actorId,
				project.getProjectId(), "PROJECT_CREATED", "Project", project.getProjectId().toString(),
				Long.toString(project.getRowVersion()), AuditOutcome.SUCCEEDED, "PROJECT_PROVISIONED", requestId,
				traceId, "ROLE_ADMIN", Map.of("status", project.getStatus().name())));
	}

	private void recordProjectChanged(DataProject project, String actorId, String action, String reasonCode,
			String requestId, String traceId, Map<String, String> details) {
		this.auditRecorder.record(new AuditEventCommand(now(), AuditActorType.USER, actorId, project.getProjectId(),
				action, "Project", project.getProjectId().toString(), Long.toString(project.getRowVersion()),
				AuditOutcome.SUCCEEDED, reasonCode, requestId, traceId, "PROJECT_ADMIN", details));
	}

	private void recordMembershipChanged(ProjectMembership membership, String actorId, String action, String reasonCode,
			String requestId, String traceId, String authorizationReference) {
		this.auditRecorder.record(new AuditEventCommand(membership.getUpdatedAt(), AuditActorType.USER, actorId,
				membership.getProjectId(), action, "ProjectMembership", membership.getMembershipId().toString(),
				Long.toString(membership.getRowVersion()), AuditOutcome.SUCCEEDED, reasonCode, requestId, traceId,
				authorizationReference, Map.of("target_actor_id", membership.getActorId(), "role",
						membership.getRole().name(), "status", membership.getStatus().name())));
	}

	private static void requireSameRequest(String existingFingerprint, String requestFingerprint, String errorCode) {
		if (!MessageDigest.isEqual(existingFingerprint.getBytes(StandardCharsets.US_ASCII),
				requestFingerprint.getBytes(StandardCharsets.US_ASCII))) {
			throw new ProjectApiException(errorCode, "The idempotency key was already used for a different request.",
					409);
		}
	}

	private static String fingerprint(String... values) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256")
					.digest(String.join("\n", values).getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

	private static ProjectApiException projectNotFound() {
		return new ProjectApiException("PROJECT_NOT_FOUND", "The project was not found for the current actor.", 404);
	}

}
