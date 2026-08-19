package com.medicationvideo.platform.mediaprivacy;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

record MediaProbeErrorPayload(@NotNull MediaProbeErrorClass errorClass, @NotNull MediaProbeErrorCode errorCode,
		@NotBlank @Size(max = 512) String safeMessage, @NotNull Boolean retryable) {

	@AssertTrue(message = "The media probe error class, code and retryable flag are inconsistent.")
	boolean validCombination() {
		if (this.errorClass == null || this.errorCode == null || this.retryable == null) {
			return false;
		}
		return switch (this.errorClass) {
			case RETRYABLE_TRANSIENT ->
				this.errorCode == MediaProbeErrorCode.OBJECT_STORAGE_UNAVAILABLE && this.retryable;
			case RETRYABLE_RESOURCE -> this.errorCode == MediaProbeErrorCode.MEDIA_PROBE_TIMEOUT && this.retryable;
			case INTERNAL_BUG -> this.errorCode == MediaProbeErrorCode.MEDIA_PROBE_INTERNAL && this.retryable;
			case NON_RETRYABLE_INPUT -> switch (this.errorCode) {
				case MEDIA_INPUT_DIGEST_MISMATCH, MEDIA_CONTAINER_UNSUPPORTED, MEDIA_VIDEO_STREAM_MISSING,
						MEDIA_DECODE_FAILED, MEDIA_TIMELINE_INVALID ->
					!this.retryable;
				default -> false;
			};
		};
	}
}
