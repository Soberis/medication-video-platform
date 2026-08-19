package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

record CaptureSessionMetadataV1(@NotBlank @Pattern(regexp = "^capture-session-metadata-v1$") String schemaVersion,
		@NotNull UUID captureSessionId,
		@NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$") String deviceId, @NotNull UUID cameraSetId,
		@NotNull @Min(1) @Max(64) Integer expectedCameraCount,
		@NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$") String cameraLayoutVersion,
		@NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$") String syncGroupId,
		@NotNull Instant deviceTimeUtc,
		@NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_+./:-]{0,63}$") String deviceTimezone,
		@NotEmpty @Size(max = 64) List<@Valid Stream> streams) {

	CaptureSessionMetadataV1 {
		streams = streams == null ? null : List.copyOf(streams);
	}

	record Stream(@NotNull UUID clientFileId, @NotNull UUID cameraChannelId,
			@NotNull @Min(0) @Max(63) Integer cameraIndex, @NotNull Boolean enabled, @NotNull Instant recordStartUtc,
			@NotNull Long timeOffsetMs, @NotNull DeclaredSyncStatus syncStatus) {
	}
}
