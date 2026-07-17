package com.medicationvideo.platform.iamproject;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice(assignableTypes = ProjectController.class)
class ProjectExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ProjectExceptionHandler.class);
	private static final String REQUEST_ID_ATTRIBUTE = "medication.requestId";

	@ExceptionHandler({
		MethodArgumentNotValidException.class,
		HandlerMethodValidationException.class,
		MissingRequestHeaderException.class,
		HttpMessageNotReadableException.class,
		IllegalArgumentException.class
	})
	ResponseEntity<ProjectApiErrorResponse> invalidRequest(Exception exception) {
		return error(
				HttpStatus.BAD_REQUEST,
				"INVALID_REQUEST",
				"The project request is invalid.",
				false);
	}

	@ExceptionHandler(ProjectApiException.class)
	ResponseEntity<ProjectApiErrorResponse> projectError(ProjectApiException exception) {
		return error(
				HttpStatus.valueOf(exception.getStatusCode()),
				exception.getErrorCode(),
				exception.getMessage(),
				false);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProjectApiErrorResponse> unexpected(Exception exception) {
		log.error("Unexpected project service error", exception);
		return error(
				HttpStatus.INTERNAL_SERVER_ERROR,
				"INTERNAL_ERROR",
				"The project service encountered an unexpected error.",
				true);
	}

	private static ResponseEntity<ProjectApiErrorResponse> error(
			HttpStatus status,
			String errorCode,
			String errorMessage,
			boolean retryable) {
		return ResponseEntity.status(status)
				.contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.cacheControl(CacheControl.noStore())
				.body(new ProjectApiErrorResponse(
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
