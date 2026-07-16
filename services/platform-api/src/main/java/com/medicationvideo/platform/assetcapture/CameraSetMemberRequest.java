package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

record CameraSetMemberRequest(
		@NotNull UUID cameraChannelId,
		@Min(0) @Max(63) int cameraIndex,
		@NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$") String cameraRole,
		@NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$") String mountPosition,
		boolean enabled) {
}
