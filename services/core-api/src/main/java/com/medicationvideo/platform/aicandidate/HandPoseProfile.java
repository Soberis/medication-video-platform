package com.medicationvideo.platform.aicandidate;

final class HandPoseProfile {

	static final String ALGORITHM_KEY = "MEDIAPIPE_HAND_LANDMARKER";

	static final String ALGORITHM_VERSION = "v1";

	static final int SAMPLING_FPS = 10;

	static final int MAX_HANDS = 2;

	static final int MAX_CANDIDATES = 20_000;

	static final String MIN_HAND_DETECTION_CONFIDENCE = "0.5";

	static final String MIN_HAND_PRESENCE_CONFIDENCE = "0.5";

	static final String MIN_TRACKING_CONFIDENCE = "0.5";

	static final String HANDEDNESS_POLICY = "MEDIAPIPE_REPORTED";

	static final String TIMESTAMP_POLICY = "CONTAINER_PTS_STRICTLY_INCREASING";

	static final String BOUNDING_BOX_RECIPE = "FINITE_NORMALIZED_LANDMARK_BOUNDS_V1";

	static final String SAMPLING_POLICY = "FIXED_TIME_GRID_FIRST_FRAME_AT_OR_AFTER_V1";

	static final String PROFILE_SHA256 = AiDigests
		.sha256(String.join("\n", ALGORITHM_KEY, ALGORITHM_VERSION, Integer.toString(SAMPLING_FPS),
				Integer.toString(MAX_HANDS), MIN_HAND_DETECTION_CONFIDENCE, MIN_HAND_PRESENCE_CONFIDENCE,
				MIN_TRACKING_CONFIDENCE, HANDEDNESS_POLICY, TIMESTAMP_POLICY, BOUNDING_BOX_RECIPE, SAMPLING_POLICY));

	private HandPoseProfile() {
	}

}
