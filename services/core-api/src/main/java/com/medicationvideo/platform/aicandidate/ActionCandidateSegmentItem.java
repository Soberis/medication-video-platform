package com.medicationvideo.platform.aicandidate;

import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ActionCandidateSegmentItem(@NotNull @Min(0) Long startMs, @NotNull @Min(1) Long endMs,
		@NotBlank String label, @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double confidence, Integer sourceWindow,
		List<Integer> stitchedWindows) {
}
