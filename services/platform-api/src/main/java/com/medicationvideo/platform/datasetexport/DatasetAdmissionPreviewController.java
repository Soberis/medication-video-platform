package com.medicationvideo.platform.datasetexport;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.medicationvideo.platform.assetcapture.AssetCatalogQueryException;
import com.medicationvideo.platform.iamproject.ProjectAccessException;
import com.medicationvideo.platform.iamproject.ProjectAccessService;
import com.medicationvideo.platform.iamproject.ProjectActor;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/capture-sessions/{captureSessionId}/dataset-admission-preview")
class DatasetAdmissionPreviewController {

    private final DatasetAdmissionPreviewService service;
    private final ProjectAccessService projectAccessService;

    DatasetAdmissionPreviewController(
            DatasetAdmissionPreviewService service,
            ProjectAccessService projectAccessService) {
        this.service = service;
        this.projectAccessService = projectAccessService;
    }

    @GetMapping
    ResponseEntity<DatasetAdmissionPreviewResponse> get(
            @PathVariable UUID projectId,
            @PathVariable UUID captureSessionId,
            Authentication authentication) {
        this.projectAccessService.requireMember(projectId, requireActor(authentication).actorId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(this.service.preview(projectId, captureSessionId));
    }

    private static ProjectActor requireActor(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof ProjectActor actor)) {
            throw new DatasetAdmissionPreviewException(
                    "DATASET_ADMISSION_PREVIEW_ACCESS_DENIED",
                    "An authenticated platform account is required for the dataset admission preview.", 403);
        }
        return actor;
    }
}

record DatasetAdmissionPreviewErrorResponse(
        String errorCode,
        String errorMessage,
        boolean retryable,
        String requestId) {
}

final class DatasetAdmissionPreviewException extends RuntimeException {

    private final String errorCode;
    private final int statusCode;

    DatasetAdmissionPreviewException(String errorCode, String message, int statusCode) {
        super(message);
        this.errorCode = errorCode;
        this.statusCode = statusCode;
    }

    String getErrorCode() {
        return this.errorCode;
    }

    int getStatusCode() {
        return this.statusCode;
    }
}

@RestControllerAdvice(assignableTypes = DatasetAdmissionPreviewController.class)
class DatasetAdmissionPreviewExceptionHandler {

    @ExceptionHandler(ProjectAccessException.class)
    ResponseEntity<DatasetAdmissionPreviewErrorResponse> projectAccess(ProjectAccessException exception) {
        return error(HttpStatus.valueOf(exception.getStatusCode()), exception.getErrorCode(), exception.getMessage());
    }

    @ExceptionHandler(AssetCatalogQueryException.class)
    ResponseEntity<DatasetAdmissionPreviewErrorResponse> sessionNotFound(AssetCatalogQueryException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getErrorCode(), exception.getMessage());
    }

    @ExceptionHandler(DatasetAdmissionPreviewException.class)
    ResponseEntity<DatasetAdmissionPreviewErrorResponse> access(DatasetAdmissionPreviewException exception) {
        return error(HttpStatus.valueOf(exception.getStatusCode()), exception.getErrorCode(), exception.getMessage());
    }

    private static ResponseEntity<DatasetAdmissionPreviewErrorResponse> error(
            HttpStatus status, String errorCode, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore())
                .body(new DatasetAdmissionPreviewErrorResponse(errorCode, message, false, UUID.randomUUID().toString()));
    }
}
