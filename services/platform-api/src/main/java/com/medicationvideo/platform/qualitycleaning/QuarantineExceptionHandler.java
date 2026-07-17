package com.medicationvideo.platform.qualitycleaning;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.medicationvideo.platform.assetcapture.AssetCatalogQueryException;

@RestControllerAdvice(assignableTypes = QuarantineController.class)
class QuarantineExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(QuarantineExceptionHandler.class);

	private static final String REQUEST_ID_ATTRIBUTE = "medication.requestId";

	@ExceptionHandler({
		MethodArgumentTypeMismatchException.class,
		MissingServletRequestParameterException.class
	})
	ResponseEntity<QuarantineApiErrorResponse> invalidRequest(
			Exception exception) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid.", false);
	}

	@ExceptionHandler(QuarantineAuthorizationException.class)
	ResponseEntity<QuarantineApiErrorResponse> forbidden(
			QuarantineAuthorizationException exception) {
		return error(
				HttpStatus.FORBIDDEN,
				"PROJECT_ACCESS_DENIED",
				exception.getMessage(),
				false);
	}

	@ExceptionHandler(AssetCatalogQueryException.class)
	ResponseEntity<QuarantineApiErrorResponse> assetCatalog(
			AssetCatalogQueryException exception) {
		if ("CLIP_ASSET_NOT_FOUND".equals(exception.getErrorCode())) {
			return error(
					HttpStatus.NOT_FOUND,
					"CLIP_ASSET_NOT_FOUND",
					exception.getMessage(),
					false);
		}
		log.warn("Unhandled asset catalog error code: {}", exception.getErrorCode());
		return error(
				HttpStatus.BAD_GATEWAY,
				"ASSET_CATALOG_UNAVAILABLE",
				"The asset catalog rejected the request.",
				true);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<QuarantineApiErrorResponse> unexpected(Exception exception) {
		log.error("Unexpected quarantine query error", exception);
		return error(
				HttpStatus.INTERNAL_SERVER_ERROR,
				"INTERNAL_ERROR",
				"The quarantine service encountered an unexpected error.",
				true);
	}

	private static ResponseEntity<QuarantineApiErrorResponse> error(
			HttpStatus status, String code, String message, boolean retryable) {
		return ResponseEntity.status(status)
				.contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.cacheControl(CacheControl.noStore())
				.body(new QuarantineApiErrorResponse(
						code, message, retryable, resolveRequestId(), MDC.get("traceId")));
	}

	private static String resolveRequestId() {
		var attributes = RequestContextHolder.getRequestAttributes();
		if (attributes instanceof ServletRequestAttributes servletAttributes) {
			var request = servletAttributes.getRequest();
			var existing = (String) request.getAttribute(REQUEST_ID_ATTRIBUTE);
			if (existing != null) {
				return existing;
			}
			var requestId = Optional.ofNullable(request.getHeader("X-Request-Id"))
					.filter(value -> value.matches("^[A-Za-z0-9._:-]{1,128}$"))
					.orElseGet(() -> UUID.randomUUID().toString());
			request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
			return requestId;
		}
		return UUID.randomUUID().toString();
	}
}
