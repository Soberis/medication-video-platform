package com.medicationvideo.platform.assetcapture;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medicationvideo.platform.iamproject.ProjectActor;

import jakarta.validation.constraints.Pattern;

@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/clip-assets")
class ClipAssetPlaybackController {

	private static final String REQUEST_ID_PATTERN = "^[A-Za-z0-9._:-]{1,128}$";

	private final ClipAssetPlaybackService service;

	ClipAssetPlaybackController(ClipAssetPlaybackService service) {
		this.service = service;
	}

	@PostMapping("/{clipAssetId}/playback-grants")
	ResponseEntity<ClipAssetPlaybackGrantResponse> grant(
			@PathVariable UUID projectId,
			@PathVariable UUID clipAssetId,
			@RequestHeader(name = "X-Request-Id", required = false)
			@Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		var response = this.service.grant(
				projectId,
				clipAssetId,
				actor.actorId(),
				resolveRequestId(requestId),
				MDC.get("traceId"));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}

	private static ProjectActor requireActor(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof ProjectActor actor)) {
			throw new ClipAssetPlaybackException(
					"PLAYBACK_ACCESS_DENIED",
					"An authenticated platform account is required for video playback.",
					403,
					false);
		}
		return actor;
	}

	private static String resolveRequestId(String requestId) {
		return Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString());
	}
}
