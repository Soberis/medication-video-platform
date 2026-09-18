package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medicationvideo.platform.iamproject.ProjectAccessException;
import com.medicationvideo.platform.iamproject.ProjectActor;

@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
class AssetCatalogController {

	private final AssetCatalogQueryService service;

	private final AssetCaptureProjectAuthorizer authorizer;

	AssetCatalogController(AssetCatalogQueryService service, AssetCaptureProjectAuthorizer authorizer) {
		this.service = service;
		this.authorizer = authorizer;
	}

	@GetMapping("/clip-assets/{clipAssetId}")
	ResponseEntity<ClipAssetReadResponse> getClipAsset(@PathVariable UUID projectId, @PathVariable UUID clipAssetId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		this.authorizer.requireMember(projectId, actor.actorId());
		var response = this.service.getClipAsset(projectId, clipAssetId);
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
