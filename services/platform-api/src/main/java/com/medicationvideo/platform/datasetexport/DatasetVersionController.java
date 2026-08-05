package com.medicationvideo.platform.datasetexport;

import java.net.URI;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.medicationvideo.platform.assetcapture.AssetCatalogQueryException;
import com.medicationvideo.platform.iamproject.ProjectAccessException;
import com.medicationvideo.platform.iamproject.ProjectActor;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/dataset-versions")
class DatasetVersionController {

	private static final String IDEMPOTENCY_KEY_PATTERN = "^[A-Za-z0-9._:-]{16,128}$";

	private static final String REQUEST_ID_PATTERN = "^[A-Za-z0-9._:-]{1,128}$";

	private final DatasetVersionService service;

	private final DatasetSplitPlanService splitPlanService;

	private final DatasetBuildSpecificationService buildSpecificationService;

	private final DatasetVersionBuildService buildService;

	private final DatasetBuildSnapshotService buildSnapshotService;

	private final DatasetVersionValidationService validationService;

	private final DatasetVersionFreezeService freezeService;

	private final DatasetExportService exportService;

	private final DatasetExportApprovalReadinessService exportApprovalReadinessService;

	private final DatasetExportApprovalCommandService exportApprovalCommandService;

	private final DatasetExportDownloadService exportDownloadService;

	private final DatasetVersionQueryService queryService;

	private final DatasetVersionCurrentAdmissionQueryService currentAdmissionQueryService;

	DatasetVersionController(DatasetVersionService service, DatasetSplitPlanService splitPlanService,
			DatasetBuildSpecificationService buildSpecificationService, DatasetVersionBuildService buildService,
			DatasetBuildSnapshotService buildSnapshotService, DatasetVersionValidationService validationService,
			DatasetVersionFreezeService freezeService, DatasetExportService exportService,
			DatasetExportApprovalReadinessService exportApprovalReadinessService,
			DatasetExportApprovalCommandService exportApprovalCommandService,
			DatasetExportDownloadService exportDownloadService, DatasetVersionQueryService queryService,
			DatasetVersionCurrentAdmissionQueryService currentAdmissionQueryService) {
		this.service = service;
		this.splitPlanService = splitPlanService;
		this.buildSpecificationService = buildSpecificationService;
		this.buildService = buildService;
		this.buildSnapshotService = buildSnapshotService;
		this.validationService = validationService;
		this.freezeService = freezeService;
		this.exportService = exportService;
		this.exportApprovalReadinessService = exportApprovalReadinessService;
		this.exportApprovalCommandService = exportApprovalCommandService;
		this.exportDownloadService = exportDownloadService;
		this.queryService = queryService;
		this.currentAdmissionQueryService = currentAdmissionQueryService;
	}

	@GetMapping
	ResponseEntity<DatasetVersionListResponse> list(@PathVariable UUID projectId,
			@RequestParam(required = false) @Size(max = 512) String cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit, Authentication authentication) {
		var response = this.queryService.list(projectId, requireActor(authentication).actorId(), cursor, limit);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}

	@GetMapping("/{datasetVersionId}")
	ResponseEntity<DatasetVersionDetailResponse> get(@PathVariable UUID projectId, @PathVariable UUID datasetVersionId,
			Authentication authentication) {
		var response = this.queryService.get(projectId, datasetVersionId, requireActor(authentication).actorId(),
				isPlatformAdministrator(authentication));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}

	@GetMapping("/{datasetVersionId}/current-admission")
	ResponseEntity<DatasetVersionCurrentAdmissionResponse> currentAdmission(@PathVariable UUID projectId,
			@PathVariable UUID datasetVersionId, Authentication authentication) {
		var response = this.currentAdmissionQueryService.get(projectId, datasetVersionId,
				requireActor(authentication).actorId());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}

	@PostMapping
	ResponseEntity<DatasetVersionResponse> createDraft(@PathVariable UUID projectId,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = IDEMPOTENCY_KEY_PATTERN) String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			@Valid @RequestBody DatasetVersionCreateRequest request, Authentication authentication) {
		var response = this.service.createDraft(projectId, requirePlatformAdministrator(authentication).actorId(),
				idempotencyKey, Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString()),
				MDC.get("traceId"),
				new DatasetVersionCreateCommand(request.displayName(), request.captureSessionIds()));
		return ResponseEntity.status(HttpStatus.CREATED)
			.location(URI.create("/api/v1/projects/" + projectId + "/dataset-versions/" + response.datasetVersionId()))
			.cacheControl(CacheControl.noStore())
			.body(response);
	}

	@PostMapping("/{datasetVersionId}/split-plans")
	ResponseEntity<DatasetSplitPlanResponse> createSplitPlan(@PathVariable UUID projectId,
			@PathVariable UUID datasetVersionId,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = IDEMPOTENCY_KEY_PATTERN) String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			@Valid @RequestBody DatasetSplitPlanCreateRequest request, Authentication authentication) {
		var response = this.splitPlanService.create(projectId, datasetVersionId,
				requirePlatformAdministrator(authentication).actorId(), idempotencyKey,
				Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString()), MDC.get("traceId"),
				request.holdoutCount());
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(response);
	}

	@PostMapping("/{datasetVersionId}/build-specifications")
	ResponseEntity<DatasetBuildSpecificationResponse> createBuildSpecification(@PathVariable UUID projectId,
			@PathVariable UUID datasetVersionId,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = IDEMPOTENCY_KEY_PATTERN) String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			Authentication authentication) {
		var response = this.buildSpecificationService.create(projectId, datasetVersionId,
				requirePlatformAdministrator(authentication).actorId(), idempotencyKey,
				Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString()), MDC.get("traceId"));
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(response);
	}

	@PostMapping("/{datasetVersionId}/build")
	ResponseEntity<DatasetVersionBuildStartResponse> startBuild(@PathVariable UUID projectId,
			@PathVariable UUID datasetVersionId,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = IDEMPOTENCY_KEY_PATTERN) String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			Authentication authentication) {
		var response = this.buildService.start(projectId, datasetVersionId,
				requirePlatformAdministrator(authentication).actorId(), idempotencyKey,
				Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString()), MDC.get("traceId"));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}

	@PostMapping("/{datasetVersionId}/build-snapshots")
	ResponseEntity<DatasetBuildSnapshotResponse> createBuildSnapshot(@PathVariable UUID projectId,
			@PathVariable UUID datasetVersionId,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = IDEMPOTENCY_KEY_PATTERN) String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			Authentication authentication) {
		var response = this.buildSnapshotService.create(projectId, datasetVersionId,
				requirePlatformAdministrator(authentication).actorId(), idempotencyKey,
				Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString()), MDC.get("traceId"));
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(response);
	}

	@PostMapping("/{datasetVersionId}/validations")
	ResponseEntity<DatasetVersionValidationResponse> validateDatasetVersion(@PathVariable UUID projectId,
			@PathVariable UUID datasetVersionId,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = IDEMPOTENCY_KEY_PATTERN) String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			Authentication authentication) {
		var response = this.validationService.validate(projectId, datasetVersionId,
				requirePlatformAdministrator(authentication).actorId(), idempotencyKey,
				Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString()), MDC.get("traceId"));
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(response);
	}

	@PostMapping("/{datasetVersionId}/freezes")
	ResponseEntity<DatasetVersionFreezeResponse> freezeDatasetVersion(@PathVariable UUID projectId,
			@PathVariable UUID datasetVersionId,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = IDEMPOTENCY_KEY_PATTERN) String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			Authentication authentication) {
		var response = this.freezeService.freeze(projectId, datasetVersionId,
				requirePlatformAdministrator(authentication).actorId(), isPlatformAdministrator(authentication),
				idempotencyKey, Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString()),
				MDC.get("traceId"));
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(response);
	}

	@PostMapping("/{datasetVersionId}/exports")
	ResponseEntity<DatasetExportResponse> requestDatasetExport(@PathVariable UUID projectId,
			@PathVariable UUID datasetVersionId,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = IDEMPOTENCY_KEY_PATTERN) String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			@Valid @RequestBody DatasetExportCreateRequest request, Authentication authentication) {
		var response = this.exportService.request(projectId, datasetVersionId,
				requirePlatformAdministrator(authentication).actorId(), idempotencyKey,
				Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString()), MDC.get("traceId"),
				request.purpose(), request.expiresAt());
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(response);
	}

	@GetMapping("/{datasetVersionId}/exports/approval-readiness")
	ResponseEntity<DatasetExportApprovalReadinessResponse> getDatasetExportApprovalReadiness(
			@PathVariable UUID projectId, @PathVariable UUID datasetVersionId, Authentication authentication) {
		var response = this.exportApprovalReadinessService.get(projectId, datasetVersionId,
				requireActor(authentication).actorId(), isPlatformAdministrator(authentication));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}

	@PostMapping("/{datasetVersionId}/exports/approvals")
	ResponseEntity<DatasetExportResponse> approveDatasetExport(@PathVariable UUID projectId,
			@PathVariable UUID datasetVersionId,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = IDEMPOTENCY_KEY_PATTERN) String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			Authentication authentication) {
		var response = this.exportApprovalCommandService.approve(projectId, datasetVersionId,
				requirePlatformAdministrator(authentication).actorId(), isPlatformAdministrator(authentication),
				idempotencyKey, Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString()),
				MDC.get("traceId"));
		return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(response);
	}

	@PostMapping("/{datasetVersionId}/exports/download-authorizations")
	ResponseEntity<DatasetExportDownloadAuthorizationResponse> authorizeDatasetExportDownload(
			@PathVariable UUID projectId, @PathVariable UUID datasetVersionId,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			Authentication authentication) {
		var response = this.exportDownloadService.authorize(projectId, datasetVersionId,
				requireActor(authentication).actorId(),
				Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString()), MDC.get("traceId"));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}

	private static ProjectActor requireActor(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof ProjectActor actor)) {
			throw new DatasetVersionException("DATASET_VERSION_ACCESS_DENIED",
					"An authenticated platform account is required for dataset versions.", 403);
		}
		return actor;
	}

	private static ProjectActor requirePlatformAdministrator(Authentication authentication) {
		var actor = requireActor(authentication);
		if (!isPlatformAdministrator(authentication)) {
			throw new ProjectAccessException("PROJECT_ACCESS_DENIED", "A platform administrator account is required.",
					403);
		}
		return actor;
	}

	private static boolean isPlatformAdministrator(Authentication authentication) {
		return authentication != null && authentication.getAuthorities()
			.stream()
			.anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
	}

}

record DatasetVersionCreateRequest(
		@NotBlank @Size(max = 128) @Pattern(regexp = "^\\S(?:.*\\S)?$",
				message = "displayName must not have leading or trailing whitespace") String displayName,
		@NotNull @NotEmpty @Size(max = 100) List<@NotNull UUID> captureSessionIds) {
}

record DatasetSplitPlanCreateRequest(@Min(1) @Max(99) int holdoutCount) {
}

record DatasetExportCreateRequest(@NotNull DatasetExportPurpose purpose, @NotNull java.time.Instant expiresAt) {
}

final class DatasetVersionException extends RuntimeException {

	private final String errorCode;

	private final int statusCode;

	private final Map<String, String> details;

	DatasetVersionException(String errorCode, String message, int statusCode) {
		this(errorCode, message, statusCode, Map.of());
	}

	DatasetVersionException(String errorCode, String message, int statusCode, Map<String, String> details) {
		super(message);
		this.errorCode = errorCode;
		this.statusCode = statusCode;
		this.details = Map.copyOf(details);
	}

	String getErrorCode() {
		return this.errorCode;
	}

	int getStatusCode() {
		return this.statusCode;
	}

	Map<String, String> getDetails() {
		return this.details;
	}

}

record DatasetVersionErrorResponse(String errorCode, String errorMessage, boolean retryable,
		Map<String, String> details, String requestId) {
}

@RestControllerAdvice(assignableTypes = DatasetVersionController.class)
class DatasetVersionExceptionHandler {

	@ExceptionHandler(ProjectAccessException.class)
	ResponseEntity<DatasetVersionErrorResponse> projectAccess(ProjectAccessException exception) {
		return error(HttpStatus.valueOf(exception.getStatusCode()), exception.getErrorCode(), exception.getMessage(),
				Map.of());
	}

	@ExceptionHandler(AssetCatalogQueryException.class)
	ResponseEntity<DatasetVersionErrorResponse> sessionNotFound(AssetCatalogQueryException exception) {
		return error(HttpStatus.NOT_FOUND, exception.getErrorCode(), exception.getMessage(), Map.of());
	}

	@ExceptionHandler(DatasetVersionException.class)
	ResponseEntity<DatasetVersionErrorResponse> datasetVersion(DatasetVersionException exception) {
		return error(HttpStatus.valueOf(exception.getStatusCode()), exception.getErrorCode(), exception.getMessage(),
				exception.getDetails());
	}

	@ExceptionHandler({ MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
			ConstraintViolationException.class, MissingRequestHeaderException.class,
			HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class })
	ResponseEntity<DatasetVersionErrorResponse> invalidRequest(Exception exception) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_DATASET_VERSION_REQUEST",
				"The dataset-version request is invalid.", Map.of());
	}

	private static ResponseEntity<DatasetVersionErrorResponse> error(HttpStatus status, String errorCode,
			String message, Map<String, String> details) {
		return ResponseEntity.status(status)
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.cacheControl(CacheControl.noStore())
			.body(new DatasetVersionErrorResponse(errorCode, message, false, details, UUID.randomUUID().toString()));
	}

}
