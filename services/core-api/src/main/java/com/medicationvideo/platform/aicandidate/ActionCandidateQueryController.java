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
import org.springframework.web.bind.annotation.RequestParam;
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
			@PathVariable UUID captureGroupId, @RequestParam(name = "clip_asset_id", required = false) UUID clipAssetId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		this.projectAccessService.requireMember(projectId, actor.actorId());
		List<ActionCandidateResponse> candidates = clipAssetId != null
				? this.service.listByCaptureGroup(projectId, captureGroupId, clipAssetId)
				: this.service.listByCaptureGroup(projectId, captureGroupId);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(candidates);
	}

	@GetMapping("/projects/{projectId}/capture-groups/{captureGroupId}/action-candidate-job")
	ResponseEntity<ActionCandidateJobResponse> getJobStatus(@PathVariable UUID projectId,
			@PathVariable UUID captureGroupId, @RequestParam(name = "clip_asset_id", required = false) UUID clipAssetId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		this.projectAccessService.requireMember(projectId, actor.actorId());
		var status = clipAssetId != null ? this.service.getJobStatus(projectId, captureGroupId, clipAssetId)
				: this.service.getJobStatus(projectId, captureGroupId);
		if (status == null) {
			return ResponseEntity.noContent().build();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(status);
	}

	@org.springframework.web.bind.annotation.PostMapping("/projects/{projectId}/capture-groups/{captureGroupId}/action-candidate-jobs")
	ResponseEntity<ActionCandidateJobResponse> queueJob(@PathVariable UUID projectId, @PathVariable UUID captureGroupId,
			@org.springframework.web.bind.annotation.RequestHeader(name = "Idempotency-Key",
					required = false) String idempotencyKeyHeader,
			@org.springframework.web.bind.annotation.RequestHeader(name = "X-Request-ID",
					required = false) String requestId,
			@org.springframework.web.bind.annotation.RequestHeader(name = "traceparent",
					required = false) String traceId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		var idempotencyKey = idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank() ? idempotencyKeyHeader
				: "action-job-" + captureGroupId + "-" + UUID.randomUUID();
		var resolvedRequestId = (requestId != null && !requestId.isBlank()) ? requestId : UUID.randomUUID().toString();
		var resolvedTraceId = (traceId != null && !traceId.isBlank()) ? traceId : resolvedRequestId;
		var response = this.service.queueActionCandidateJob(projectId, captureGroupId, actor.actorId(), idempotencyKey,
				resolvedRequestId, resolvedTraceId);
		return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(response);
	}

	private static PlatformActor requireActor(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof PlatformActor actor)) {
			throw new HandPoseJobException("PROJECT_ACCESS_DENIED", "Authentication is required.", 403);
		}
		return actor;
	}

}
