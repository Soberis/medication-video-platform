package com.medicationvideo.platform.annotationreview;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.medicationvideo.platform.iamproject.ProjectAccessException;
import com.medicationvideo.platform.iamproject.ProjectAccessService;
import com.medicationvideo.platform.iamproject.ProjectActor;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/human-annotations/v1")
class HumanAnnotationTrainingExportController {

	private final HumanAnnotationTrainingExportService service;

	private final ProjectAccessService projectAccessService;

	HumanAnnotationTrainingExportController(HumanAnnotationTrainingExportService service,
			ProjectAccessService projectAccessService) {
		this.service = service;
		this.projectAccessService = projectAccessService;
	}

	@GetMapping(value = "/download", produces = HumanAnnotationTrainingExportService.MEDIA_TYPE)
	ResponseEntity<byte[]> download(@PathVariable UUID projectId,
			@RequestParam(name = "capture_group_id", required = false) UUID captureGroupId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		this.projectAccessService.requireAdministrator(projectId, actor.actorId());
		var generatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.contentType(MediaType.parseMediaType(HumanAnnotationTrainingExportService.MEDIA_TYPE))
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment()
						.filename(HumanAnnotationTrainingExportService.fileName(captureGroupId, generatedAt))
						.build()
						.toString())
			.body(this.service.download(projectId, captureGroupId));
	}

	private static ProjectActor requireActor(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof ProjectActor actor)) {
			throw new ProjectAccessException("PROJECT_ACCESS_DENIED", "An authenticated platform account is required.",
					403);
		}
		return actor;
	}

}
