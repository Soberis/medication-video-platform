package com.medicationvideo.platform.aicandidate;

import java.net.URI;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medicationvideo.platform.iamproject.PlatformActor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Validated
@RestController
@RequestMapping("/api/v1")
class HandPoseJobController {

	private final HandPoseJobService service;

	private final HandPoseCandidateQueryService candidateQueryService;

	HandPoseJobController(HandPoseJobService service, HandPoseCandidateQueryService candidateQueryService) {
		this.service = service;
		this.candidateQueryService = candidateQueryService;
	}

	@GetMapping("/projects/{projectId}/clip-assets/{clipAssetId}/hand-pose-candidates")
	ResponseEntity<HandPoseCandidateResponse> getLatestCandidates(@PathVariable UUID projectId,
			@PathVariable UUID clipAssetId, Authentication authentication) {
		var actor = requireActor(authentication);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.candidateQueryService.getLatest(projectId, clipAssetId, actor.actorId()));
	}

	@PostMapping("/projects/{projectId}/clip-assets/{clipAssetId}/hand-pose-jobs")
	ResponseEntity<HandPoseJobResponse> queue(@PathVariable UUID projectId, @PathVariable UUID clipAssetId,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = "^[A-Za-z0-9._:-]{16,128}$") String idempotencyKey,
			@RequestHeader(name = "X-Request-Id", required = false) String requestId, Authentication authentication) {
		var actor = requireActor(authentication);
		var resolvedRequestId = requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
		var body = this.service.queue(projectId, clipAssetId, actor.actorId(), idempotencyKey, resolvedRequestId,
				MDC.get("traceId"));
		return ResponseEntity.status(HttpStatus.ACCEPTED)
			.location(URI.create("/api/v1/ai-jobs/" + body.aiJobId()))
			.cacheControl(CacheControl.noStore())
			.body(body);
	}

	@PostMapping("/projects/{projectId}/clip-assets/{clipAssetId}/hand-pose-jobs/retry")
	ResponseEntity<HandPoseJobResponse> retry(@PathVariable UUID projectId, @PathVariable UUID clipAssetId,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = "^[A-Za-z0-9._:-]{16,128}$") String idempotencyKey,
			@RequestHeader(name = "X-Request-Id", required = false) String requestId, Authentication authentication) {
		var actor = requireActor(authentication);
		var resolvedRequestId = requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
		var body = this.service.retry(projectId, clipAssetId, actor.actorId(), idempotencyKey, resolvedRequestId,
				MDC.get("traceId"));
		return ResponseEntity.status(HttpStatus.ACCEPTED)
			.location(URI.create("/api/v1/ai-jobs/" + body.aiJobId()))
			.cacheControl(CacheControl.noStore())
			.body(body);
	}

	@GetMapping("/ai-jobs/{aiJobId}")
	ResponseEntity<HandPoseJobResponse> get(@PathVariable UUID aiJobId,
			@RequestHeader(name = "X-Project-Id") UUID projectId, Authentication authentication) {
		var actor = requireActor(authentication);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.get(projectId, aiJobId, actor.actorId()));
	}

	private static PlatformActor requireActor(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof PlatformActor actor)) {
			throw new HandPoseJobException("PROJECT_ACCESS_DENIED", "Authentication is required.", 403);
		}
		return actor;
	}

}
