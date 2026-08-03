package com.medicationvideo.platform.datasetexport;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

record DatasetExportManualReplayRequest(@NotNull UUID deadLetterId, @NotNull UUID sourceMessageId,
		@NotNull @Min(1) Integer sourceAttempt, @NotNull DatasetExportDeadLetterReason deadLetterReason,
		@NotNull @Pattern(regexp = "^[a-f0-9]{64}$") String bodySha256,
		@NotNull DatasetExportManualReplayReason operatorReasonCode,
		@NotNull @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:/#-]{2,127}$") String changeReference) {
}
