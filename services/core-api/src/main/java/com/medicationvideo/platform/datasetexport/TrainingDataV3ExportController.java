package com.medicationvideo.platform.datasetexport;

import java.util.List;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.medicationvideo.platform.assetcapture.AssetCatalogQueryException;
import com.medicationvideo.platform.iamproject.ProjectAccessException;
import com.medicationvideo.platform.iamproject.ProjectAccessService;
import com.medicationvideo.platform.iamproject.ProjectActor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/training-data/v3")
class TrainingDataV3ExportController {

	private final TrainingDataV3ExportService service;

	private final ProjectAccessService projectAccessService;

	TrainingDataV3ExportController(TrainingDataV3ExportService service, ProjectAccessService projectAccessService) {
		this.service = service;
		this.projectAccessService = projectAccessService;
	}

	@GetMapping("/preview")
	ResponseEntity<TrainingDataV3ExportPreviewResponse> preview(@PathVariable UUID projectId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		this.projectAccessService.requireAdministrator(projectId, actor.actorId());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(this.service.preview(projectId));
	}

	@GetMapping(value = "/download", produces = TrainingDataV3ExportService.MEDIA_TYPE)
	ResponseEntity<byte[]> download(@PathVariable UUID projectId, Authentication authentication) {
		var actor = requireActor(authentication);
		this.projectAccessService.requireAdministrator(projectId, actor.actorId());
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.contentType(MediaType.parseMediaType(TrainingDataV3ExportService.MEDIA_TYPE))
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(TrainingDataV3ExportService.FILE_NAME).build().toString())
			.body(this.service.download(projectId));
	}

	@PostMapping("/validate-splits")
	ResponseEntity<TrainingDataV3SplitValidationResponse> validateSplits(@PathVariable UUID projectId,
			@Valid @RequestBody TrainingDataV3SplitValidationRequest request, Authentication authentication) {
		var actor = requireActor(authentication);
		this.projectAccessService.requireAdministrator(projectId, actor.actorId());
		TrainingDataV3ExportService.validateSplitAssignments(request.assignments()
			.stream()
			.map(assignment -> new TrainingDataV3ExportService.SplitAssignment(assignment.captureGroupId(),
					assignment.split()))
			.toList());
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(new TrainingDataV3SplitValidationResponse(TrainingDataV3ExportService.SPLIT_UNIT,
					request.assignments().size(), true));
	}

	private static ProjectActor requireActor(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof ProjectActor actor)) {
			throw new ProjectAccessException("PROJECT_ACCESS_DENIED", "An authenticated platform account is required.",
					403);
		}
		return actor;
	}

}

record TrainingDataV3SplitValidationRequest(
		@NotNull @Size(max = 10000) List<@Valid TrainingDataV3SplitAssignmentRequest> assignments) {
}

record TrainingDataV3SplitAssignmentRequest(@NotNull UUID captureGroupId,
		@NotBlank @Pattern(regexp = "^(?i:train|validation|test)$") String split) {
}

record TrainingDataV3SplitValidationResponse(String splitUnit, long assignmentCount, boolean valid) {
}

record TrainingDataExportErrorResponse(String errorCode, String errorMessage, boolean retryable, String requestId) {
}

@RestControllerAdvice(
		assignableTypes = { TrainingDataV3ExportController.class, HandLandmarkTrainingExportController.class })
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

	@ExceptionHandler(AssetCatalogQueryException.class)
	ResponseEntity<TrainingDataExportErrorResponse> captureGroup(AssetCatalogQueryException exception) {
		return error(HttpStatus.NOT_FOUND, exception.getErrorCode(), exception.getMessage(), false);
	}

	private static ResponseEntity<TrainingDataExportErrorResponse> error(HttpStatus status, String errorCode,
			String message, boolean retryable) {
		return ResponseEntity.status(status)
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.cacheControl(CacheControl.noStore())
			.body(new TrainingDataExportErrorResponse(errorCode, message, retryable, UUID.randomUUID().toString()));
	}

}
