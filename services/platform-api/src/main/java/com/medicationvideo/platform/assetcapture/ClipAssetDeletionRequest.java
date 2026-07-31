package com.medicationvideo.platform.assetcapture;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

record ClipAssetDeletionRequest(
		@NotBlank @Size(max = 500) @Pattern(regexp = "^(?!.*[\\p{Cntrl}]).*\\S.*$") String reason) {
}
