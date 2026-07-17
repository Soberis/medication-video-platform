package com.medicationvideo.platform.assetcapture;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

record AssetFolderRequest(
		@NotBlank
		@Size(max = 128)
		@Pattern(regexp = "^[^\\p{Cc}]+$") String displayName) {
}
