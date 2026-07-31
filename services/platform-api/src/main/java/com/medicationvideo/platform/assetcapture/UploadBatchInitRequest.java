package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

record UploadBatchInitRequest(@NotNull UUID projectId,
		@NotBlank @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$") String deviceId, UploadSourceType uploadSourceType,
		UUID assetFolderId, @Positive int fileCount, @Positive long totalSizeBytes,
		@NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String manifestSha256) {
}
