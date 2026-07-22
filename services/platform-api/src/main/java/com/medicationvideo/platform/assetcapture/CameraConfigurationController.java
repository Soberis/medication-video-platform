package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medicationvideo.platform.iamproject.PlatformActor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
class CameraConfigurationController {

	private final CameraChannelService channelService;
	private final CameraSetService cameraSetService;
	private final AssetCaptureProjectAuthorizer authorizer;

	CameraConfigurationController(
			CameraChannelService channelService,
			CameraSetService cameraSetService,
			AssetCaptureProjectAuthorizer authorizer) {
		this.channelService = channelService;
		this.cameraSetService = cameraSetService;
		this.authorizer = authorizer;
	}

	@PutMapping("/camera-channels/{provisioningKey}")
	ResponseEntity<CameraChannelResponse> provisionChannel(
			@PathVariable UUID projectId,
			@PathVariable
			@Size(min = 1, max = 128)
			@Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]*$") String provisioningKey,
			Authentication authentication) {
		requireAdministrator(authentication, projectId);
		return ResponseEntity.status(HttpStatus.CREATED)
				.cacheControl(CacheControl.noStore())
				.body(this.channelService.provision(projectId, provisioningKey));
	}

	@PutMapping("/camera-sets/{cameraSetKey}/versions/{configurationVersion}")
	ResponseEntity<CameraSetResponse> configureCameraSet(
			@PathVariable UUID projectId,
			@PathVariable
			@Size(min = 1, max = 128)
			@Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]*$") String cameraSetKey,
			@PathVariable @Min(1) @Max(Integer.MAX_VALUE) int configurationVersion,
			@Valid @RequestBody CameraSetRequest request,
			Authentication authentication) {
		requireAdministrator(authentication, projectId);
		return ResponseEntity.status(HttpStatus.CREATED)
				.cacheControl(CacheControl.noStore())
				.body(this.cameraSetService.configure(
						projectId,
						cameraSetKey,
						configurationVersion,
						request));
	}

	private void requireAdministrator(Authentication authentication, UUID projectId) {
		if (!(authentication.getPrincipal() instanceof PlatformActor actor)) {
			throw new UploadBatchAuthorizationException();
		}
		this.authorizer.requireAdministrator(projectId, actor.actorId());
	}
}
