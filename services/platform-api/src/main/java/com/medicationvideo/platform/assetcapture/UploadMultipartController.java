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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medicationvideo.platform.iamproject.PlatformActor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Validated
@RestController
@RequestMapping("/api/v1/upload/{batchId}/files/{clientFileId}")
class UploadMultipartController {

	private final UploadMultipartService multipartService;

	private final UploadPartAuthorizationService authorizationService;

	private final UploadPartReconciliationService reconciliationService;

	private final UploadFileCompletionService completionService;

	private final AssetCaptureProjectAuthorizer authorizer;

	UploadMultipartController(UploadMultipartService multipartService,
			UploadPartAuthorizationService authorizationService, UploadPartReconciliationService reconciliationService,
			UploadFileCompletionService completionService, AssetCaptureProjectAuthorizer authorizer) {
		this.multipartService = multipartService;
		this.authorizationService = authorizationService;
		this.reconciliationService = reconciliationService;
		this.completionService = completionService;
		this.authorizer = authorizer;
	}

	@PutMapping("/multipart")
	ResponseEntity<UploadMultipartSessionResponse> start(@PathVariable UUID batchId, @PathVariable UUID clientFileId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		var projectId = requireProject(batchId, actor);
		var file = this.multipartService.start(projectId, batchId, clientFileId);
		return ResponseEntity.status(HttpStatus.CREATED).body(UploadMultipartSessionResponse.from(file));
	}

	@PostMapping("/parts/{partNumber}/authorization")
	ResponseEntity<UploadPartAuthorizationResponse> authorizePart(@PathVariable UUID batchId,
			@PathVariable UUID clientFileId, @PathVariable @Min(1) @Max(10_000) int partNumber,
			@Valid @RequestBody UploadPartAuthorizationRequest request, Authentication authentication) {
		var actor = requireActor(authentication);
		var projectId = requireProject(batchId, actor);
		var authorized = this.authorizationService.authorize(projectId, batchId, clientFileId, partNumber, request);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(UploadPartAuthorizationResponse.from(authorized.file(), authorized.part(), authorized.uploadUrl()));
	}

	@PutMapping("/parts/{partNumber}/confirmation")
	ResponseEntity<UploadPartResponse> confirmPart(@PathVariable UUID batchId, @PathVariable UUID clientFileId,
			@PathVariable @Min(1) @Max(10_000) int partNumber, Authentication authentication) {
		var actor = requireActor(authentication);
		var projectId = requireProject(batchId, actor);
		var part = this.reconciliationService.confirm(projectId, batchId, clientFileId, partNumber);
		return ResponseEntity.ok(UploadPartResponse.from(part));
	}

	@GetMapping("/parts")
	ResponseEntity<UploadPartProgressResponse> getPartProgress(@PathVariable UUID batchId,
			@PathVariable UUID clientFileId, Authentication authentication) {
		var actor = requireActor(authentication);
		var projectId = requireProject(batchId, actor);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.reconciliationService.getProgress(projectId, batchId, clientFileId));
	}

	@PutMapping("/parts/reconciliation")
	ResponseEntity<UploadPartProgressResponse> reconcileParts(@PathVariable UUID batchId,
			@PathVariable UUID clientFileId, Authentication authentication) {
		var actor = requireActor(authentication);
		var projectId = requireProject(batchId, actor);
		return ResponseEntity.ok(this.reconciliationService.reconcile(projectId, batchId, clientFileId));
	}

	@PutMapping("/completion")
	ResponseEntity<UploadFileCompletionResponse> completeFile(@PathVariable UUID batchId,
			@PathVariable UUID clientFileId, Authentication authentication) {
		var actor = requireActor(authentication);
		var projectId = requireProject(batchId, actor);
		return ResponseEntity.ok(this.completionService.complete(projectId, batchId, clientFileId));
	}

	private static PlatformActor requireActor(Authentication authentication) {
		if (!(authentication.getPrincipal() instanceof PlatformActor actor)) {
			throw new UploadBatchAuthorizationException();
		}
		return actor;
	}

	private UUID requireProject(UUID batchId, PlatformActor actor) {
		return this.authorizer.requireBatchMember(batchId, actor.actorId()).projectId();
	}

}
