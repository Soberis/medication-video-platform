package com.medicationvideo.platform.datasetexport;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.security.core.GrantedAuthority;

import com.medicationvideo.platform.iamproject.PlatformActor;
import com.medicationvideo.platform.iamproject.ProjectAccessException;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Validated
@RestController
@RequestMapping("/api/v1/dataset-exports")
class DatasetExportManualReplayController {

	private static final String IDEMPOTENCY_KEY_PATTERN = "^[A-Za-z0-9._:-]{16,128}$";

	private static final String REQUEST_ID_PATTERN = "^[A-Za-z0-9._:-]{1,128}$";

	private final DatasetExportManualReplayService service;

	DatasetExportManualReplayController(DatasetExportManualReplayService service) {
		this.service = service;
	}

	@PostMapping("/{datasetExportId}/replays")
	ResponseEntity<DatasetExportManualReplayResponse> replay(@PathVariable UUID datasetExportId,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = IDEMPOTENCY_KEY_PATTERN) String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			@RequestBody @Valid DatasetExportManualReplayRequest request, Authentication authentication) {
		var actor = requireAdministrator(authentication);
		var replay = this.service.replay(actor.projectId(), actor.actorId(), datasetExportId, idempotencyKey,
				Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString()), MDC.get("traceId"),
				request);
		return ResponseEntity.status(HttpStatus.ACCEPTED)
			.cacheControl(CacheControl.noStore())
			.body(DatasetExportManualReplayResponse.from(replay));
	}

	private static PlatformActor requireAdministrator(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof PlatformActor actor)) {
			throw new DatasetVersionException("DATASET_EXPORT_REPLAY_ACCESS_DENIED",
					"An authenticated platform account is required for dataset export replay.", 403);
		}
		if (authentication.getAuthorities()
			.stream()
			.map(GrantedAuthority::getAuthority)
			.noneMatch("ROLE_ADMIN"::equals)) {
			throw new DatasetVersionException("DATASET_EXPORT_REPLAY_ACCESS_DENIED",
					"A project administrator membership is required for dataset export replay.", 403);
		}
		return actor;
	}

}

@RestControllerAdvice(assignableTypes = DatasetExportManualReplayController.class)
class DatasetExportManualReplayExceptionHandler {

	@ExceptionHandler(ProjectAccessException.class)
	ResponseEntity<DatasetVersionErrorResponse> projectAccess(ProjectAccessException exception) {
		return error(HttpStatus.valueOf(exception.getStatusCode()), exception.getErrorCode(), exception.getMessage(),
				java.util.Map.of());
	}

	@ExceptionHandler(DatasetVersionException.class)
	ResponseEntity<DatasetVersionErrorResponse> datasetExportReplay(DatasetVersionException exception) {
		return error(HttpStatus.valueOf(exception.getStatusCode()), exception.getErrorCode(), exception.getMessage(),
				exception.getDetails());
	}

	@ExceptionHandler({ MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
			ConstraintViolationException.class, MissingRequestHeaderException.class,
			HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class })
	ResponseEntity<DatasetVersionErrorResponse> invalidRequest(Exception exception) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_DATASET_EXPORT_REPLAY_REQUEST",
				"The dataset export replay request is invalid.", java.util.Map.of());
	}

	private static ResponseEntity<DatasetVersionErrorResponse> error(HttpStatus status, String errorCode,
			String message, java.util.Map<String, String> details) {
		return ResponseEntity.status(status)
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.cacheControl(CacheControl.noStore())
			.body(new DatasetVersionErrorResponse(errorCode, message, false, details, UUID.randomUUID().toString()));
	}

}
