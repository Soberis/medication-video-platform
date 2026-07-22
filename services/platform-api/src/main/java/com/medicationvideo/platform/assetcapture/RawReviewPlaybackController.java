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
class RawReviewPlaybackController {

	private static final String REQUEST_ID_PATTERN = "^[A-Za-z0-9._:-]{1,128}$";

	private final RawReviewPlaybackService service;

	RawReviewPlaybackController(RawReviewPlaybackService service) {
		this.service = service;
	}

	@PostMapping("/{clipAssetId}/raw-review-playback-grants")
	ResponseEntity<RawReviewPlaybackGrantResponse> grant(
			@PathVariable UUID projectId,
			@PathVariable UUID clipAssetId,
			@RequestHeader(name = "X-Request-Id", required = false)
			@Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			Authentication authentication) {
		var actor = requireGlobalAdministrator(authentication);
		var response = this.service.grant(
				projectId,
				clipAssetId,
				actor.actorId(),
				resolveRequestId(requestId),
				MDC.get("traceId"));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}

	private static ProjectActor requireGlobalAdministrator(Authentication authentication) {
		if (authentication == null
				|| !(authentication.getPrincipal() instanceof ProjectActor actor)
				|| authentication.getAuthorities().stream()
						.noneMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))) {
			throw new RawReviewPlaybackException(
					"RAW_REVIEW_PLAYBACK_ACCESS_DENIED",
					"A platform administrator is required for raw review playback.",
					403,
					false);
		}
		return actor;
	}

	private static String resolveRequestId(String requestId) {
		return Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString());
	}
}
