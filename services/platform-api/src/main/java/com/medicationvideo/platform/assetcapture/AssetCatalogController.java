package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.medicationvideo.platform.iamproject.ProjectAccessException;
import com.medicationvideo.platform.iamproject.ProjectActor;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
class AssetCatalogController {

	private static final Set<String> HAND_STATUSES = Set.of("NOT_REQUESTED", "QUEUED", "RUNNING", "SUCCEEDED",
			"FAILED");

	private static final Set<String> HAND_RESULTS = Set.of("UNKNOWN", "HAND_DETECTED", "NO_HAND_DETECTED");

	private static final Set<String> ANNOTATION_STATUSES = Set.of("NOT_STARTED", "ASSIGNED", "SUBMITTED", "COMPLETED");

	private static final Set<String> CLEANING_STATUSES = Set.of("NOT_STARTED", "PASS", "WARNING", "REJECT");

	private final AssetCatalogQueryService service;

	private final ClipAssetActivityQueryService activityQueryService;

	private final AssetCaptureProjectAuthorizer authorizer;

	private final VideoSummaryQueryService videoSummaryQueryService;

	private final DataManagementSummaryQueryService dataManagementSummaryQueryService;

	AssetCatalogController(AssetCatalogQueryService service, ClipAssetActivityQueryService activityQueryService,
			AssetCaptureProjectAuthorizer authorizer, VideoSummaryQueryService videoSummaryQueryService,
			DataManagementSummaryQueryService dataManagementSummaryQueryService) {
		this.service = service;
		this.activityQueryService = activityQueryService;
		this.authorizer = authorizer;
		this.videoSummaryQueryService = videoSummaryQueryService;
		this.dataManagementSummaryQueryService = dataManagementSummaryQueryService;
	}

	@GetMapping("/videos")
	ResponseEntity<VideoSummaryPage> listVideos(@PathVariable UUID projectId,
			@RequestParam(required = false) UploadSourceType source,
			@RequestParam(name = "media_status", required = false) ClipAssetStatus mediaStatus,
			@RequestParam(name = "hand_status", required = false) @Pattern(regexp = "^[A-Z_]{1,32}$") String handStatus,
			@RequestParam(name = "hand_result", required = false) @Pattern(regexp = "^[A-Z_]{1,32}$") String handResult,
			@RequestParam(name = "annotation_status",
					required = false) @Pattern(regexp = "^[A-Z_]{1,32}$") String annotationStatus,
			@RequestParam(name = "cleaning_status",
					required = false) @Pattern(regexp = "^[A-Z_]{1,32}$") String cleaningStatus,
			@RequestParam(name = "camera_number", required = false) @Min(1) @Max(64) Integer cameraNumber,
			@RequestParam(name = "captured_from",
					required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant capturedFrom,
			@RequestParam(name = "captured_before",
					required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant capturedBefore,
			@RequestParam(name = "received_from",
					required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant receivedFrom,
			@RequestParam(name = "received_before",
					required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant receivedBefore,
			@RequestParam(required = false) @Size(max = 512) String cursor,
			@RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit, Authentication authentication) {
		var actor = requireActor(authentication);
		this.authorizer.requireMember(projectId, actor.actorId());
		validateVideoWorkflowFilters(handStatus, handResult, annotationStatus, cleaningStatus);
		var response = this.videoSummaryQueryService.list(projectId, source, mediaStatus, handStatus, handResult,
				annotationStatus, cleaningStatus, cameraNumber, capturedFrom, capturedBefore, receivedFrom,
				receivedBefore, cursor, limit);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}

	@GetMapping("/video-data/summary")
	ResponseEntity<DataManagementSummary> getVideoDataSummary(@PathVariable UUID projectId,
			@RequestParam(required = false) UploadSourceType source,
			@RequestParam(name = "camera_number", required = false) @Min(1) @Max(64) Integer cameraNumber,
			@RequestParam(name = "media_status", required = false) ClipAssetStatus mediaStatus,
			@RequestParam(name = "hand_status", required = false) @Pattern(regexp = "^[A-Z_]{1,32}$") String handStatus,
			@RequestParam(name = "hand_result", required = false) @Pattern(regexp = "^[A-Z_]{1,32}$") String handResult,
			@RequestParam(name = "annotation_status",
					required = false) @Pattern(regexp = "^[A-Z_]{1,32}$") String annotationStatus,
			@RequestParam(name = "cleaning_status",
					required = false) @Pattern(regexp = "^[A-Z_]{1,32}$") String cleaningStatus,
			@RequestParam(name = "captured_from",
					required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant capturedFrom,
			@RequestParam(name = "captured_before",
					required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant capturedBefore,
			@RequestParam(name = "received_from",
					required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant receivedFrom,
			@RequestParam(name = "received_before",
					required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant receivedBefore,
			Authentication authentication) {
		var actor = requireActor(authentication);
		this.authorizer.requireMember(projectId, actor.actorId());
		validateVideoWorkflowFilters(handStatus, handResult, annotationStatus, cleaningStatus);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.dataManagementSummaryQueryService.get(projectId, source, mediaStatus, handStatus, handResult,
					annotationStatus, cleaningStatus, cameraNumber, capturedFrom, capturedBefore, receivedFrom,
					receivedBefore));
	}

	private static void validateVideoWorkflowFilters(String handStatus, String handResult, String annotationStatus,
			String cleaningStatus) {
		validateFilter("hand_status", handStatus, HAND_STATUSES);
		validateFilter("hand_result", handResult, HAND_RESULTS);
		validateFilter("annotation_status", annotationStatus, ANNOTATION_STATUSES);
		validateFilter("cleaning_status", cleaningStatus, CLEANING_STATUSES);
	}

	private static void validateFilter(String name, String value, Set<String> allowed) {
		if (value != null && !allowed.contains(value)) {
			throw new AssetCatalogQueryException("INVALID_FILTER", name + " has an unsupported value.");
		}
	}

	@GetMapping("/clip-assets/{clipAssetId}")
	ResponseEntity<ClipAssetReadResponse> getClipAsset(@PathVariable UUID projectId, @PathVariable UUID clipAssetId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		this.authorizer.requireMember(projectId, actor.actorId());
		var response = this.service.getClipAsset(projectId, clipAssetId);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}

	@GetMapping("/clip-assets/{clipAssetId}/activity")
	ResponseEntity<ClipAssetActivityResponse> getClipAssetActivity(@PathVariable UUID projectId,
			@PathVariable UUID clipAssetId, @RequestParam(required = false) @Size(max = 512) String cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit, Authentication authentication) {
		var actor = requireActor(authentication);
		this.authorizer.requireMember(projectId, actor.actorId());
		var response = this.activityQueryService.get(projectId, clipAssetId, cursor, limit);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}

	private static ProjectActor requireActor(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof ProjectActor actor)) {
			throw new ProjectAccessException("PROJECT_ACCESS_DENIED", "An authenticated project actor is required.",
					403);
		}
		return actor;
	}

}
