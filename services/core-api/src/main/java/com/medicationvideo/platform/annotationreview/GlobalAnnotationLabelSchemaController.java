package com.medicationvideo.platform.annotationreview;

import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medicationvideo.platform.iamproject.PlatformActor;

@RestController
@RequestMapping("/api/v1/annotation-label-schema")
class GlobalAnnotationLabelSchemaController {

	private final AnnotationLabelSchemaService service;

	GlobalAnnotationLabelSchemaController(AnnotationLabelSchemaService service) {
		this.service = service;
	}

	@GetMapping
	ResponseEntity<AnnotationLabelSchemaResponse> getCurrent(Authentication authentication) {
		requireAccount(authentication, false);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(this.service.getGlobalCurrent());
	}

	@PostMapping("/labels")
	ResponseEntity<AnnotationLabelSchemaResponse> addLabel(Authentication authentication,
			@RequestHeader(name = "X-Request-Id", required = false) String requestId,
			@Valid @RequestBody AnnotationLabelCreateRequest request) {
		var actor = requireAccount(authentication, true);
		return ResponseEntity.status(201)
			.cacheControl(CacheControl.noStore())
			.body(this.service.addGlobalLabel(actor.actorId(), request.labelId().trim(), request.displayName().trim(),
					request.description().trim(), requestId == null ? UUID.randomUUID().toString() : requestId,
					org.slf4j.MDC.get("traceId")));
	}

	private static PlatformActor requireAccount(Authentication authentication, boolean administrator) {
		if (authentication == null || !(authentication.getPrincipal() instanceof PlatformActor actor)
				|| (administrator && authentication.getAuthorities()
					.stream()
					.noneMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority())))) {
			throw new AnnotationTaskException("ANNOTATION_TASK_ACCESS_DENIED",
					"Platform account authority is required.", 403);
		}
		return actor;
	}

}
