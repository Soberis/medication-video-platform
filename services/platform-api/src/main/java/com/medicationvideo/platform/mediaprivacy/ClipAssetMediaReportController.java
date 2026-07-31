package com.medicationvideo.platform.mediaprivacy;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medicationvideo.platform.assetcapture.AssetCatalogQueryService;
import com.medicationvideo.platform.iamproject.PlatformActor;
import com.medicationvideo.platform.iamproject.ProjectAccessService;

/**
 * Project-scoped read boundary for the media facts visible from a ClipAsset detail page.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/clip-assets/{clipAssetId}/media-report")
class ClipAssetMediaReportController {

	private final MediaJobExecutionService service;

	private final ProjectAccessService projectAccessService;

	private final AssetCatalogQueryService assetCatalogQueryService;

	ClipAssetMediaReportController(MediaJobExecutionService service, ProjectAccessService projectAccessService,
			AssetCatalogQueryService assetCatalogQueryService) {
		this.service = service;
		this.projectAccessService = projectAccessService;
		this.assetCatalogQueryService = assetCatalogQueryService;
	}

	@GetMapping
	ResponseEntity<MediaReportResponse> getReport(@PathVariable UUID projectId, @PathVariable UUID clipAssetId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		this.projectAccessService.requireMember(projectId, actor.actorId());
		this.assetCatalogQueryService.getClipAssetInputSummary(projectId, clipAssetId);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(MediaReportResponse.from(this.service.getReportForClipAsset(projectId, clipAssetId)));
	}

	private static PlatformActor requireActor(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof PlatformActor actor)) {
			throw new MediaJobAuthorizationException();
		}
		return actor;
	}

}
