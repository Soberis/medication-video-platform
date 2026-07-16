package com.medicationvideo.platform.mediaprivacy;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import com.medicationvideo.platform.assetcapture.AssetCatalogQueryException;

@RestControllerAdvice(assignableTypes = MediaJobController.class)
class MediaJobExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(MediaJobExceptionHandler.class);

	@ExceptionHandler({
			MethodArgumentNotValidException.class,
			HandlerMethodValidationException.class,
			MissingRequestHeaderException.class,
			IllegalArgumentException.class
	})
	ResponseEntity<ApiErrorResponse> invalidRequest(Exception exception) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid.", false);
	}

	@ExceptionHandler(MediaJobAuthorizationException.class)
	ResponseEntity<ApiErrorResponse> forbidden(MediaJobAuthorizationException exception) {
		return error(HttpStatus.FORBIDDEN, "PROJECT_ACCESS_DENIED", exception.getMessage(), false);
	}

	@ExceptionHandler(MediaJobIdempotencyConflictException.class)
	ResponseEntity<ApiErrorResponse> idempotencyConflict(MediaJobIdempotencyConflictException exception) {
		return error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", exception.getMessage(), false);
	}

	@ExceptionHandler(MediaJobNotFoundException.class)
	ResponseEntity<ApiErrorResponse> notFound(MediaJobNotFoundException exception) {
		return error(HttpStatus.NOT_FOUND, "MEDIA_JOB_NOT_FOUND", exception.getMessage(), false);
	}

	@ExceptionHandler(ClipAssetNotProbeableException.class)
	ResponseEntity<ApiErrorResponse> notProbeable(ClipAssetNotProbeableException exception) {
		return error(HttpStatus.CONFLICT, "CLIP_ASSET_NOT_PROBEABLE", exception.getMessage(), false);
	}

	@ExceptionHandler(AssetCatalogQueryException.class)
	ResponseEntity<ApiErrorResponse> assetCatalogError(AssetCatalogQueryException exception) {
		if ("CLIP_ASSET_NOT_FOUND".equals(exception.getErrorCode())) {
			return error(HttpStatus.NOT_FOUND, "CLIP_ASSET_NOT_FOUND", exception.getMessage(), false);
		}
		log.warn("Unhandled asset catalog error code: {}", exception.getErrorCode());
		return error(HttpStatus.BAD_GATEWAY, "ASSET_CATALOG_UNAVAILABLE",
				"The asset catalog rejected the request.", true);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiErrorResponse> unexpected(Exception exception) {
		log.error("Unexpected media job error", exception);
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
				"The media job service encountered an unexpected error.", true);
	}

	private static ResponseEntity<ApiErrorResponse> error(
			HttpStatus status, String code, String message, boolean retryable) {
		return ResponseEntity.status(status)
				.contentType(MediaType.APPLICATION_JSON)
				.cacheControl(org.springframework.http.CacheControl.noStore())
				.body(new ApiErrorResponse(code, message, retryable, UUID.randomUUID().toString(), null));
	}
}
