package com.medicationvideo.platform.qualitycleaning;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.medicationvideo.platform.assetcapture.UploadActor;

@RestController
@RequestMapping("/api/v1")
class QuarantineController {

	private final QuarantineQueryService service;

	QuarantineController(QuarantineQueryService service) {
		this.service = service;
	}

	@GetMapping("/quarantines")
	ResponseEntity<QuarantineStatusResponse> status(
			@RequestParam("clip_asset_id") UUID clipAssetId,
			Authentication authentication) {
		var actor = requireAdministrator(authentication);
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(this.service.status(actor.projectId(), clipAssetId));
	}

	private static UploadActor requireAdministrator(Authentication authentication) {
		if (authentication == null
				|| !(authentication.getPrincipal() instanceof UploadActor actor)
				|| authentication.getAuthorities().stream()
						.noneMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))) {
			throw new QuarantineAuthorizationException();
		}
		return actor;
	}
}
