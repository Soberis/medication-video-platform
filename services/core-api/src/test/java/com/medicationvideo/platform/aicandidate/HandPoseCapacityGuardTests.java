package com.medicationvideo.platform.aicandidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class HandPoseCapacityGuardTests {

	@Test
	void derivesTheP0BoundaryFromDurationSamplingAndMaximumHands() {
		assertEquals(20_000L, HandPoseCapacityGuard.maximumCandidateCount(1_000_000_000L, 10, 2));
		assertEquals(20_002L, HandPoseCapacityGuard.maximumCandidateCount(1_000_000_001L, 10, 2));
	}

	@Test
	void rejectsOnlyTheDerivedOverLimitInput() {
		HandPoseCapacityGuard.requireWithinP0ResultLimit(1_000_000_000L, 10, 2);
		var exception = assertThrows(HandPoseJobException.class,
				() -> HandPoseCapacityGuard.requireWithinP0ResultLimit(1_000_000_001L, 10, 2));
		assertEquals("AI_HAND_POSE_INPUT_EXCEEDS_P0_RESULT_LIMIT", exception.getErrorCode());
	}

}
