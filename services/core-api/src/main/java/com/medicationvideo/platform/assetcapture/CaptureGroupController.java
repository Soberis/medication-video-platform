package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medicationvideo.platform.iamproject.ProjectAccessException;
import com.medicationvideo.platform.iamproject.ProjectActor;

@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/capture-groups")
class CaptureGroupController {

	private final CaptureGroupQueryService queryService;

	private final CaptureGroupReceiptService receiptService;

	private final AssetCaptureProjectAuthorizer authorizer;

	CaptureGroupController(CaptureGroupQueryService queryService, CaptureGroupReceiptService receiptService,
			AssetCaptureProjectAuthorizer authorizer) {
		this.queryService = queryService;
		this.receiptService = receiptService;
		this.authorizer = authorizer;
	}

	@GetMapping
	ResponseEntity<CaptureGroupListResponse> list(@PathVariable UUID projectId, Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof ProjectActor actor)) {
			throw new ProjectAccessException("PROJECT_ACCESS_DENIED", "An authenticated project actor is required.",
					403);
		}
		this.authorizer.requireMember(projectId, actor.actorId());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(this.queryService.list(projectId));
	}

	@GetMapping("/{captureGroupId}")
	ResponseEntity<CaptureSessionResponse> get(@PathVariable UUID projectId, @PathVariable UUID captureGroupId,
			Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof ProjectActor actor)) {
			throw new ProjectAccessException("PROJECT_ACCESS_DENIED", "An authenticated project actor is required.",
					403);
		}
		this.authorizer.requireMember(projectId, actor.actorId());
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.queryService.get(projectId, captureGroupId));
	}

	@PostMapping("/{captureGroupId}/receipt/finalize")
	ResponseEntity<CaptureSessionResponse> finalizeReceipt(@PathVariable UUID projectId,
			@PathVariable UUID captureGroupId, Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof ProjectActor actor)) {
			throw new ProjectAccessException("PROJECT_ACCESS_DENIED", "An authenticated project actor is required.",
					403);
		}
		this.authorizer.requireMember(projectId, actor.actorId());
		this.receiptService.finalizeCaptureGroup(projectId, captureGroupId);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.queryService.get(projectId, captureGroupId));
	}

}
