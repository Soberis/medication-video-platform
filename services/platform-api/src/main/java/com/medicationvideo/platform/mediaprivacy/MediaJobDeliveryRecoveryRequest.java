package com.medicationvideo.platform.mediaprivacy;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

record MediaJobDeliveryRecoveryRequest(@NotNull UUID sourceMessageId, @Positive int sourceAttempt,
		@NotNull MediaJobDeliveryRecoveryReason recoveryReason,
		@NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:/#-]{2,127}$") String changeReference) {
}
