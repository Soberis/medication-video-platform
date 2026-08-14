package com.medicationvideo.platform.datasetexport;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.medicationvideo.platform.iamproject.ProjectAccessException;
import com.medicationvideo.platform.iamproject.ProjectAccessService;
import com.medicationvideo.platform.iamproject.ProjectActor;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/training-data")
class TrainingDataExportController {

	private final MinimalTrainingDataExportService service;

	private final ProjectAccessService projectAccessService;

	TrainingDataExportController(MinimalTrainingDataExportService service, ProjectAccessService projectAccessService) {
		this.service = service;
		this.projectAccessService = projectAccessService;
	}

	@GetMapping("/preview")
	ResponseEntity<TrainingDataExportPreviewResponse> preview(@PathVariable UUID projectId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		this.projectAccessService.requireAdministrator(projectId, actor.actorId());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(this.service.preview(projectId));
	}

	@GetMapping(value = "/download", produces = MinimalTrainingDataExportService.MEDIA_TYPE)
	ResponseEntity<byte[]> download(@PathVariable UUID projectId, Authentication authentication) {
		var actor = requireActor(authentication);
		this.projectAccessService.requireAdministrator(projectId, actor.actorId());
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.contentType(MediaType.parseMediaType(MinimalTrainingDataExportService.MEDIA_TYPE))
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment()
						.filename(MinimalTrainingDataExportService.FILE_NAME)
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

record TrainingDataExportErrorResponse(String errorCode, String errorMessage, boolean retryable, String requestId) {
}

@RestControllerAdvice(assignableTypes = TrainingDataExportController.class)
class TrainingDataExportExceptionHandler {

	@ExceptionHandler(ProjectAccessException.class)
	ResponseEntity<TrainingDataExportErrorResponse> projectAccess(ProjectAccessException exception) {
		return error(HttpStatus.valueOf(exception.getStatusCode()), exception.getErrorCode(), exception.getMessage(),
				false);
	}

	@ExceptionHandler(TrainingDataExportException.class)
	ResponseEntity<TrainingDataExportErrorResponse> export(TrainingDataExportException exception) {
		return error(HttpStatus.valueOf(exception.getStatusCode()), exception.getErrorCode(), exception.getMessage(),
				exception.getStatusCode() >= 500);
	}

	private static ResponseEntity<TrainingDataExportErrorResponse> error(HttpStatus status, String errorCode,
			String message, boolean retryable) {
		return ResponseEntity.status(status)
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.cacheControl(CacheControl.noStore())
			.body(new TrainingDataExportErrorResponse(errorCode, message, retryable, UUID.randomUUID().toString()));
	}

}
