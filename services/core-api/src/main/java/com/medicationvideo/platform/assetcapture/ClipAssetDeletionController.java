package com.medicationvideo.platform.assetcapture;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medicationvideo.platform.iamproject.ProjectAccessException;
import com.medicationvideo.platform.iamproject.ProjectActor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/clip-assets")
class ClipAssetDeletionController {

	private static final String IDEMPOTENCY_KEY_PATTERN = "^[A-Za-z0-9._:-]{16,128}$";

	private static final String REQUEST_ID_PATTERN = "^[A-Za-z0-9._:-]{1,128}$";

	private final ClipAssetDeletionService service;

	ClipAssetDeletionController(ClipAssetDeletionService service) {
		this.service = service;
	}

	@DeleteMapping("/{clipAssetId}")
	ResponseEntity<Void> delete(@PathVariable UUID projectId, @PathVariable UUID clipAssetId,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = IDEMPOTENCY_KEY_PATTERN) String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			@Valid @RequestBody ClipAssetDeletionRequest request, Authentication authentication) {
		var actor = requireActor(authentication);
		this.service.delete(projectId, clipAssetId, actor.actorId(), resolveRequestId(requestId), MDC.get("traceId"),
				request);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	private static ProjectActor requireActor(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof ProjectActor actor)) {
			throw new ProjectAccessException("PROJECT_ACCESS_DENIED", "An authenticated project actor is required.",
					403);
		}
		return actor;
	}

	private static String resolveRequestId(String requestId) {
		return Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString());
	}

}
