package com.medicationvideo.platform.iamproject;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

record ProjectCreateRequest(
		@NotBlank
		@Size(max = 128)
		@Pattern(regexp = "^[^\\p{Cc}]+$") String displayName) {
}
