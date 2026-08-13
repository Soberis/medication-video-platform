package com.medicationvideo.platform.assetcapture;

import java.util.List;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.medicationvideo.platform.iamproject.ProjectActor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/capture-sessions/{captureSessionId}")
class CaptureSyncController {

	private final CaptureSyncCommandService commandService;

	private final CaptureSyncQueryService queryService;

	private final AssetCaptureProjectAuthorizer authorizer;

	CaptureSyncController(CaptureSyncCommandService commandService, CaptureSyncQueryService queryService,
			AssetCaptureProjectAuthorizer authorizer) {
		this.commandService = commandService;
		this.queryService = queryService;
		this.authorizer = authorizer;
	}

	@PostMapping("/synchronizations")
	ResponseEntity<CaptureSyncJobResponse> startSynchronization(@PathVariable UUID projectId,
			@PathVariable UUID captureSessionId,
			@RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
			@RequestBody @Valid CaptureSyncStartRequest request, Authentication authentication) {
		var actor = requireActor(authentication);
		this.authorizer.requireAdministrator(projectId, actor.actorId());
		var response = this.commandService.startSynchronization(projectId, captureSessionId, request.syncProfileKey(),
				idempotencyKey, actor.actorId());
		return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(response);
	}

	@GetMapping("/synchronizations/{syncJobId}")
	ResponseEntity<CaptureSyncJobResponse> getSynchronization(@PathVariable UUID projectId,
			@PathVariable UUID captureSessionId, @PathVariable UUID syncJobId, Authentication authentication) {
		var actor = requireActor(authentication);
		this.authorizer.requireMember(projectId, actor.actorId());
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.commandService.getJob(projectId, captureSessionId, syncJobId));
	}

	@GetMapping("/timeline")
	ResponseEntity<CaptureSyncTimelineResponse> getTimeline(@PathVariable UUID projectId,
			@PathVariable UUID captureSessionId, Authentication authentication) {
		var actor = requireActor(authentication);
		this.authorizer.requireMember(projectId, actor.actorId());
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.queryService.getTimeline(projectId, captureSessionId));
	}

	@GetMapping("/frames")
	ResponseEntity<CaptureSyncFramePageResponse> listFrames(@PathVariable UUID projectId,
			@PathVariable UUID captureSessionId,
			@RequestParam(name = "sync_revision_id", required = false) UUID syncRevisionId,
			@RequestParam(name = "from_sync_frame_index", required = false) Long fromSyncFrameIndex,
			@RequestParam(name = "camera_number", required = false) List<@Min(1) @Max(64) Integer> cameraNumbers,
			@RequestParam(required = false) @Size(max = 512) String cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit, Authentication authentication) {
		var actor = requireActor(authentication);
		this.authorizer.requireMember(projectId, actor.actorId());
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.queryService.listFrames(projectId, captureSessionId, syncRevisionId, fromSyncFrameIndex,
					cameraNumbers, cursor, limit));
	}

	private static ProjectActor requireActor(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof ProjectActor actor)) {
			throw new AssetCatalogQueryException("UNAUTHORIZED", "Authentication is required.");
		}
		return actor;
	}

}
