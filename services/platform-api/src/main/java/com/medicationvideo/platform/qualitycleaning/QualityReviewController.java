package com.medicationvideo.platform.qualitycleaning;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
import com.medicationvideo.platform.qualitycleaning.QualityReviewService.QualityReviewCommand;
import com.medicationvideo.platform.qualitycleaning.QualityReviewService.QualityReviewResult;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/clip-assets/{clipAssetId}/quality-review")
class QualityReviewController {

	private static final String REQUEST_ID_PATTERN = "^[A-Za-z0-9._:-]{1,128}$";

	private final QualityReviewService service;

	QualityReviewController(QualityReviewService service) {
		this.service = service;
	}

	@GetMapping
	ResponseEntity<QualityReviewResponse> get(
			@PathVariable UUID projectId,
			@PathVariable UUID clipAssetId,
			Authentication authentication) {
		var result = this.service.get(projectId, clipAssetId, requireActor(authentication).actorId());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(QualityReviewResponse.from(result));
	}

	@PutMapping
	ResponseEntity<QualityReviewResponse> record(
			@PathVariable UUID projectId,
			@PathVariable UUID clipAssetId,
			@RequestHeader(name = "X-Request-Id", required = false)
			@Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			@Valid @RequestBody QualityReviewRequest request,
			Authentication authentication) {
		var result = this.service.record(
				projectId,
				clipAssetId,
				requireActor(authentication).actorId(),
				new QualityReviewCommand(request.decision(), request.reasonCode(), request.expectedVersion()),
				Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString()),
				MDC.get("traceId"));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(QualityReviewResponse.from(result));
	}

	private static ProjectActor requireActor(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof ProjectActor actor)) {
			throw new QualityReviewException(
					"QUALITY_REVIEW_ACCESS_DENIED",
					"An authenticated platform account is required for quality review.",
					403);
		}
		return actor;
	}
}

record QualityReviewRequest(
		@NotNull QualityReviewDecision decision,
		@NotNull QualityReviewReasonCode reasonCode,
		@NotNull @PositiveOrZero Long expectedVersion) {
}

record QualityReviewResponse(
		UUID qualityReviewId,
		UUID projectId,
		UUID clipAssetId,
		String decision,
		QualityReviewReasonCode reasonCode,
		String reviewedBy,
		Instant reviewedAt,
		long version) {

	static QualityReviewResponse from(QualityReviewResult result) {
		return new QualityReviewResponse(
				result.qualityReviewId(),
				result.projectId(),
				result.clipAssetId(),
				result.decision(),
				result.reasonCode(),
				result.reviewedBy(),
				result.reviewedAt(),
				result.version());
	}
}

record QualityReviewErrorResponse(
		String errorCode,
		String errorMessage,
		boolean retryable,
		String requestId) {
}

@RestControllerAdvice(assignableTypes = QualityReviewController.class)
class QualityReviewExceptionHandler {

	@ExceptionHandler(ProjectAccessException.class)
	ResponseEntity<QualityReviewErrorResponse> projectAccess(ProjectAccessException exception) {
		return error(HttpStatus.valueOf(exception.getStatusCode()), exception.getErrorCode(), exception.getMessage());
	}

	@ExceptionHandler(AssetCatalogQueryException.class)
	ResponseEntity<QualityReviewErrorResponse> assetNotFound(AssetCatalogQueryException exception) {
		return error(HttpStatus.NOT_FOUND, exception.getErrorCode(), exception.getMessage());
	}

	@ExceptionHandler(QualityReviewException.class)
	ResponseEntity<QualityReviewErrorResponse> qualityReview(QualityReviewException exception) {
		return error(HttpStatus.valueOf(exception.getStatusCode()), exception.getErrorCode(), exception.getMessage());
	}

	@ExceptionHandler({
		MethodArgumentNotValidException.class,
		HandlerMethodValidationException.class,
		HttpMessageNotReadableException.class
	})
	ResponseEntity<QualityReviewErrorResponse> invalidRequest(Exception exception) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_QUALITY_REVIEW", "The quality review request is invalid.");
	}

	@ExceptionHandler({
		ObjectOptimisticLockingFailureException.class,
		DataIntegrityViolationException.class
	})
	ResponseEntity<QualityReviewErrorResponse> conflict(Exception exception) {
		return error(HttpStatus.CONFLICT, "QUALITY_REVIEW_CONFLICT", "The quality review changed; reload it before trying again.");
	}

	private static ResponseEntity<QualityReviewErrorResponse> error(
			HttpStatus status,
			String errorCode,
			String message) {
		return ResponseEntity.status(status)
				.contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.cacheControl(CacheControl.noStore())
				.body(new QualityReviewErrorResponse(errorCode, message, false, UUID.randomUUID().toString()));
	}
}
