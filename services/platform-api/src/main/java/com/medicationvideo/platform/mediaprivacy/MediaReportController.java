package com.medicationvideo.platform.mediaprivacy;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medicationvideo.platform.iamproject.PlatformActor;

@RestController
@RequestMapping("/api/v1/media-jobs")
class MediaReportController {

	private final MediaJobExecutionService service;

	MediaReportController(MediaJobExecutionService service) {
		this.service = service;
	}

	@GetMapping("/{mediaJobId}/report")
	ResponseEntity<MediaReportResponse> getReport(
			@PathVariable UUID mediaJobId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(MediaReportResponse.from(this.service.getReport(actor.projectId(), mediaJobId)));
	}

	private static PlatformActor requireActor(Authentication authentication) {
		if (authentication == null
				|| !(authentication.getPrincipal() instanceof PlatformActor actor)) {
			throw new MediaJobAuthorizationException();
		}
		return actor;
	}
}
