package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.medicationvideo.platform.iamproject.ProjectAccessException;

@RestControllerAdvice(assignableTypes = {
		AssetFolderController.class,
		ClipAssetDeletionController.class,
		ClipAssetPlaybackController.class
})
class AssetFolderExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(AssetFolderExceptionHandler.class);
	private static final String REQUEST_ID_ATTRIBUTE = "medication.requestId";

	@ExceptionHandler({
		MethodArgumentNotValidException.class,
		HandlerMethodValidationException.class,
		MissingRequestHeaderException.class,
		HttpMessageNotReadableException.class,
		MethodArgumentTypeMismatchException.class,
		IllegalArgumentException.class
	})
	ResponseEntity<ApiErrorResponse> invalidRequest(Exception exception) {
		return error(
				HttpStatus.BAD_REQUEST,
				"INVALID_REQUEST",
				"The asset folder request is invalid.",
				false);
	}

	@ExceptionHandler(ProjectAccessException.class)
	ResponseEntity<ApiErrorResponse> projectAccess(ProjectAccessException exception) {
		return error(
				HttpStatus.valueOf(exception.getStatusCode()),
				exception.getErrorCode(),
				exception.getMessage(),
				false);
	}

	@ExceptionHandler(AssetFolderException.class)
	ResponseEntity<ApiErrorResponse> assetFolder(AssetFolderException exception) {
		return error(
				HttpStatus.valueOf(exception.getStatusCode()),
				exception.getErrorCode(),
				exception.getMessage(),
				false);
	}

	@ExceptionHandler(ClipAssetDeletionException.class)
	ResponseEntity<ApiErrorResponse> clipAssetDeletion(ClipAssetDeletionException exception) {
		return error(
				HttpStatus.valueOf(exception.getStatusCode()),
				exception.getErrorCode(),
				exception.getMessage(),
				false);
	}

	@ExceptionHandler(ClipAssetPlaybackException.class)
	ResponseEntity<ApiErrorResponse> clipAssetPlayback(ClipAssetPlaybackException exception) {
		return error(
				HttpStatus.valueOf(exception.getStatusCode()),
				exception.getErrorCode(),
				exception.getMessage(),
				exception.isRetryable());
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiErrorResponse> unexpected(Exception exception) {
		log.error("Unexpected asset capture command error", exception);
		return error(
				HttpStatus.INTERNAL_SERVER_ERROR,
				"INTERNAL_ERROR",
				"The asset capture command encountered an unexpected error.",
				true);
	}

	private static ResponseEntity<ApiErrorResponse> error(
			HttpStatus status,
			String errorCode,
			String errorMessage,
			boolean retryable) {
		return ResponseEntity.status(status)
				.contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.cacheControl(CacheControl.noStore())
				.body(new ApiErrorResponse(
						errorCode,
						errorMessage,
						retryable,
						resolveRequestId(),
						MDC.get("traceId")));
	}

	private static String resolveRequestId() {
		var attributes = RequestContextHolder.getRequestAttributes();
		if (attributes instanceof ServletRequestAttributes servletAttributes) {
			var request = servletAttributes.getRequest();
			var existing = (String) request.getAttribute(REQUEST_ID_ATTRIBUTE);
			if (existing != null) {
				return existing;
			}
			var requestId = request.getHeader("X-Request-Id");
			if (requestId == null || requestId.isBlank()) {
				requestId = UUID.randomUUID().toString();
			}
			request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
			return requestId;
		}
		return UUID.randomUUID().toString();
	}
}
