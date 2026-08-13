package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = { CaptureSyncWorkerController.class, CaptureSyncController.class })
class CaptureSyncExceptionHandler {

	@ExceptionHandler(CaptureSyncExecutionException.class)
	ResponseEntity<ApiErrorResponse> executionFailure(CaptureSyncExecutionException exception) {
		return ResponseEntity
			.status(exception.getErrorCode().equals("UNAUTHORIZED") ? HttpStatus.UNAUTHORIZED : HttpStatus.CONFLICT)
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.header("Cache-Control", "no-store")
			.body(new ApiErrorResponse(exception.getErrorCode(), exception.getMessage(), exception.isRetryable(),
					UUID.randomUUID().toString(), null));
	}

	@ExceptionHandler(AssetCatalogQueryException.class)
	ResponseEntity<ApiErrorResponse> queryFailure(AssetCatalogQueryException exception) {
		var status = switch (exception.getErrorCode()) {
			case "UNAUTHORIZED" -> HttpStatus.UNAUTHORIZED;
			case "PROJECT_ACCESS_DENIED" -> HttpStatus.FORBIDDEN;
			case "CAPTURE_SESSION_NOT_FOUND", "CAPTURE_SYNC_JOB_NOT_FOUND", "CAPTURE_SYNC_REVISION_NOT_FOUND" ->
				HttpStatus.NOT_FOUND;
			default -> HttpStatus.BAD_REQUEST;
		};
		return ResponseEntity.status(status)
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.header("Cache-Control", "no-store")
			.body(new ApiErrorResponse(exception.getErrorCode(), exception.getMessage(), false,
					UUID.randomUUID().toString(), null));
	}

}
