package com.medicationvideo.platform.aicandidate;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.slf4j.MDC;
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

import com.medicationvideo.platform.iamproject.PlatformActor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Validated
@RestController
@RequestMapping("/api/v1")
class AiEvaluationController {

	private final AiEvaluationService service;

	AiEvaluationController(AiEvaluationService service) {
		this.service = service;
	}

	@PostMapping("/projects/{projectId}/ai/evaluations")
	ResponseEntity<AiEvaluationResponse> create(@PathVariable UUID projectId,
			@RequestBody @Valid AiEvaluationCreateRequest request,
			@RequestHeader(name = "X-Request-Id", required = false) String requestId, Authentication authentication) {
		var actor = requireActor(authentication);
		var resolvedRequestId = requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
		var response = this.service.create(projectId, request, actor.actorId(), resolvedRequestId, MDC.get("traceId"));
		return ResponseEntity
			.created(URI.create("/api/v1/projects/" + projectId + "/ai/evaluations/" + response.evaluationId()))
			.cacheControl(CacheControl.noStore())
			.body(response);
	}

	@GetMapping("/projects/{projectId}/ai/evaluations/{evaluationId}")
	ResponseEntity<AiEvaluationResponse> get(@PathVariable UUID projectId, @PathVariable UUID evaluationId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.get(projectId, evaluationId, actor.actorId()));
	}

	private static PlatformActor requireActor(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof PlatformActor actor)) {
			throw new HandPoseJobException("PROJECT_ACCESS_DENIED", "Authentication is required.", 403);
		}
		return actor;
	}

}

record AiEvaluationCreateRequest(
		@Size(max = 128) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$") String sampleVersion,
		@Pattern(regexp = "^[a-f0-9]{64}$") String sampleSha256,
		@NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String modelSha256,
		@NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String profileSha256,
		@NotBlank @Size(max = 2000) @Pattern(regexp = "^[^\\p{Cc}]*$") String parameterAdjustmentNote,
		@NotNull @NotEmpty @Size(min = 7, max = 7) List<@NotNull @Valid AiEvaluationMetricRequest> metrics) {

	@AssertTrue(message = "sample_version or sample_sha256 must be provided.")
	boolean hasSampleBinding() {
		return (this.sampleVersion != null && !this.sampleVersion.isBlank())
				|| (this.sampleSha256 != null && !this.sampleSha256.isBlank());
	}

}

record AiEvaluationMetricRequest(@NotNull AiEvaluationMetricKey metricKey, @NotNull AiEvaluationMetricStatus status,
		Double value) {

	@AssertTrue(
			message = "PENDING metrics must have a null value; RECORDED metrics require a finite non-negative value.")
	boolean validValue() {
		if (this.status == AiEvaluationMetricStatus.PENDING) {
			return this.value == null;
		}
		if (this.value == null || !Double.isFinite(this.value) || this.value < 0) {
			return false;
		}
		return !isRate(this.metricKey) || this.value <= 1;
	}

	private static boolean isRate(AiEvaluationMetricKey key) {
		return key == AiEvaluationMetricKey.HAND_FRAME_RECALL
				|| key == AiEvaluationMetricKey.NO_HAND_FRAME_FALSE_POSITIVE_RATE
				|| key == AiEvaluationMetricKey.LANDMARK_21_VALID_RATE
				|| key == AiEvaluationMetricKey.HANDEDNESS_ACCURACY
				|| key == AiEvaluationMetricKey.FAST_MOTION_OCCLUSION_USEFULNESS;
	}

}

record AiEvaluationResponse(UUID evaluationId, UUID projectId, String sampleVersion, String sampleSha256,
		String modelSha256, String profileSha256, String status, String parameterAdjustmentNote,
		List<AiEvaluationMetricResponse> metrics, String createdBy, java.time.Instant createdAt, String schemaVersion) {

	AiEvaluationResponse {
		metrics = List.copyOf(metrics);
	}
}

record AiEvaluationMetricResponse(AiEvaluationMetricKey metricKey, AiEvaluationMetricStatus status, Double value) {
}
