package com.medicationvideo.platform.datasetexport;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.medicationvideo.platform.assetcapture.AssetCatalogQueryException;
import com.medicationvideo.platform.iamproject.ProjectAccessException;

@RestControllerAdvice(assignableTypes = HandLandmarkTrainingExportController.class)
class TrainingDataExportExceptionHandler {

	@ExceptionHandler(ProjectAccessException.class)
	ResponseEntity<TrainingDataExportErrorResponse> projectAccess(ProjectAccessException exception) {
		return error(HttpStatus.valueOf(exception.getStatusCode()), exception.getErrorCode(), exception.getMessage(),
				false);
	}

	@ExceptionHandler(TrainingDataExportException.class)
	ResponseEntity<TrainingDataExportErrorResponse> export(TrainingDataExportException exception) {
		return error(HttpStatus.valueOf(exception.getStatusCode()), exception.getErrorCode(), exception.getMessage(),
				exception.getStatusCode() >= 500);
	}

	@ExceptionHandler(AssetCatalogQueryException.class)
	ResponseEntity<TrainingDataExportErrorResponse> captureGroup(AssetCatalogQueryException exception) {
		return error(HttpStatus.NOT_FOUND, exception.getErrorCode(), exception.getMessage(), false);
	}

	private static ResponseEntity<TrainingDataExportErrorResponse> error(HttpStatus status, String errorCode,
			String message, boolean retryable) {
		return ResponseEntity.status(status)
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.cacheControl(CacheControl.noStore())
			.body(new TrainingDataExportErrorResponse(errorCode, message, retryable, UUID.randomUUID().toString()));
	}

}

record TrainingDataExportErrorResponse(String errorCode, String errorMessage, boolean retryable, String requestId) {
}
