package com.medicationvideo.platform.annotationreview;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import com.medicationvideo.platform.assetcapture.AssetCatalogQueryException;
import com.medicationvideo.platform.iamproject.ProjectAccessException;
import com.medicationvideo.platform.iamproject.ProjectActor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
class AnnotationTaskController {

	private static final String IDEMPOTENCY_KEY_PATTERN = "^[A-Za-z0-9._:-]{16,128}$";

	private static final String REQUEST_ID_PATTERN = "^[A-Za-z0-9._:-]{1,128}$";

	private final AnnotationTaskService service;

	AnnotationTaskController(AnnotationTaskService service) {
		this.service = service;
	}

	@GetMapping("/annotation-tasks")
	ResponseEntity<AnnotationTaskListResponse> list(@PathVariable UUID projectId, Authentication authentication) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.list(projectId, requireActor(authentication).actorId(),
					isPlatformAdministrator(authentication)));
	}

	@PostMapping("/clip-assets/{clipAssetId}/annotation-tasks")
	ResponseEntity<AnnotationTaskResponse> create(@PathVariable UUID projectId, @PathVariable UUID clipAssetId,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = IDEMPOTENCY_KEY_PATTERN) String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			@Valid @RequestBody AnnotationTaskRequest request, Authentication authentication) {
		var response = this.service.create(projectId, clipAssetId,
				requirePlatformAdministrator(authentication).actorId(), idempotencyKey,
				Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString()), MDC.get("traceId"),
				new AnnotationTaskCreateRequest(request.assigneeActorId()));
		return ResponseEntity.status(HttpStatus.CREATED)
			.location(URI.create("/api/v1/projects/" + projectId + "/annotation-tasks/" + response.annotationTaskId()))
			.cacheControl(CacheControl.noStore())
			.body(response);
	}

	@PostMapping("/annotation-tasks/{annotationTaskId}/revisions")
	ResponseEntity<AnnotationRevisionResponse> submitRevision(@PathVariable UUID projectId,
			@PathVariable UUID annotationTaskId,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = IDEMPOTENCY_KEY_PATTERN) String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			@Valid @RequestBody AnnotationRevisionRequest request, Authentication authentication) {
		var response = this.service.submitRevision(projectId, annotationTaskId, requireActor(authentication).actorId(),
				idempotencyKey, Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString()),
				MDC.get("traceId"),
				new AnnotationRevisionSubmitCommand(request.segments()
					.stream()
					.map(segment -> new AnnotationSegmentCommand(segment.startMs(), segment.endMs(), segment.label()))
					.toList(), request.note()));
		return ResponseEntity.status(HttpStatus.CREATED)
			.location(URI.create("/api/v1/projects/" + projectId + "/annotation-tasks/" + annotationTaskId
					+ "/revisions/" + response.annotationRevisionId()))
			.cacheControl(CacheControl.noStore())
			.body(response);
	}

	@PutMapping("/annotation-tasks/{annotationTaskId}/primary-view")
	ResponseEntity<AnnotationTaskResponse> selectPrimaryView(@PathVariable UUID projectId,
			@PathVariable UUID annotationTaskId,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			@Valid @RequestBody AnnotationPrimaryViewRequest request, Authentication authentication) {
		var response = this.service.selectPrimaryView(projectId, annotationTaskId,
				requireActor(authentication).actorId(), request.clipAssetId(),
				Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString()), MDC.get("traceId"));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}

	@PostMapping("/annotation-revisions/{annotationRevisionId}/review")
	ResponseEntity<AnnotationReviewResponse> reviewRevision(@PathVariable UUID projectId,
			@PathVariable UUID annotationRevisionId,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = IDEMPOTENCY_KEY_PATTERN) String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			@Valid @RequestBody AnnotationReviewRequest request, Authentication authentication) {
		var response = this.service.reviewRevision(projectId, annotationRevisionId,
				requirePlatformAdministrator(authentication).actorId(), isPlatformAdministrator(authentication),
				idempotencyKey, Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString()),
				MDC.get("traceId"), new AnnotationReviewCommand(request.decision(), request.reviewNote()));
		return ResponseEntity.status(HttpStatus.CREATED)
			.location(URI
				.create("/api/v1/projects/" + projectId + "/annotation-revisions/" + annotationRevisionId + "/review"))
			.cacheControl(CacheControl.noStore())
			.body(response);
	}

	@PostMapping("/annotation-revisions/{annotationRevisionId}/post-cleaning-rechecks")
	ResponseEntity<PostCleaningResultResponse> recheckPostCleaning(@PathVariable UUID projectId,
			@PathVariable UUID annotationRevisionId,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			Authentication authentication) {
		var response = this.service.recheckPostCleaning(projectId, annotationRevisionId,
				requirePlatformAdministrator(authentication).actorId(),
				Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString()), MDC.get("traceId"));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}

	@PostMapping("/annotation-revisions/{annotationRevisionId}/capture-group-cleaning-rechecks")
	ResponseEntity<CaptureGroupCleaningResponse> recheckCaptureGroupCleaning(@PathVariable UUID projectId,
			@PathVariable UUID annotationRevisionId,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			@Valid @RequestBody(required = false) CaptureGroupCleaningRequest request, Authentication authentication) {
		var mappings = request == null
				? java.util.Map.<UUID, com.medicationvideo.platform.qualitycleaning.CaptureGroupCleaningService.SupportIntervalMapping>of()
				: request.supportMappings()
					.stream()
					.collect(java.util.stream.Collectors.toMap(SupportIntervalMappingRequest::clipAssetId,
							AnnotationTaskController::toSupportIntervalMapping));
		var response = this.service.recheckCaptureGroupCleaning(projectId, annotationRevisionId,
				requirePlatformAdministrator(authentication).actorId(),
				Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString()), MDC.get("traceId"),
				mappings);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}

	private static com.medicationvideo.platform.qualitycleaning.CaptureGroupCleaningService.SupportIntervalMapping toSupportIntervalMapping(
			SupportIntervalMappingRequest request) {
		return new com.medicationvideo.platform.qualitycleaning.CaptureGroupCleaningService.SupportIntervalMapping(
				request.validated(), request.mappingVersion(), request.provenance(),
				request.intervals() == null ? java.util.List.of() : request.intervals()
					.stream()
					.map(interval -> new com.medicationvideo.platform.qualitycleaning.CaptureGroupCleaningService.Interval(
							interval.startMs(), interval.endMs()))
					.toList());
	}

	private static ProjectActor requireActor(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof ProjectActor actor)) {
			throw new AnnotationTaskException("ANNOTATION_TASK_ACCESS_DENIED",
					"An authenticated platform account is required for annotation tasks.", 403);
		}
		return actor;
	}

	private static ProjectActor requirePlatformAdministrator(Authentication authentication) {
		var actor = requireActor(authentication);
		if (!isPlatformAdministrator(authentication)) {
			throw new ProjectAccessException("PROJECT_ACCESS_DENIED", "A platform administrator account is required.",
					403);
		}
		return actor;
	}

	private static boolean isPlatformAdministrator(Authentication authentication) {
		return authentication != null && authentication.getAuthorities()
			.stream()
			.anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
	}

}

record AnnotationTaskRequest(
		@NotNull @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$") String assigneeActorId) {
}

record AnnotationPrimaryViewRequest(@NotNull UUID clipAssetId) {
}

record AnnotationRevisionRequest(
		@NotNull @jakarta.validation.constraints.Size(min = 1,
				max = 200) java.util.List<@Valid AnnotationSegmentRequest> segments,
		@jakarta.validation.constraints.Size(max = 1000) @Pattern(regexp = "^[^\\p{Cc}]*$") String note) {
}

record AnnotationSegmentRequest(@jakarta.validation.constraints.Min(0) long startMs,
		@jakarta.validation.constraints.Min(1) long endMs,
		@NotBlank @jakarta.validation.constraints.Size(max = 64) @Pattern(regexp = "^[^\\p{Cc}]+$") String label) {
}

record AnnotationReviewRequest(@NotNull AnnotationReviewDecision decision,
		@jakarta.validation.constraints.Size(max = 1000) @Pattern(regexp = "^[^\\p{Cc}]*$") String reviewNote) {
}

record CaptureGroupCleaningRequest(@jakarta.validation.constraints.Size(
		max = 64) java.util.List<@Valid SupportIntervalMappingRequest> supportMappings) {

	CaptureGroupCleaningRequest {
		supportMappings = supportMappings == null ? java.util.List.of() : java.util.List.copyOf(supportMappings);
	}
}

record SupportIntervalMappingRequest(@NotNull UUID clipAssetId, boolean validated,
		@NotBlank @jakarta.validation.constraints.Size(max = 128) @Pattern(
				regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$") String mappingVersion,
		@NotBlank @jakarta.validation.constraints.Size(max = 500) @Pattern(regexp = "^[^\\p{Cc}]+$") String provenance,
		@jakarta.validation.constraints.Size(max = 200) java.util.List<@Valid SupportIntervalRequest> intervals) {
}

record SupportIntervalRequest(@jakarta.validation.constraints.Min(0) long startMs,
		@jakarta.validation.constraints.Min(1) long endMs) {
}

record AnnotationTaskErrorResponse(String errorCode, String errorMessage, boolean retryable, String requestId) {
}

@RestControllerAdvice(assignableTypes = { AnnotationTaskController.class, AnnotationLabelSchemaController.class })
class AnnotationTaskExceptionHandler {

	@ExceptionHandler(ProjectAccessException.class)
	ResponseEntity<AnnotationTaskErrorResponse> projectAccess(ProjectAccessException exception) {
		return error(HttpStatus.valueOf(exception.getStatusCode()), exception.getErrorCode(), exception.getMessage());
	}

	@ExceptionHandler(AssetCatalogQueryException.class)
	ResponseEntity<AnnotationTaskErrorResponse> assetNotFound(AssetCatalogQueryException exception) {
		return error(HttpStatus.NOT_FOUND, exception.getErrorCode(), exception.getMessage());
	}

	@ExceptionHandler(AnnotationTaskException.class)
	ResponseEntity<AnnotationTaskErrorResponse> annotationTask(AnnotationTaskException exception) {
		return error(HttpStatus.valueOf(exception.getStatusCode()), exception.getErrorCode(), exception.getMessage());
	}

	@ExceptionHandler({ MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
			HttpMessageNotReadableException.class })
	ResponseEntity<AnnotationTaskErrorResponse> invalidRequest(Exception exception) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_ANNOTATION_TASK", "The annotation task request is invalid.");
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<AnnotationTaskErrorResponse> conflict(DataIntegrityViolationException exception) {
		return error(HttpStatus.CONFLICT, "ANNOTATION_TASK_ALREADY_EXISTS",
				"The clip asset already has an annotation task in this project.");
	}

	private static ResponseEntity<AnnotationTaskErrorResponse> error(HttpStatus status, String errorCode,
			String message) {
		return ResponseEntity.status(status)
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.cacheControl(CacheControl.noStore())
			.body(new AnnotationTaskErrorResponse(errorCode, message, false, UUID.randomUUID().toString()));
	}

}
