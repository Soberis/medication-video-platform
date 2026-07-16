package com.medicationvideo.platform.assetcapture;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

record UploadFileRegistrationRequest(
		@NotNull UploadFileKind fileKind,
		@NotBlank
		@Size(min = 3, max = 128)
		@Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9!#$&^_.+\\-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+\\-]*$")
		String contentType,
		@Positive long sizeBytes,
		@NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String sha256,
		@Min(8_388_608) @Max(67_108_864) long partSizeBytes) {
}
