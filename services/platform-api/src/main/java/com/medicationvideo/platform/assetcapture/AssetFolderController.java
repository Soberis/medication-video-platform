package com.medicationvideo.platform.assetcapture;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medicationvideo.platform.iamproject.ProjectAccessException;
import com.medicationvideo.platform.iamproject.ProjectActor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
class AssetFolderController {

	private static final String IDEMPOTENCY_KEY_PATTERN = "^[A-Za-z0-9._:-]{16,128}$";

	private static final String REQUEST_ID_PATTERN = "^[A-Za-z0-9._:-]{1,128}$";

	private final AssetFolderService service;

	AssetFolderController(AssetFolderService service) {
		this.service = service;
	}

	@GetMapping("/asset-folders")
	ResponseEntity<AssetFolderListResponse> list(@PathVariable UUID projectId, Authentication authentication) {
		var actor = requireActor(authentication);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.list(projectId, actor.actorId()));
	}

	@PostMapping("/asset-folders")
	ResponseEntity<AssetFolderResponse> create(@PathVariable UUID projectId,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = IDEMPOTENCY_KEY_PATTERN) String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			@Valid @RequestBody AssetFolderRequest request, Authentication authentication) {
		var actor = requireActor(authentication);
		var response = this.service.create(projectId, actor.actorId(), idempotencyKey, resolveRequestId(requestId),
				MDC.get("traceId"), request);
		return ResponseEntity.status(HttpStatus.CREATED)
			.location(URI.create("/api/v1/projects/" + projectId + "/asset-folders/" + response.folderId()))
			.cacheControl(CacheControl.noStore())
			.body(response);
	}

	@PutMapping("/asset-folders/{folderId}")
	ResponseEntity<AssetFolderResponse> rename(@PathVariable UUID projectId, @PathVariable UUID folderId,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = IDEMPOTENCY_KEY_PATTERN) String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			@Valid @RequestBody AssetFolderRequest request, Authentication authentication) {
		var actor = requireActor(authentication);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.rename(projectId, folderId, actor.actorId(), idempotencyKey, resolveRequestId(requestId),
					MDC.get("traceId"), request));
	}

	@DeleteMapping("/asset-folders/{folderId}")
	ResponseEntity<Void> delete(@PathVariable UUID projectId, @PathVariable UUID folderId,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = IDEMPOTENCY_KEY_PATTERN) String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		this.service.delete(projectId, folderId, actor.actorId(), idempotencyKey, resolveRequestId(requestId),
				MDC.get("traceId"));
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	@PutMapping("/clip-assets/{clipAssetId}/folder")
	ResponseEntity<ClipAssetFolderResponse> assign(@PathVariable UUID projectId, @PathVariable UUID clipAssetId,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = IDEMPOTENCY_KEY_PATTERN) String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			@Valid @RequestBody ClipAssetFolderRequest request, Authentication authentication) {
		var actor = requireActor(authentication);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.assign(projectId, clipAssetId, actor.actorId(), idempotencyKey,
					resolveRequestId(requestId), MDC.get("traceId"), request));
	}

	@DeleteMapping("/clip-assets/{clipAssetId}/folder")
	ResponseEntity<ClipAssetFolderResponse> unclassify(@PathVariable UUID projectId, @PathVariable UUID clipAssetId,
			@RequestHeader(name = "Idempotency-Key") @NotBlank @Pattern(
					regexp = IDEMPOTENCY_KEY_PATTERN) String idempotencyKey,
			@RequestHeader(name = "X-Request-Id",
					required = false) @Pattern(regexp = REQUEST_ID_PATTERN) String requestId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.unclassify(projectId, clipAssetId, actor.actorId(), idempotencyKey,
					resolveRequestId(requestId), MDC.get("traceId")));
	}

	private static ProjectActor requireActor(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof ProjectActor actor)) {
			throw new ProjectAccessException("PROJECT_ACCESS_DENIED", "An authenticated project actor is required.",
					403);
		}
		return actor;
	}

	private static String resolveRequestId(String requestId) {
		return Optional.ofNullable(requestId).orElseGet(() -> UUID.randomUUID().toString());
	}

}
