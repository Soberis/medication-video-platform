package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medicationvideo.platform.iamproject.ProjectActor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

@Validated
@RestController
@RequestMapping("/api/v1/internal/capture-sync-jobs")
class CaptureSyncWorkerController {

	private final CaptureSyncExecutionService service;

	CaptureSyncWorkerController(CaptureSyncExecutionService service) {
		this.service = service;
	}

	@PostMapping("/{jobId}/attempts/{attempt}/lease")
	ResponseEntity<Void> acquire(@PathVariable UUID jobId, @PathVariable @Min(1) int attempt,
			@RequestHeader(name = "X-Capture-Sync-Lease-Token") @Pattern(
					regexp = "^[A-Za-z0-9_-]{43,128}$") String leaseToken,
			@RequestBody @Valid CaptureSyncLeaseRequest request, Authentication authentication) {
		requireWorker(authentication);
		this.service.acquire(jobId, attempt, leaseToken, request);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
	}

	@PutMapping("/{jobId}/attempts/{attempt}/result")
	ResponseEntity<CaptureSyncResultAcceptedResponse> acceptResult(@PathVariable UUID jobId,
			@PathVariable @Min(1) int attempt,
			@RequestHeader(name = "X-Capture-Sync-Lease-Token") @Pattern(
					regexp = "^[A-Za-z0-9_-]{43,128}$") String leaseToken,
			@RequestBody @Valid CaptureSyncResultRequest request, Authentication authentication) {
		requireWorker(authentication);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.acceptResult(jobId, attempt, leaseToken, request));
	}

	private static void requireWorker(Authentication authentication) {
		if (authentication == null || authentication.getAuthorities()
			.stream()
			.noneMatch(authority -> authority.getAuthority().equals("ROLE_MEDIA_WORKER"))) {
			throw new CaptureSyncExecutionException("UNAUTHORIZED", "A valid Worker machine identity is required.",
					false);
		}
	}

}
