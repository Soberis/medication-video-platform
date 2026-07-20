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

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.medicationvideo.platform.iamproject.ProjectRole;

@Validated
@RestController
@RequestMapping("/api/v1/upload")
class UploadBatchController {

	private final UploadBatchService service;
	private final UploadProgressQueryService progressQueryService;
	private final UploadBatchCompletionService completionService;
	private final CaptureSessionIngestService captureSessionIngestService;
	private final UploadBatchTerminationService terminationService;
	private final AssetCaptureProjectAuthorizer authorizer;

	UploadBatchController(
			UploadBatchService service,
			UploadProgressQueryService progressQueryService,
			UploadBatchCompletionService completionService,
			CaptureSessionIngestService captureSessionIngestService,
			UploadBatchTerminationService terminationService,
			AssetCaptureProjectAuthorizer authorizer) {
		this.service = service;
		this.progressQueryService = progressQueryService;
		this.completionService = completionService;
		this.captureSessionIngestService = captureSessionIngestService;
		this.terminationService = terminationService;
		this.authorizer = authorizer;
	}

	@PostMapping("/init")
	ResponseEntity<UploadBatchResponse> initialize(
			@RequestHeader("Idempotency-Key")
			@Size(min = 16, max = 128)
			@Pattern(regexp = "^[A-Za-z0-9._:-]+$") String idempotencyKey,
			@Valid @RequestBody UploadBatchInitRequest request,
			Authentication authentication) {
		var actor = requireActor(authentication);
		this.authorizer.requireMember(request.projectId(), actor.actorId());
		var batch = this.service.initialize(actor, idempotencyKey, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(UploadBatchResponse.from(batch));
	}

	@PutMapping("/{batchId}/abort")
	ResponseEntity<UploadBatchTerminationResponse> abortBatch(
			@PathVariable UUID batchId,
			@RequestHeader("X-Request-Id")
			@Size(min = 1, max = 128)
			@Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]*$") String requestId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		var access = this.authorizer.requireBatchMember(batchId, actor.actorId());
		var administrator = access.role() == ProjectRole.ADMIN;
		var response = this.terminationService.abort(
				access.projectId(), actor.actorId(), administrator, batchId, requestId);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}

	@GetMapping("/{batchId}")
	ResponseEntity<UploadBatchProgressResponse> getBatch(
			@PathVariable UUID batchId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		var access = this.authorizer.requireBatchMember(batchId, actor.actorId());
		var response = this.progressQueryService.getBatch(batchId, access.projectId());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}

	@GetMapping("/{batchId}/files")
	ResponseEntity<UploadFileProgressListResponse> getFiles(
			@PathVariable UUID batchId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		var access = this.authorizer.requireBatchMember(batchId, actor.actorId());
		var response = this.progressQueryService.getFiles(batchId, access.projectId());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}

	@PutMapping("/{batchId}/completion")
	ResponseEntity<UploadBatchCompletionResponse> completeBatch(
			@PathVariable UUID batchId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		var access = this.authorizer.requireBatchMember(batchId, actor.actorId());
		var response = this.completionService.complete(access.projectId(), batchId);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
	}

	@PutMapping("/{batchId}/capture-session")
	ResponseEntity<CaptureSessionResponse> ingestCaptureSession(
			@PathVariable UUID batchId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		var access = this.authorizer.requireBatchMember(batchId, actor.actorId());
		var response = this.captureSessionIngestService.ingest(access.projectId(), batchId);
		return ResponseEntity.status(HttpStatus.CREATED)
				.cacheControl(CacheControl.noStore())
				.body(response);
	}

	private static UploadActor requireActor(Authentication authentication) {
		if (!(authentication.getPrincipal() instanceof UploadActor actor)) {
			throw new UploadBatchAuthorizationException();
		}
		return actor;
	}
}
