package com.medicationvideo.platform.aicandidate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ActionCandidateResultRequest(@NotNull UUID messageId, @NotBlank String modelName, String modelRevision,
		int samplingFps, long peakMemoryMb, long inferenceTimeMs, @NotNull Instant startedAt,
		@NotNull Instant completedAt, @NotNull List<@Valid ActionCandidateSegmentItem> segments) {
}
