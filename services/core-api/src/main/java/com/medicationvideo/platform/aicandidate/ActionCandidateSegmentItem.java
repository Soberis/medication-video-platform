package com.medicationvideo.platform.aicandidate;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ActionCandidateSegmentItem(@NotNull @Min(0) Long startMs, @NotNull @Min(1) Long endMs,
		@NotBlank String label, @DecimalMin("0.0") @DecimalMax("1.0") Double confidence, Integer sourceWindow,
		List<Integer> stitchedWindows, @Size(max = 64) @JsonAlias("item") String targetObject) {

	public ActionCandidateSegmentItem(Long startMs, Long endMs, String label, Double confidence, Integer sourceWindow,
			List<Integer> stitchedWindows) {
		this(startMs, endMs, label, confidence, sourceWindow, stitchedWindows, null);
	}
}
