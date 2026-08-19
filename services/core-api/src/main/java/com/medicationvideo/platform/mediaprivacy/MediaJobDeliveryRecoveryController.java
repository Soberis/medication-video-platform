package com.medicationvideo.platform.mediaprivacy;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medicationvideo.platform.iamproject.PlatformActor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Validated
@RestController
@RequestMapping("/api/v1")
class MediaJobDeliveryRecoveryController {

	private static final String REQUEST_ID_PATTERN = "^[A-Za-z0-9._:-]{1,128}$";

	private final MediaJobDeliveryRecoveryService service;

	MediaJobDeliveryRecoveryController(MediaJobDeliveryRecoveryService service) {
		this.service = service;
	}

	@PostMapping("/media-jobs/{mediaJobId}/delivery-recoveries")
	ResponseEntity<MediaJobDeliveryRecoveryResponse> recover(@PathVariable UUID mediaJobId,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = "^[A-Za-z0-9._:-]{16,128}$") String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			@RequestBody @Valid MediaJobDeliveryRecoveryRequest request, Authentication authentication) {
		var actor = requireAdministrator(authentication);
		var recovery = this.service.recover(actor.projectId(), actor.actorId(), mediaJobId, idempotencyKey,
				Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString()), MDC.get("traceId"),
				request);
		return ResponseEntity.status(HttpStatus.ACCEPTED)
			.cacheControl(CacheControl.noStore())
			.body(MediaJobDeliveryRecoveryResponse.from(recovery));
	}

	@PostMapping("/projects/{projectId}/clip-assets/{clipAssetId}/media-probe-delivery-recoveries")
	ResponseEntity<MediaJobDeliveryRecoveryResponse> recoverCurrentClipAssetMediaProbe(@PathVariable UUID projectId,
			@PathVariable UUID clipAssetId,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = "^[A-Za-z0-9._:-]{16,128}$") String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			@RequestBody @Valid MediaJobCurrentDeliveryRecoveryRequest request, Authentication authentication) {
		var actor = requireAdministrator(authentication);
		var recovery = this.service.recoverCurrentClipAssetMediaProbe(projectId, actor.actorId(), clipAssetId,
				idempotencyKey, Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString()),
				MDC.get("traceId"), request);
		return ResponseEntity.status(HttpStatus.ACCEPTED)
			.cacheControl(CacheControl.noStore())
			.body(MediaJobDeliveryRecoveryResponse.from(recovery));
	}

	private static PlatformActor requireAdministrator(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof PlatformActor actor)
				|| authentication.getAuthorities()
					.stream()
					.noneMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))) {
			throw new MediaJobAuthorizationException();
		}
		return actor;
	}

}
