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
import jakarta.validation.constraints.Size;

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

	@PostMapping("/capture-groups/{captureGroupId}/annotation-tasks")
	ResponseEntity<AnnotationTaskResponse> assignForHumanLabel(@PathVariable UUID projectId,
			@PathVariable UUID captureGroupId,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = IDEMPOTENCY_KEY_PATTERN) String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			@Valid @RequestBody AnnotationTaskAssignmentRequest request, Authentication authentication) {
		var actor = requireActor(authentication);
		var response = this.service.assignForHumanLabel(projectId, captureGroupId, actor.actorId(),
				request.assigneeActorId(), idempotencyKey,
				Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString()), MDC.get("traceId"));
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
				new AnnotationRevisionSubmitCommand(request.expectedTaskVersion(), request.expectedInputVersion(),
						request.correspondences()
							.stream()
							.map(correspondence -> new AnnotationCorrespondenceCommand(
									correspondence.correspondenceId(), correspondence.status(),
									correspondence.segments()
										.stream()
										.map(ref -> new AnnotationCorrespondenceSegmentRefCommand(ref.cameraNumber(),
												ref.segmentId()))
										.toList(),
									correspondence.reason()))
							.toList(),
						request.note()));
		return ResponseEntity.status(HttpStatus.CREATED)
			.location(URI.create("/api/v1/projects/" + projectId + "/annotation-tasks/" + annotationTaskId
					+ "/revisions/" + response.annotationRevisionId()))
			.cacheControl(CacheControl.noStore())
			.body(response);
	}

	@PutMapping("/annotation-tasks/{annotationTaskId}/camera-views/{cameraNumber}")
	ResponseEntity<AnnotationTaskResponse> updateCameraView(@PathVariable UUID projectId,
			@PathVariable UUID annotationTaskId, @PathVariable @jakarta.validation.constraints.Min(1) int cameraNumber,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = IDEMPOTENCY_KEY_PATTERN) String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			@Valid @RequestBody AnnotationCameraViewUpdateRequest request, Authentication authentication) {
		var response = this.service.updateCameraView(projectId, annotationTaskId, cameraNumber,
				requireActor(authentication).actorId(), isPlatformAdministrator(authentication), idempotencyKey,
				Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString()), MDC.get("traceId"),
				new AnnotationCameraViewUpdateCommand(request.expectedVersion(), request.expectedInputVersion(),
						request.state(),
						request.segments()
							.stream()
							.map(segment -> new AnnotationCameraViewSegmentCommand(segment.segmentId(),
									segment.startMs(), segment.endMs(), segment.label(), segment.sourceCandidateIds()))
							.toList(),
						request.note(), request.exclusionReason()));
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
				requirePlatformAdministrator(authentication).actorId(), idempotencyKey,
				Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString()), MDC.get("traceId"),
				new AnnotationReviewCommand(request.decision(), request.reviewNote(), request.reviewedCameraNumbers(),
						request.returnCameraNumbers()));
		return ResponseEntity.status(HttpStatus.CREATED)
			.location(URI
				.create("/api/v1/projects/" + projectId + "/annotation-revisions/" + annotationRevisionId + "/review"))
			.cacheControl(CacheControl.noStore())
			.body(response);
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

record AnnotationTaskAssignmentRequest(
		@NotBlank @Size(max = 128) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$") String assigneeActorId) {
}

record AnnotationRevisionRequest(@jakarta.validation.constraints.Min(0) long expectedTaskVersion,
		@NotBlank @Size(max = 128) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$") String expectedInputVersion,
		@NotNull @jakarta.validation.constraints.Size(
				max = 512) java.util.List<@NotNull @Valid AnnotationCorrespondenceRequest> correspondences,
		@jakarta.validation.constraints.Size(max = 1000) @Pattern(regexp = "^[^\\p{Cc}]*$") String note) {
}

record AnnotationCorrespondenceRequest(@NotNull UUID correspondenceId, @NotNull AnnotationCorrespondenceStatus status,
		@NotNull @jakarta.validation.constraints.Size(min = 1,
				max = 128) java.util.List<@NotNull @Valid AnnotationCorrespondenceSegmentRefRequest> segments,
		@Size(max = 1000) @Pattern(regexp = "^[^\\p{Cc}]*$") String reason) {
}

record AnnotationCorrespondenceSegmentRefRequest(
		@jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(64) int cameraNumber,
		@NotNull UUID segmentId) {
}

record AnnotationCameraViewUpdateRequest(@jakarta.validation.constraints.Min(0) long expectedVersion,
		@NotBlank @Size(max = 128) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$") String expectedInputVersion,
		@NotNull AnnotationTaskViewState state,
		@NotNull @jakarta.validation.constraints.Size(
				max = 200) java.util.List<@NotNull @Valid AnnotationCameraViewSegmentRequest> segments,
		@Size(max = 1000) @Pattern(regexp = "^[^\\p{Cc}]*$") String note,
		@Size(max = 1000) @Pattern(regexp = "^[^\\p{Cc}]*$") String exclusionReason) {
}

record AnnotationCameraViewSegmentRequest(@NotNull UUID segmentId, @jakarta.validation.constraints.Min(0) long startMs,
		@jakarta.validation.constraints.Min(1) long endMs,
		@NotBlank @Size(max = 64) @Pattern(regexp = "^[^\\p{Cc}]+$") String label,
		@NotNull @jakarta.validation.constraints.Size(max = 200) java.util.List<@NotNull UUID> sourceCandidateIds) {
}

record AnnotationSegmentRequest(@jakarta.validation.constraints.Min(0) long startMs,
		@jakarta.validation.constraints.Min(1) long endMs,
		@NotBlank @jakarta.validation.constraints.Size(max = 64) @Pattern(regexp = "^[^\\p{Cc}]+$") String label) {
}

record AnnotationReviewRequest(@NotNull AnnotationReviewDecision decision,
		@jakarta.validation.constraints.Size(max = 1000) @Pattern(regexp = "^[^\\p{Cc}]*$") String reviewNote,
		@jakarta.validation.constraints.Size(
				max = 64) java.util.List<@NotNull @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(64) Integer> reviewedCameraNumbers,
		@jakarta.validation.constraints.Size(
				max = 64) java.util.List<@NotNull @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(64) Integer> returnCameraNumbers) {
}

record AnnotationTaskErrorResponse(String errorCode, String errorMessage, boolean retryable, String requestId) {
}

@RestControllerAdvice(assignableTypes = { AnnotationTaskController.class, AnnotationLabelSchemaController.class,
		GlobalAnnotationLabelSchemaController.class })
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
				"The CaptureGroup already has an annotation task in this project.");
	}

	private static ResponseEntity<AnnotationTaskErrorResponse> error(HttpStatus status, String errorCode,
			String message) {
		return ResponseEntity.status(status)
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.cacheControl(CacheControl.noStore())
			.body(new AnnotationTaskErrorResponse(errorCode, message, false, UUID.randomUUID().toString()));
	}

}
