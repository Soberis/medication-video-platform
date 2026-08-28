package com.medicationvideo.platform.qualitycleaning;

import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Size;

@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/capture-group-quality-reviews")
class CaptureGroupQualityReviewBatchController {

	private final CaptureGroupQualityReviewService service;

	CaptureGroupQualityReviewBatchController(CaptureGroupQualityReviewService service) {
		this.service = service;
	}

	@GetMapping
	ResponseEntity<CaptureGroupQualityReviewBatchResponse> get(@PathVariable UUID projectId,
			@RequestParam(name = "capture_group_id") @Size(min = 1, max = 100) List<UUID> captureGroupIds,
			Authentication authentication) {
		var actor = CaptureGroupQualityReviewController.requireActor(authentication);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.batch(projectId, captureGroupIds, actor.actorId()));
	}

}

record CaptureGroupQualityReviewBatchResponse(List<CaptureGroupQualityReviewResponse> items) {

	CaptureGroupQualityReviewBatchResponse {
		items = List.copyOf(items);
	}

}
