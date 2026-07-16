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

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Validated
@RestController
@RequestMapping("/api/v1")
class AssetCatalogController {

	private final AssetCatalogQueryService service;

	AssetCatalogController(AssetCatalogQueryService service) {
		this.service = service;
	}

	@GetMapping("/capture-sessions")
	ResponseEntity<CaptureSessionListResponse> listCaptureSessions(
			@RequestParam(name = "device_id", required = false)
			@Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$") String deviceId,
			@RequestParam(name = "assembly_status", required = false)
			CaptureSessionAssemblyStatus assemblyStatus,
			@RequestParam(name = "quality_status", required = false)
			CaptureQualityStatus qualityStatus,
			@RequestParam(name = "recorded_from", required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant recordedFrom,
			@RequestParam(name = "recorded_before", required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant recordedBefore,
			@RequestParam(required = false) @Size(max = 512) String cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
			Authentication authentication) {
		var actor = requireActor(authentication);
		var response = this.service.listCaptureSessions(
				actor.projectId(),
				deviceId,
				assemblyStatus,
				qualityStatus,
				recordedFrom,
				recordedBefore,
				cursor,
				limit);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}

	@GetMapping("/capture-sessions/{captureSessionId}")
	ResponseEntity<CaptureSessionResponse> getCaptureSession(
			@PathVariable UUID captureSessionId,
			Authentication authentication) {
		var response = this.service.getCaptureSession(requireActor(authentication).projectId(), captureSessionId);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}

	@GetMapping("/clip-assets")
	ResponseEntity<ClipAssetListResponse> listClipAssets(
			@RequestParam(name = "capture_session_id", required = false) UUID captureSessionId,
			@RequestParam(name = "batch_id", required = false) UUID batchId,
			@RequestParam(name = "device_id", required = false)
			@Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$") String deviceId,
			@RequestParam(name = "camera_role", required = false)
			@Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$") String cameraRole,
			@RequestParam(required = false) ClipAssetStatus status,
			@RequestParam(name = "recorded_from", required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant recordedFrom,
			@RequestParam(name = "recorded_before", required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant recordedBefore,
			@RequestParam(required = false) @Size(max = 512) String cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
			Authentication authentication) {
		var actor = requireActor(authentication);
		var response = this.service.listClipAssets(
				actor.projectId(),
				captureSessionId,
				batchId,
				deviceId,
				cameraRole,
				status,
				recordedFrom,
				recordedBefore,
				cursor,
				limit);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}

	@GetMapping("/clip-assets/{clipAssetId}")
	ResponseEntity<ClipAssetReadResponse> getClipAsset(
			@PathVariable UUID clipAssetId,
			Authentication authentication) {
		var response = this.service.getClipAsset(requireActor(authentication).projectId(), clipAssetId);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}

	private static UploadActor requireActor(Authentication authentication) {
		if (!(authentication.getPrincipal() instanceof UploadActor actor)) {
			throw new UploadBatchAuthorizationException();
		}
		return actor;
	}
}
