package com.medicationvideo.platform.aicandidate;

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
@RequestMapping("/api/v1/internal/ai-jobs")
class AiWorkerController {

	private final AiJobExecutionService service;

	AiWorkerController(AiJobExecutionService service) {
		this.service = service;
	}

	@PostMapping("/{jobId}/attempts/{attempt}/lease")
	ResponseEntity<AiWorkerLeaseResponse> acquire(@PathVariable UUID jobId, @PathVariable @Min(1) int attempt,
			@RequestHeader(name = "X-AI-Lease-Token") @Pattern(regexp = "^[A-Za-z0-9_-]{43,128}$") String leaseToken,
			@RequestBody @Valid AiWorkerLeaseRequest request, Authentication authentication) {
		requireWorker(authentication);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.acquire(jobId, attempt, leaseToken, request));
	}

	@PostMapping("/{jobId}/attempts/{attempt}/heartbeat")
	ResponseEntity<AiWorkerHeartbeatResponse> heartbeat(@PathVariable UUID jobId, @PathVariable @Min(1) int attempt,
			@RequestHeader(name = "X-AI-Lease-Token") @Pattern(regexp = "^[A-Za-z0-9_-]{43,128}$") String leaseToken,
			@RequestBody @Valid AiWorkerLeaseRequest request, Authentication authentication) {
		requireWorker(authentication);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.heartbeat(jobId, attempt, leaseToken, request));
	}

	@PutMapping("/{jobId}/attempts/{attempt}/result")
	ResponseEntity<Void> acceptResult(@PathVariable UUID jobId, @PathVariable @Min(1) int attempt,
			@RequestHeader(name = "X-AI-Lease-Token") @Pattern(regexp = "^[A-Za-z0-9_-]{43,128}$") String leaseToken,
			@RequestBody @Valid AiHandPoseResultRequest request, Authentication authentication) {
		requireWorker(authentication);
		this.service.acceptResult(jobId, attempt, leaseToken, request);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	private static void requireWorker(Authentication authentication) {
		if (authentication == null || authentication.getAuthorities()
			.stream()
			.noneMatch(authority -> authority.getAuthority().equals("ROLE_AI_WORKER"))) {
			throw new HandPoseJobException("AI_WORKER_ACCESS_DENIED", "An AI Worker identity is required.", 403);
		}
	}

}
