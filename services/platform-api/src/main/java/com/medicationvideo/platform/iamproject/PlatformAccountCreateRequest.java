package com.medicationvideo.platform.iamproject;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

record PlatformAccountCreateRequest(
		@NotBlank @Size(min = 3, max = 128) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:@-]{2,127}$") String username,
		@NotBlank @Size(min = 12, max = 72) String password) {
}
