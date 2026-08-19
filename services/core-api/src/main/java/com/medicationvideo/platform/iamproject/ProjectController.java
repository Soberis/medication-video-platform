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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Validated
@RestController
@RequestMapping("/api/v1/projects")
class ProjectController {

	private static final String IDEMPOTENCY_KEY_PATTERN = "^[A-Za-z0-9._:-]{16,128}$";

	private static final String REQUEST_ID_PATTERN = "^[A-Za-z0-9._:-]{1,128}$";

	private static final String ACTOR_ID_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$";

	private final ProjectApplicationService service;

	ProjectController(ProjectApplicationService service) {
		this.service = service;
	}

	@PostMapping
	ResponseEntity<ProjectResponse> create(
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = IDEMPOTENCY_KEY_PATTERN) String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			@Valid @RequestBody ProjectCreateRequest request, Authentication authentication) {
		var actor = requireActor(authentication);
		requireProvisioningAdministrator(authentication);
		var response = this.service.create(actor.actorId(), idempotencyKey, resolveRequestId(requestId),
				MDC.get("traceId"), request);
		return ResponseEntity.status(HttpStatus.CREATED)
			.location(URI.create("/api/v1/projects/" + response.projectId()))
			.cacheControl(CacheControl.noStore())
			.body(response);
	}

	@GetMapping
	ResponseEntity<ProjectListResponse> list(Authentication authentication) {
		var actor = requireActor(authentication);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.list(actor.actorId(), hasProvisioningAdministrator(authentication)));
	}

	@GetMapping("/{projectId}")
	ResponseEntity<ProjectResponse> get(@PathVariable UUID projectId, Authentication authentication) {
		var actor = requireActor(authentication);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.get(projectId, actor.actorId()));
	}

	@PutMapping("/{projectId}")
	ResponseEntity<ProjectResponse> rename(@PathVariable UUID projectId,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			@Valid @RequestBody ProjectRenameRequest request, Authentication authentication) {
		var actor = requireActor(authentication);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.rename(projectId, actor.actorId(), resolveRequestId(requestId), MDC.get("traceId"),
					request));
	}

	@DeleteMapping("/{projectId}")
	ResponseEntity<Void> archive(@PathVariable UUID projectId,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		this.service.archive(projectId, actor.actorId(), resolveRequestId(requestId), MDC.get("traceId"));
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	@GetMapping("/{projectId}/memberships")
	ResponseEntity<ProjectMembershipListResponse> listMemberships(@PathVariable UUID projectId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.listMemberships(projectId, actor.actorId()));
	}

	@PutMapping("/{projectId}/memberships/{targetActorId}")
	ResponseEntity<ProjectMembershipResponse> upsertMembership(@PathVariable UUID projectId,
			@PathVariable @Size(min = 1, max = 128) @Pattern(regexp = ACTOR_ID_PATTERN) String targetActorId,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = IDEMPOTENCY_KEY_PATTERN) String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			@Valid @RequestBody ProjectMembershipRequest request, Authentication authentication) {
		var actor = requireActor(authentication);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.upsertMembership(projectId, actor.actorId(), targetActorId, idempotencyKey,
					resolveRequestId(requestId), MDC.get("traceId"), request));
	}

	@DeleteMapping("/{projectId}/memberships/{targetActorId}")
	ResponseEntity<ProjectMembershipResponse> removeMembership(@PathVariable UUID projectId,
			@PathVariable @Size(min = 1, max = 128) @Pattern(regexp = ACTOR_ID_PATTERN) String targetActorId,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = IDEMPOTENCY_KEY_PATTERN) String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.removeMembership(projectId, actor.actorId(), targetActorId, idempotencyKey,
					resolveRequestId(requestId), MDC.get("traceId")));
	}

	private static ProjectActor requireActor(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof ProjectActor actor)) {
			throw new ProjectApiException("PROJECT_ACCESS_DENIED", "An authenticated project actor is required.", 403);
		}
		return actor;
	}

	private static void requireProvisioningAdministrator(Authentication authentication) {
		if (!hasProvisioningAdministrator(authentication)) {
			throw new ProjectApiException("PROJECT_ACCESS_DENIED",
					"Administrator authority is required to create a project.", 403);
		}
	}

	private static boolean hasProvisioningAdministrator(Authentication authentication) {
		return authentication != null && authentication.getAuthorities()
			.stream()
			.anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
	}

	private static String resolveRequestId(String requestId) {
		return Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString());
	}

}
