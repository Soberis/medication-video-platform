package com.medicationvideo.platform.mediaprivacy;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medicationvideo.platform.assetcapture.UploadActor;
import com.medicationvideo.platform.iamproject.ProjectAccessException;
import com.medicationvideo.platform.iamproject.ProjectAccessService;
import com.medicationvideo.platform.iamproject.ProjectRole;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
class PrivacyAssessmentController {

	private static final String REQUEST_ID_PATTERN = "^[A-Za-z0-9._:-]{1,128}$";

	private final PrivacyAssessmentService service;
	private final ProjectAccessService projectAccessService;

	PrivacyAssessmentController(
			PrivacyAssessmentService service,
			ProjectAccessService projectAccessService) {
		this.service = service;
		this.projectAccessService = projectAccessService;
	}

	@PostMapping("/privacy-assessments")
	ResponseEntity<PrivacyAssessmentResponse> assess(
			@PathVariable UUID projectId,
			@RequestHeader(name = "Idempotency-Key")
			@NotBlank @Pattern(regexp = "^[A-Za-z0-9._:-]{16,128}$") String idempotencyKey,
			@RequestHeader(name = "X-Request-Id", required = false)
			@Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			@RequestBody @Valid PrivacyAssessmentRequest request,
			Authentication authentication) {
		var actor = requireAdministrator(authentication);
		var assessment = this.service.assess(
				projectId,
				actor.actorId(),
				idempotencyKey,
				Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString()),
				MDC.get("traceId"),
				request);
		return ResponseEntity.status(HttpStatus.CREATED)
				.cacheControl(CacheControl.noStore())
				.location(URI.create(
						"/api/v1/projects/" + projectId + "/privacy-assessments/"
								+ assessment.getPrivacyAssessmentId()))
				.body(PrivacyAssessmentResponse.from(assessment));
	}

	@GetMapping("/privacy-assessments/{privacyAssessmentId}")
	ResponseEntity<PrivacyAssessmentResponse> get(
			@PathVariable UUID projectId,
			@PathVariable UUID privacyAssessmentId,
			Authentication authentication) {
		var actor = requireAdministrator(authentication);
		requireProjectAdministrator(projectId, actor);
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(PrivacyAssessmentResponse.from(
						this.service.get(projectId, privacyAssessmentId)));
	}

	@GetMapping("/clip-assets/{clipAssetId}/privacy-status")
	ResponseEntity<PrivacyStatusResponse> status(
			@PathVariable UUID projectId,
			@PathVariable UUID clipAssetId,
			Authentication authentication) {
		var actor = requireAdministrator(authentication);
		requireProjectAdministrator(projectId, actor);
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(this.service.status(projectId, clipAssetId));
	}

	private static UploadActor requireAdministrator(Authentication authentication) {
		if (authentication == null
				|| !(authentication.getPrincipal() instanceof UploadActor actor)
				|| authentication.getAuthorities().stream()
						.noneMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))) {
			throw new MediaJobAuthorizationException();
		}
		return actor;
	}

	private void requireProjectAdministrator(UUID projectId, UploadActor actor) {
		if (this.projectAccessService.requireMember(projectId, actor.actorId()) != ProjectRole.ADMIN) {
			throw new ProjectAccessException(
					"PROJECT_ACCESS_DENIED",
					"Project administrator membership is required.",
					403);
		}
	}
}
