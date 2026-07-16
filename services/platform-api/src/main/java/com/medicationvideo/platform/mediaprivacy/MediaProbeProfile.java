package com.medicationvideo.platform.mediaprivacy;

/**
 * The only approved media-probe recipe in the current P0 slice. The digest is the SHA-256 of
 * {@code contracts/profiles/media-probe-baseline-v1.json} after removing its final newline.
 * Changing the profile content requires a new profile key and a new digest; existing jobs remain
 * bound to the recipe they were created with.
 */
final class MediaProbeProfile {

	static final String PROFILE_KEY = "media-probe-baseline-v1";
	static final String RECIPE_DIGEST = "8900937958d0fb81a9c9bf2d9e81ac3e57f35d838d5a4da52b58ccf0f5cd5765";

	private MediaProbeProfile() {
	}
}
