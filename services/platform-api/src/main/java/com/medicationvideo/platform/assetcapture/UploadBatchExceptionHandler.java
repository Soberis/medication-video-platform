package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {
		UploadBatchController.class,
		UploadFileController.class,
		UploadMultipartController.class,
		CameraConfigurationController.class,
		AssetCatalogController.class
})
class UploadBatchExceptionHandler {

	@ExceptionHandler({
			MethodArgumentNotValidException.class,
			HandlerMethodValidationException.class,
			HttpMessageNotReadableException.class,
			MethodArgumentTypeMismatchException.class
	})
	ResponseEntity<ApiErrorResponse> invalidRequest(Exception exception) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid.");
	}

	@ExceptionHandler(UploadBatchAuthorizationException.class)
	ResponseEntity<ApiErrorResponse> forbidden(UploadBatchAuthorizationException exception) {
		return error(HttpStatus.FORBIDDEN, "PROJECT_ACCESS_DENIED", exception.getMessage());
	}

	@ExceptionHandler(UploadBatchConflictException.class)
	ResponseEntity<ApiErrorResponse> conflict(UploadBatchConflictException exception) {
		return error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", exception.getMessage());
	}

	@ExceptionHandler(UploadBatchNotFoundException.class)
	ResponseEntity<ApiErrorResponse> notFound(UploadBatchNotFoundException exception) {
		return error(HttpStatus.NOT_FOUND, "UPLOAD_BATCH_NOT_FOUND", exception.getMessage());
	}

	@ExceptionHandler(UploadFileNotFoundException.class)
	ResponseEntity<ApiErrorResponse> fileNotFound(UploadFileNotFoundException exception) {
		return error(HttpStatus.NOT_FOUND, "UPLOAD_FILE_NOT_FOUND", exception.getMessage());
	}

	@ExceptionHandler(UploadFileRegistrationException.class)
	ResponseEntity<ApiErrorResponse> fileRegistrationConflict(UploadFileRegistrationException exception) {
		return error(HttpStatus.CONFLICT, exception.getErrorCode(), exception.getMessage());
	}

	@ExceptionHandler(UploadMultipartException.class)
	ResponseEntity<ApiErrorResponse> multipartConflict(UploadMultipartException exception) {
		return error(HttpStatus.CONFLICT, exception.getErrorCode(), exception.getMessage());
	}

	@ExceptionHandler(UploadStorageUnavailableException.class)
	ResponseEntity<ApiErrorResponse> storageUnavailable(UploadStorageUnavailableException exception) {
		return error(HttpStatus.SERVICE_UNAVAILABLE, "OBJECT_STORAGE_UNAVAILABLE", exception.getMessage(), true);
	}

	@ExceptionHandler(UploadFileCompletionInProgressException.class)
	ResponseEntity<ApiErrorResponse> completionInProgress(UploadFileCompletionInProgressException exception) {
		return error(
				HttpStatus.CONFLICT,
				"UPLOAD_FILE_COMPLETION_IN_PROGRESS",
				exception.getMessage(),
				true);
	}

	@ExceptionHandler(UploadBatchCompletionException.class)
	ResponseEntity<ApiErrorResponse> batchCompletionConflict(UploadBatchCompletionException exception) {
		return error(HttpStatus.CONFLICT, exception.getErrorCode(), exception.getMessage());
	}

	@ExceptionHandler(CameraConfigurationException.class)
	ResponseEntity<ApiErrorResponse> cameraConfigurationConflict(CameraConfigurationException exception) {
		return error(HttpStatus.CONFLICT, exception.getErrorCode(), exception.getMessage());
	}

	@ExceptionHandler(CaptureSessionIngestException.class)
	ResponseEntity<ApiErrorResponse> captureSessionIngestConflict(CaptureSessionIngestException exception) {
		return error(HttpStatus.CONFLICT, exception.getErrorCode(), exception.getMessage());
	}

	@ExceptionHandler(AssetCatalogQueryException.class)
	ResponseEntity<ApiErrorResponse> assetCatalogQueryFailure(AssetCatalogQueryException exception) {
		var status = switch (exception.getErrorCode()) {
			case "CAPTURE_SESSION_NOT_FOUND", "CLIP_ASSET_NOT_FOUND" -> HttpStatus.NOT_FOUND;
			default -> HttpStatus.BAD_REQUEST;
		};
		return error(status, exception.getErrorCode(), exception.getMessage());
	}

	private static ResponseEntity<ApiErrorResponse> error(
			HttpStatus status,
			String errorCode,
			String errorMessage) {
		return error(status, errorCode, errorMessage, false);
	}

	private static ResponseEntity<ApiErrorResponse> error(
			HttpStatus status,
			String errorCode,
			String errorMessage,
			boolean retryable) {
		var body = new ApiErrorResponse(
				errorCode,
				errorMessage,
				retryable,
				UUID.randomUUID().toString(),
				null);
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
	}
}
