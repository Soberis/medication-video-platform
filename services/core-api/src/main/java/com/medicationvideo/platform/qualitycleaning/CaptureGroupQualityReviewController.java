package com.medicationvideo.platform.qualitycleaning;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medicationvideo.platform.iamproject.ProjectActor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/capture-groups/{captureGroupId}/quality-review")
class CaptureGroupQualityReviewController {

	private static final String IDEMPOTENCY_KEY_PATTERN = "^[A-Za-z0-9._:-]{16,128}$";

	private static final String REQUEST_ID_PATTERN = "^[A-Za-z0-9._:-]{1,128}$";

	private final CaptureGroupQualityReviewService service;

	CaptureGroupQualityReviewController(CaptureGroupQualityReviewService service) {
		this.service = service;
	}

	@GetMapping
	ResponseEntity<CaptureGroupQualityReviewResponse> get(@PathVariable UUID projectId,
			@PathVariable UUID captureGroupId, Authentication authentication) {
		var actor = requireActor(authentication);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.get(projectId, captureGroupId, actor.actorId()));
	}

	@GetMapping("/history")
	ResponseEntity<CaptureGroupQualityReviewHistoryResponse> history(@PathVariable UUID projectId,
			@PathVariable UUID captureGroupId, Authentication authentication) {
		var actor = requireActor(authentication);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.history(projectId, captureGroupId, actor.actorId()));
	}

	@PutMapping
	ResponseEntity<CaptureGroupQualityReviewResponse> record(@PathVariable UUID projectId,
			@PathVariable UUID captureGroupId,
			@RequestHeader(name = "Idempotency-Key",
					required = false) @Pattern(regexp = IDEMPOTENCY_KEY_PATTERN) String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			@Valid @RequestBody CaptureGroupQualityReviewRequest request, Authentication authentication) {
		var actor = requireActor(authentication);
		var response = this.service.record(projectId, captureGroupId, actor.actorId(),
				Optional.ofNullable(idempotencyKey).orElseGet(() -> UUID.randomUUID().toString()),
				Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString()), MDC.get("traceId"),
				new CaptureGroupQualityReviewService.CaptureGroupQualityReviewCommand(request.result(),
						request.reason(), request.expectedRevisionValue()));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}

	private static ProjectActor requireActor(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof ProjectActor actor)) {
			throw new QualityReviewException("CAPTURE_GROUP_QUALITY_ACCESS_DENIED",
					"An authenticated platform account is required for group quality review.", 403);
		}
		return actor;
	}

}

record CaptureGroupQualityReviewRequest(@NotNull CaptureGroupQualityResult result,
		@NotBlank @Size(max = 1000) @Pattern(regexp = "^[^\\p{Cc}]+$") String reason,
		@PositiveOrZero Long expectedRevision, @PositiveOrZero Long expectedVersion) {

	long expectedRevisionValue() {
		if (this.expectedRevision != null) {
			return this.expectedRevision;
		}
		return this.expectedVersion == null ? 0 : this.expectedVersion;
	}
}

record CaptureGroupQualityReviewResponse(UUID captureGroupQualityReviewId, UUID projectId, UUID captureGroupId,
		String result, String reason, String reviewerActorId, long revision, long version, Instant decidedAt,
		Map<String, Object> confirmedViewSet, String inputVersion, String confirmedViewSetSha256) {

	static CaptureGroupQualityReviewResponse from(CaptureGroupQualityReview review) {
		return new CaptureGroupQualityReviewResponse(review.getCaptureGroupQualityReviewId(), review.getProjectId(),
				review.getCaptureGroupId(), review.getResult().name(), review.getReason(), review.getReviewerActorId(),
				review.getRevision(), review.getRevision(), review.getDecidedAt(), review.getConfirmedViewSet(),
				review.getInputVersion(), review.getConfirmedViewSetSha256());
	}

	static CaptureGroupQualityReviewResponse unreviewed(UUID projectId, UUID captureGroupId) {
		return new CaptureGroupQualityReviewResponse(null, projectId, captureGroupId, "UNREVIEWED", null, null, 0, 0,
				null, null, null, null);
	}
}

record CaptureGroupQualityReviewHistoryResponse(List<CaptureGroupQualityReviewResponse> items) {

	CaptureGroupQualityReviewHistoryResponse {
		items = List.copyOf(items);
	}
}
