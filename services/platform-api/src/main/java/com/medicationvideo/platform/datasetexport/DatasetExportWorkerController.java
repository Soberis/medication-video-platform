package com.medicationvideo.platform.datasetexport;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

@Validated
@RestController
@RequestMapping("/api/v1/internal/dataset-exports")
class DatasetExportWorkerController {

	private static final String LEASE_PATTERN = "^[A-Za-z0-9_-]{43,128}$";

	private final DatasetExportExecutionService service;

	DatasetExportWorkerController(DatasetExportExecutionService service) {
		this.service = service;
	}

	@PostMapping("/{exportId}/attempts/{attempt}/lease")
	ResponseEntity<DatasetExportLeaseResponse> acquire(@PathVariable UUID exportId, @PathVariable @Min(1) int attempt,
			@RequestHeader(name = "X-Export-Lease-Token") @Pattern(regexp = LEASE_PATTERN) String leaseToken,
			@RequestBody @Valid DatasetExportLeaseRequest request, Authentication authentication) {
		requireWorker(authentication);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.acquire(exportId, attempt, leaseToken, request));
	}

	@PostMapping("/{exportId}/attempts/{attempt}/heartbeat")
	ResponseEntity<DatasetExportHeartbeatResponse> heartbeat(@PathVariable UUID exportId,
			@PathVariable @Min(1) int attempt,
			@RequestHeader(name = "X-Export-Lease-Token") @Pattern(regexp = LEASE_PATTERN) String leaseToken,
			@RequestBody @Valid DatasetExportLeaseRequest request, Authentication authentication) {
		requireWorker(authentication);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.heartbeat(exportId, attempt, leaseToken, request));
	}

	@PostMapping("/{exportId}/attempts/{attempt}/output-parts/{partNumber}/authorization")
	ResponseEntity<DatasetExportPartAuthorizationResponse> authorizePart(@PathVariable UUID exportId,
			@PathVariable @Min(1) int attempt, @PathVariable @Min(1) @Max(10_000) int partNumber,
			@RequestHeader(name = "X-Export-Lease-Token") @Pattern(regexp = LEASE_PATTERN) String leaseToken,
			@RequestBody @Valid DatasetExportLeaseRequest request, Authentication authentication) {
		requireWorker(authentication);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.authorizePart(exportId, attempt, partNumber, leaseToken, request));
	}

	@PutMapping("/{exportId}/attempts/{attempt}/result")
	ResponseEntity<DatasetExportResultAcceptedResponse> acceptResult(@PathVariable UUID exportId,
			@PathVariable @Min(1) int attempt,
			@RequestHeader(name = "X-Export-Lease-Token") @Pattern(regexp = LEASE_PATTERN) String leaseToken,
			@RequestBody @Valid DatasetExportResultRequest request, Authentication authentication) {
		requireWorker(authentication);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.acceptResult(exportId, attempt, leaseToken, request));
	}

	private static void requireWorker(Authentication authentication) {
		if (authentication == null || authentication.getAuthorities()
			.stream()
			.noneMatch(authority -> authority.getAuthority().equals("ROLE_EXPORT_WORKER"))) {
			throw new DatasetExportExecutionException("EXPORT_WORKER_UNAUTHORIZED",
					"A valid Export Worker service identity is required.", false);
		}
	}

}

record DatasetExportWorkerErrorResponse(String errorCode, String errorMessage, boolean retryable, String requestId) {
}

@RestControllerAdvice(assignableTypes = DatasetExportWorkerController.class)
class DatasetExportWorkerExceptionHandler {

	@ExceptionHandler(DatasetExportExecutionException.class)
	ResponseEntity<DatasetExportWorkerErrorResponse> execution(DatasetExportExecutionException exception) {
		var status = switch (exception.getErrorCode()) {
			case "DATASET_EXPORT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
			case "EXPORT_WORKER_UNAUTHORIZED" -> HttpStatus.FORBIDDEN;
			default -> HttpStatus.CONFLICT;
		};
		return error(status, exception.getErrorCode(), exception.getMessage(), exception.isRetryable());
	}

	@ExceptionHandler({ MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
			ConstraintViolationException.class, HttpMessageNotReadableException.class })
	ResponseEntity<DatasetExportWorkerErrorResponse> invalidRequest(Exception exception) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_DATASET_EXPORT_WORKER_REQUEST",
				"The dataset export Worker request is invalid.", false);
	}

	private static ResponseEntity<DatasetExportWorkerErrorResponse> error(HttpStatus status, String code,
			String message, boolean retryable) {
		return ResponseEntity.status(status)
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.cacheControl(CacheControl.noStore())
			.body(new DatasetExportWorkerErrorResponse(code, message, retryable, UUID.randomUUID().toString()));
	}

}
