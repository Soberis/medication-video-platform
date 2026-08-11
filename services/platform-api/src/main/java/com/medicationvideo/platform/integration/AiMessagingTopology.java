package com.medicationvideo.platform.integration;

public final class AiMessagingTopology {

	public static final String EXCHANGE = "platform.ai.v1";

	public static final String HAND_POSE_QUEUE = "platform.ai.hand-pose.v1";

	public static final String HAND_POSE_ROUTING_KEY = "ai.hand-pose.requested.v1";

	public static final String DEAD_LETTER_EXCHANGE = "platform.dead-letter.v1";

	public static final String DEAD_LETTER_QUEUE = "platform.ai.dead-letter.v1";

	public static final String DEAD_LETTER_ROUTING_KEY = "ai.dead-letter.v1";

	private AiMessagingTopology() {
	}

}
