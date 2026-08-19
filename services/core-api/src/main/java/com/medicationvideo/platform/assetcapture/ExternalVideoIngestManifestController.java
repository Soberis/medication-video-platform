package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medicationvideo.platform.iamproject.PlatformActor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/external-video-ingest-manifests")
class ExternalVideoIngestManifestController {

	private final AssetCaptureProjectAuthorizer authorizer;

	private final ExternalVideoIngestManifestService service;

	ExternalVideoIngestManifestController(AssetCaptureProjectAuthorizer authorizer,
			ExternalVideoIngestManifestService service) {
		this.authorizer = authorizer;
		this.service = service;
	}

	@PostMapping
	ResponseEntity<ExternalVideoIngestManifestAcceptedResponse> accept(@PathVariable UUID projectId,
			@Valid @RequestBody ExternalVideoIngestManifestRequest request, Authentication authentication) {
		if (!(authentication.getPrincipal() instanceof PlatformActor actor)) {
			throw new UploadBatchAuthorizationException();
		}
		this.authorizer.requireMember(projectId, actor.actorId());
		var response = this.service.accept(projectId, request);
		return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(response);
	}

}

record ExternalVideoIngestManifestRequest(
		@NotBlank @Pattern(regexp = "^external-video-ingest-v1$") String schemaVersion,
		@NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$") String sourceSystem,
		@NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$") String sourceDeviceId,
		@Size(max = 128) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$") String sourceBatchId,
		@NotNull @Valid ExternalVideoCaptureGroup captureGroup,
		@NotNull @Size(min = 1, max = 64) List<@Valid ExternalVideoManifestVideo> videos) {

	@AssertTrue(message = "video camera numbers must be explicit, expected and unique.")
	boolean isCameraNumbersAreExplicit() {
		if (this.captureGroup == null || this.captureGroup.expectedCameraNumbers() == null || this.videos == null) {
			return false;
		}
		var expected = new HashSet<>(this.captureGroup.expectedCameraNumbers());
		var actual = new HashSet<Integer>();
		return expected.size() == this.captureGroup.expectedCameraNumbers().size() && this.videos.stream()
			.allMatch(video -> expected.contains(video.cameraNumber()) && actual.add(video.cameraNumber()));
	}
}

record ExternalVideoCaptureGroup(
		@NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$") String captureGroupKey,
		@NotNull ExternalVideoCaptureGroupKeySource keySource, @NotNull Instant capturedAt,
		@NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_+./:-]{0,63}$") String timezone,
		@NotNull @Size(min = 1, max = 64) List<@NotNull @Min(1) @Max(64) Integer> expectedCameraNumbers) {
}

enum ExternalVideoCaptureGroupKeySource {

	EXTERNAL_CAPTURE_SESSION_ID, UPSTREAM_MANIFEST_KEY

}

record ExternalVideoManifestVideo(@NotNull UUID clientFileId, @NotNull @Min(1) @Max(64) Integer cameraNumber,
		@NotBlank @Size(max = 255) String fileName, @NotNull @Min(1) Long declaredSizeBytes,
		@NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String sha256, Instant recordStartAt) {
}

record ExternalVideoIngestManifestAcceptedResponse(UUID manifestId, String status, String schemaVersion,
		String captureGroupKey, String keySource, Instant capturedAt, int expectedCameraCount, int videoCount) {
}
