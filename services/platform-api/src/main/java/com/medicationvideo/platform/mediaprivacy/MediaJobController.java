package com.medicationvideo.platform.mediaprivacy;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medicationvideo.platform.assetcapture.UploadActor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Validated
@RestController
@RequestMapping("/api/v1")
class MediaJobController {

	private final MediaJobService service;

	MediaJobController(MediaJobService service) {
		this.service = service;
	}

	@PostMapping("/media-jobs")
	ResponseEntity<MediaJobResponse> createMediaJob(
			@RequestHeader(name = "Idempotency-Key")
			@NotBlank @Pattern(regexp = "^[A-Za-z0-9._:-]{16,128}$") String idempotencyKey,
			@RequestBody @Valid MediaJobRequest request,
			Authentication authentication) {
		var actor = requireActor(authentication);
		var job = this.service.create(actor.projectId(), idempotencyKey, request);
		var body = MediaJobResponse.from(job);
		var location = URI.create("/api/v1/media-jobs/" + job.getMediaJobId());
		return ResponseEntity.status(HttpStatus.ACCEPTED)
				.cacheControl(CacheControl.noStore())
				.location(location)
				.body(body);
	}

	@GetMapping("/media-jobs/{mediaJobId}")
	ResponseEntity<MediaJobResponse> getMediaJob(
			@PathVariable UUID mediaJobId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		var job = this.service.get(actor.projectId(), mediaJobId);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(MediaJobResponse.from(job));
	}

	private static UploadActor requireActor(Authentication authentication) {
		if (authentication == null
				|| !(authentication.getPrincipal() instanceof UploadActor actor)) {
			throw new MediaJobAuthorizationException();
		}
		return actor;
	}
}
