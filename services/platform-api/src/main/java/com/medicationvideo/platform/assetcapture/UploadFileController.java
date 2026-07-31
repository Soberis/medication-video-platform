package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medicationvideo.platform.iamproject.PlatformActor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Validated
@RestController
@RequestMapping("/api/v1/upload/{batchId}/files")
class UploadFileController {

	private final UploadFileService service;

	private final AssetCaptureProjectAuthorizer authorizer;

	UploadFileController(UploadFileService service, AssetCaptureProjectAuthorizer authorizer) {
		this.service = service;
		this.authorizer = authorizer;
	}

	@PutMapping("/{clientFileId}")
	ResponseEntity<UploadFileResponse> register(@PathVariable UUID batchId, @PathVariable UUID clientFileId,
			@RequestHeader("Idempotency-Key") @Size(min = 16,
					max = 128) @Pattern(regexp = "^[A-Za-z0-9._:-]+$") String idempotencyKey,
			@Valid @RequestBody UploadFileRegistrationRequest request, Authentication authentication) {
		if (!(authentication.getPrincipal() instanceof PlatformActor actor)) {
			throw new UploadBatchAuthorizationException();
		}
		var access = this.authorizer.requireBatchMember(batchId, actor.actorId());
		var file = this.service.register(access.projectId(), batchId, clientFileId, idempotencyKey, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(UploadFileResponse.from(file));
	}

}
