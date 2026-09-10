package com.medicationvideo.platform.aicandidate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Action candidate result reported by the Worker. Field names follow the frozen
 * {@code ai-action-result.v1} payload; every field the Worker sends is modelled here so
 * provenance is persisted instead of being silently dropped.
 */
public record ActionCandidateResultRequest(@NotNull UUID messageId, @NotBlank String modelName, String promptVersion,
		@NotNull @Min(1) Integer totalWindows, @NotNull @Min(1) Long durationMs,
		@NotNull @Min(0) Long inferenceDurationMs, @NotNull Instant startedAt, @NotNull Instant completedAt,
		@NotNull List<@Valid ActionCandidateSegmentItem> segments) {
}
