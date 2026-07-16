package com.medicationvideo.platform.mediaprivacy;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

@Validated
@RestController
@RequestMapping("/api/v1/internal/media-jobs")
class MediaWorkerController {

	private final MediaJobExecutionService service;

	MediaWorkerController(MediaJobExecutionService service) {
		this.service = service;
	}

	@PostMapping("/{mediaJobId}/attempts/{attempt}/lease")
	ResponseEntity<MediaLeaseResponse> acquire(
			@PathVariable UUID mediaJobId,
			@PathVariable @Min(1) int attempt,
			@RequestHeader(name = "X-Media-Lease-Token")
			@Pattern(regexp = "^[A-Za-z0-9_-]{43,128}$") String leaseToken,
			@RequestBody @Valid MediaLeaseRequest request,
			Authentication authentication) {
		requireWorker(authentication);
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(this.service.acquire(mediaJobId, attempt, leaseToken, request));
	}

	@PutMapping("/{mediaJobId}/attempts/{attempt}/result")
	ResponseEntity<MediaResultAcceptedResponse> acceptResult(
			@PathVariable UUID mediaJobId,
			@PathVariable @Min(1) int attempt,
			@RequestHeader(name = "X-Media-Lease-Token")
			@Pattern(regexp = "^[A-Za-z0-9_-]{43,128}$") String leaseToken,
			@RequestBody @Valid MediaProbeResultRequest request,
			Authentication authentication) {
		requireWorker(authentication);
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(this.service.acceptResult(mediaJobId, attempt, leaseToken, request));
	}

	private static void requireWorker(Authentication authentication) {
		if (authentication == null
				|| !(authentication.getPrincipal() instanceof MediaWorkerActor)) {
			throw new MediaJobAuthorizationException();
		}
	}
}
