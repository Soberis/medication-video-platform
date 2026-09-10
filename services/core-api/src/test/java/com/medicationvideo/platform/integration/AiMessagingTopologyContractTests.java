package com.medicationvideo.platform.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AiMessagingTopologyContractTests {

	@Test
	void declaresTheHandPoseQueueAndSanitizedDeadLetterRoute() {
		assertEquals("platform.ai.v1", AiMessagingTopology.EXCHANGE);
		assertEquals("platform.ai.hand-pose.v1", AiMessagingTopology.HAND_POSE_QUEUE);
		assertEquals("ai.hand-pose.requested.v1", AiMessagingTopology.HAND_POSE_ROUTING_KEY);
		assertEquals("platform.ai.action-candidate.v1", AiMessagingTopology.ACTION_QUEUE);
		assertEquals("ai.action.requested.v1", AiMessagingTopology.ACTION_ROUTING_KEY);
		assertEquals("platform.dead-letter.v1", AiMessagingTopology.DEAD_LETTER_EXCHANGE);
		assertEquals("platform.ai.dead-letter.v1", AiMessagingTopology.DEAD_LETTER_QUEUE);
		assertEquals("ai.dead-letter.v1", AiMessagingTopology.DEAD_LETTER_ROUTING_KEY);
	}

}
