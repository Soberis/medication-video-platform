package com.medicationvideo.platform.aicandidate;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import com.medicationvideo.platform.assetcapture.AssetCatalogQueryException;
import com.medicationvideo.platform.iamproject.ProjectAccessException;

@RestControllerAdvice(assignableTypes = { HandPoseJobController.class, AiWorkerController.class,
		ActionCandidateQueryController.class })
class HandPoseJobExceptionHandler {

	@ExceptionHandler({ MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
			IllegalArgumentException.class })
	ResponseEntity<HandPoseApiErrorResponse> invalidRequest(Exception exception) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid.");
	}

	@ExceptionHandler(HandPoseJobException.class)
	ResponseEntity<HandPoseApiErrorResponse> handPose(HandPoseJobException exception) {
		return error(HttpStatus.valueOf(exception.getStatusCode()), exception.getErrorCode(), exception.getMessage());
	}

	@ExceptionHandler(ProjectAccessException.class)
	ResponseEntity<HandPoseApiErrorResponse> project(ProjectAccessException exception) {
		return error(HttpStatus.valueOf(exception.getStatusCode()), exception.getErrorCode(), exception.getMessage());
	}

	@ExceptionHandler(AssetCatalogQueryException.class)
	ResponseEntity<HandPoseApiErrorResponse> asset(AssetCatalogQueryException exception) {
		return error(HttpStatus.NOT_FOUND, "CLIP_ASSET_NOT_FOUND", "The clip asset was not found.");
	}

	private static ResponseEntity<HandPoseApiErrorResponse> error(HttpStatus status, String errorCode, String message) {
		return ResponseEntity.status(status)
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.cacheControl(CacheControl.noStore())
			.body(new HandPoseApiErrorResponse(errorCode, message, false, UUID.randomUUID().toString(), null));
	}

}

record HandPoseApiErrorResponse(String errorCode, String errorMessage, boolean retryable, String requestId,
		String traceId) {
}
