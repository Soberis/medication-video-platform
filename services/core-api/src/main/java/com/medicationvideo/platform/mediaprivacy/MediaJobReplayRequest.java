package com.medicationvideo.platform.mediaprivacy;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

record MediaJobReplayRequest(@NotNull UUID deadLetterId, @NotNull UUID sourceMessageId, @Min(1) int sourceAttempt,
		@NotNull MediaDeadLetterReason deadLetterReason, @NotNull @Pattern(regexp = "^[a-f0-9]{64}$") String bodySha256,
		@NotNull MediaJobReplayReason operatorReasonCode,
		@NotNull @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:/#-]{2,127}$") String changeReference) {
}
