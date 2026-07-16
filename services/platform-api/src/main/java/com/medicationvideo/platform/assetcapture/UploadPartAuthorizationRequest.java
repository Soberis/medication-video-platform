package com.medicationvideo.platform.assetcapture;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotNull;

record UploadPartAuthorizationRequest(
		@NotNull @Pattern(regexp = "^[a-f0-9]{64}$") String sha256) {
}
