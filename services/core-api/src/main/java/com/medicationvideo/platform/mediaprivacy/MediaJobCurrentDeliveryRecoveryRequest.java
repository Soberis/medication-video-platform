package com.medicationvideo.platform.mediaprivacy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Browser-safe recovery command. The server derives the current message and attempt under
 * lock, so the page never receives a lease capability or needs to submit broker
 * diagnostic data.
 */
record MediaJobCurrentDeliveryRecoveryRequest(
		@NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:/#-]{2,127}$") String changeReference) {
}
