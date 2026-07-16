package com.medicationvideo.platform.mediaprivacy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

record MediaLeaseRequest(
		@NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$") String workerId) {
}
