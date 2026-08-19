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
		var workerId = requireWorker(authentication);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.acquire(jobId, attempt, leaseToken, request, workerId));
	}

	@PostMapping("/{jobId}/attempts/{attempt}/heartbeat")
	ResponseEntity<AiWorkerHeartbeatResponse> heartbeat(@PathVariable UUID jobId, @PathVariable @Min(1) int attempt,
			@RequestHeader(name = "X-AI-Lease-Token") @Pattern(regexp = "^[A-Za-z0-9_-]{43,128}$") String leaseToken,
			@RequestBody @Valid AiWorkerLeaseRequest request, Authentication authentication) {
		var workerId = requireWorker(authentication);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.heartbeat(jobId, attempt, leaseToken, request, workerId));
	}

	@PutMapping("/{jobId}/attempts/{attempt}/result")
	ResponseEntity<Void> acceptResult(@PathVariable UUID jobId, @PathVariable @Min(1) int attempt,
			@RequestHeader(name = "X-AI-Lease-Token") @Pattern(regexp = "^[A-Za-z0-9_-]{43,128}$") String leaseToken,
			@RequestBody @Valid AiHandPoseResultRequest request, Authentication authentication) {
		var workerId = requireWorker(authentication);
		this.service.acceptResult(jobId, attempt, leaseToken, request, workerId);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	private static String requireWorker(Authentication authentication) {
		if (authentication == null || authentication.getAuthorities()
			.stream()
			.noneMatch(authority -> authority.getAuthority().equals("ROLE_AI_WORKER"))) {
			throw new HandPoseJobException("AI_WORKER_ACCESS_DENIED", "An AI Worker identity is required.", 403);
		}
		var name = authentication.getName();
		if (name == null || name.isBlank()) {
			throw new HandPoseJobException("AI_WORKER_ACCESS_DENIED", "An AI Worker identity is required.", 403);
		}
		// LocalMediaWorkerAuthenticationFilter uses a small actor record as the
		// principal; Authentication#getName renders that record as below. JWT
		// authentication uses the subject directly, so preserve all other names.
		var prefix = "MediaWorkerActor[serviceId=";
		if (name.startsWith(prefix) && name.endsWith("]")) {
			name = name.substring(prefix.length(), name.length() - 1);
		}
		if (!name.matches("^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$")) {
			throw new HandPoseJobException("AI_WORKER_ACCESS_DENIED", "The AI Worker identity is invalid.", 403);
		}
		return name;
	}

}
