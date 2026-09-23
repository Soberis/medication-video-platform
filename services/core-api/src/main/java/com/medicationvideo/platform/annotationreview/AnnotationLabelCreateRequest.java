package com.medicationvideo.platform.annotationreview;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

record AnnotationLabelCreateRequest(@NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]{0,63}$") String labelId,
		@NotBlank @Size(max = 128) @Pattern(regexp = "^[^\\p{Cc}]+$") String displayName,
		@NotBlank @Size(max = 512) @Pattern(regexp = "^[^\\p{Cc}]+$") String description) {
}
