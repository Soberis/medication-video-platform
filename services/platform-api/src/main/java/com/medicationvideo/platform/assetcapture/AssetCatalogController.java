package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
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

	private final AssetCatalogQueryService service;

	private final ClipAssetActivityQueryService activityQueryService;

	private final AssetCaptureProjectAuthorizer authorizer;

	AssetCatalogController(AssetCatalogQueryService service, ClipAssetActivityQueryService activityQueryService,
			AssetCaptureProjectAuthorizer authorizer) {
		this.service = service;
		this.activityQueryService = activityQueryService;
		this.authorizer = authorizer;
	}

	@GetMapping("/capture-sessions")
	ResponseEntity<CaptureSessionListResponse> listCaptureSessions(@PathVariable UUID projectId,
			@RequestParam(name = "device_id",
					required = false) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$") String deviceId,
			@RequestParam(name = "assembly_status", required = false) CaptureSessionAssemblyStatus assemblyStatus,
			@RequestParam(name = "quality_status", required = false) CaptureQualityStatus qualityStatus,
			@RequestParam(name = "recorded_from",
					required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant recordedFrom,
			@RequestParam(name = "recorded_before",
					required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant recordedBefore,
			@RequestParam(required = false) @Size(max = 512) String cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit, Authentication authentication) {
		var actor = requireActor(authentication);
		this.authorizer.requireMember(projectId, actor.actorId());
		var response = this.service.listCaptureSessions(projectId, deviceId, assemblyStatus, qualityStatus,
				recordedFrom, recordedBefore, cursor, limit);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}

	@GetMapping("/capture-sessions/{captureSessionId}")
	ResponseEntity<CaptureSessionResponse> getCaptureSession(@PathVariable UUID projectId,
			@PathVariable UUID captureSessionId, Authentication authentication) {
		var actor = requireActor(authentication);
		this.authorizer.requireMember(projectId, actor.actorId());
		var response = this.service.getCaptureSession(projectId, captureSessionId);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}

	@GetMapping("/clip-assets")
	ResponseEntity<ClipAssetListResponse> listClipAssets(@PathVariable UUID projectId,
			@RequestParam(name = "capture_session_id", required = false) UUID captureSessionId,
			@RequestParam(name = "batch_id", required = false) UUID batchId,
			@RequestParam(name = "device_id",
					required = false) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$") String deviceId,
			@RequestParam(name = "camera_role",
					required = false) @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$") String cameraRole,
			@RequestParam(required = false) ClipAssetStatus status,
			@RequestParam(name = "folder_id", required = false) UUID folderId,
			@RequestParam(defaultValue = "false") boolean unclassified,
			@RequestParam(name = "recorded_from",
					required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant recordedFrom,
			@RequestParam(name = "recorded_before",
					required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant recordedBefore,
			@RequestParam(required = false) @Size(max = 512) String cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit, Authentication authentication) {
		var actor = requireActor(authentication);
		this.authorizer.requireMember(projectId, actor.actorId());
		var response = this.service.listClipAssets(projectId, captureSessionId, batchId, deviceId, cameraRole, status,
				folderId, unclassified, recordedFrom, recordedBefore, cursor, limit);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
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

	@GetMapping("/capture-event-logs")
	ResponseEntity<CaptureEventLogListResponse> listCaptureEventLogs(@PathVariable UUID projectId,
			@RequestParam(name = "batch_id", required = false) UUID batchId,
			@RequestParam(name = "device_id",
					required = false) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$") String deviceId,
			@RequestParam(name = "indexed_from",
					required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant indexedFrom,
			@RequestParam(name = "indexed_before",
					required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant indexedBefore,
			@RequestParam(required = false) @Size(max = 512) String cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit, Authentication authentication) {
		var actor = requireActor(authentication);
		this.authorizer.requireMember(projectId, actor.actorId());
		var response = this.service.listCaptureEventLogs(projectId, batchId, deviceId, indexedFrom, indexedBefore,
				cursor, limit);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}

	@GetMapping("/capture-event-logs/{captureEventLogId}")
	ResponseEntity<CaptureEventLogReadResponse> getCaptureEventLog(@PathVariable UUID projectId,
			@PathVariable UUID captureEventLogId, Authentication authentication) {
		var actor = requireActor(authentication);
		this.authorizer.requireMember(projectId, actor.actorId());
		var response = this.service.getCaptureEventLog(projectId, captureEventLogId);
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
