package com.medicationvideo.platform.aicandidate;

import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medicationvideo.platform.iamproject.PlatformActor;
import com.medicationvideo.platform.iamproject.ProjectAccessService;

@Validated
@RestController
@RequestMapping("/api/v1")
class ActionCandidateQueryController {

	private final ActionCandidateJobService service;

	private final ProjectAccessService projectAccessService;

	ActionCandidateQueryController(ActionCandidateJobService service, ProjectAccessService projectAccessService) {
		this.service = service;
		this.projectAccessService = projectAccessService;
	}

	@GetMapping("/projects/{projectId}/capture-groups/{captureGroupId}/action-candidates")
	ResponseEntity<List<ActionCandidateResponse>> listCandidates(@PathVariable UUID projectId,
			@PathVariable UUID captureGroupId, Authentication authentication) {
		var actor = requireActor(authentication);
		this.projectAccessService.requireMember(projectId, actor.actorId());
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.listByCaptureGroup(projectId, captureGroupId));
	}

	private static PlatformActor requireActor(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof PlatformActor actor)) {
			throw new HandPoseJobException("PROJECT_ACCESS_DENIED", "Authentication is required.", 403);
		}
		return actor;
	}

}
