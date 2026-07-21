package com.medicationvideo.platform.iamproject;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medicationvideo.platform.assetcapture.UploadActor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Validated
@RestController
@RequestMapping("/api/v1/admin/accounts")
class PlatformAccountManagementController {

	private static final String REQUEST_ID_PATTERN = "^[A-Za-z0-9._:-]{1,128}$";
	private static final String USERNAME_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._:@-]{2,127}$";

	private final PlatformAccountManagementService service;

	PlatformAccountManagementController(PlatformAccountManagementService service) {
		this.service = service;
	}

	@GetMapping
	ResponseEntity<PlatformAccountListResponse> list(Authentication authentication) {
		requireAdministrator(authentication);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(this.service.list());
	}

	@PostMapping
	ResponseEntity<PlatformAccountResponse> create(
			@Valid @RequestBody PlatformAccountCreateRequest request,
			@RequestHeader(name = "X-Request-Id", required = false)
			@Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			Authentication authentication) {
		var administrator = requireAdministrator(authentication);
		var response = this.service.create(
				administrator.actorId(), resolveRequestId(requestId), MDC.get("traceId"), request);
		return ResponseEntity.status(HttpStatus.CREATED)
				.location(URI.create("/api/v1/admin/accounts/" + response.username()))
				.cacheControl(CacheControl.noStore())
				.body(response);
	}

	@PatchMapping("/{username}/enabled")
	ResponseEntity<PlatformAccountResponse> changeEnabled(
			@PathVariable @Size(min = 3, max = 128) @Pattern(regexp = USERNAME_PATTERN) String username,
			@Valid @RequestBody PlatformAccountEnabledRequest request,
			@RequestHeader(name = "X-Request-Id", required = false)
			@Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			Authentication authentication) {
		var administrator = requireAdministrator(authentication);
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(this.service.changeEnabled(
						administrator.actorId(), username, resolveRequestId(requestId), MDC.get("traceId"), request));
	}

	private static UploadActor requireAdministrator(Authentication authentication) {
		if (authentication == null
				|| !(authentication.getPrincipal() instanceof UploadActor actor)
				|| authentication.getAuthorities().stream()
						.noneMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))) {
			throw new ProjectApiException(
					"PLATFORM_ACCOUNT_ACCESS_DENIED",
					"Platform administrator authority is required.",
					403);
		}
		return actor;
	}

	private static String resolveRequestId(String requestId) {
		return Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString());
	}
}
