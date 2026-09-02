package com.medicationvideo.platform.datasetexport;

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
import org.springframework.web.bind.annotation.RestController;

import com.medicationvideo.platform.iamproject.ProjectAccessException;
import com.medicationvideo.platform.iamproject.ProjectAccessService;
import com.medicationvideo.platform.iamproject.ProjectActor;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/hand-landmarks/v1")
class HandLandmarkTrainingExportController {

	private final HandLandmarkTrainingExportService service;

	private final ProjectAccessService projectAccessService;

	HandLandmarkTrainingExportController(HandLandmarkTrainingExportService service,
			ProjectAccessService projectAccessService) {
		this.service = service;
		this.projectAccessService = projectAccessService;
	}

	@GetMapping("/preview")
	ResponseEntity<HandLandmarkTrainingExportPreviewResponse> preview(@PathVariable UUID projectId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		this.projectAccessService.requireAdministrator(projectId, actor.actorId());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(this.service.preview(projectId));
	}

	@GetMapping(value = "/download", produces = HandLandmarkTrainingExportService.MEDIA_TYPE)
	ResponseEntity<byte[]> download(@PathVariable UUID projectId, Authentication authentication) {
		var actor = requireActor(authentication);
		this.projectAccessService.requireAdministrator(projectId, actor.actorId());
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.contentType(MediaType.parseMediaType(HandLandmarkTrainingExportService.MEDIA_TYPE))
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment()
						.filename(HandLandmarkTrainingExportService.FILE_NAME)
						.build()
						.toString())
			.body(this.service.download(projectId));
	}

	private static ProjectActor requireActor(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof ProjectActor actor)) {
			throw new ProjectAccessException("PROJECT_ACCESS_DENIED", "An authenticated platform account is required.",
					403);
		}
		return actor;
	}

}
