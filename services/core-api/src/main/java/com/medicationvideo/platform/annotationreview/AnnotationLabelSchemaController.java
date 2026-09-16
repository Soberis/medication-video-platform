package com.medicationvideo.platform.annotationreview;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import com.medicationvideo.platform.iamproject.ProjectActor;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/annotation-label-schema")
class AnnotationLabelSchemaController {

	private final AnnotationLabelSchemaService service;

	AnnotationLabelSchemaController(AnnotationLabelSchemaService service) {
		this.service = service;
	}

	@GetMapping
	ResponseEntity<AnnotationLabelSchemaResponse> getCurrent(@PathVariable UUID projectId,
			Authentication authentication) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.getCurrent(projectId, requireActor(authentication).actorId()));
	}

	private static ProjectActor requireActor(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof ProjectActor actor)) {
			throw new AnnotationTaskException("ANNOTATION_TASK_ACCESS_DENIED",
					"An authenticated platform account is required for annotation label schemas.", 403);
		}
		return actor;
	}

}

record AnnotationLabelCreateRequest(@NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]{0,63}$") String labelId,
		@NotBlank @jakarta.validation.constraints.Size(max = 128) @Pattern(regexp = "^[^\\p{Cc}]+$") String displayName,
		@NotBlank @jakarta.validation.constraints.Size(max = 512) @Pattern(
				regexp = "^[^\\p{Cc}]+$") String description) {
}
