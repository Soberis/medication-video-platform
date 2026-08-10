package com.medicationvideo.platform.integration;

public final class AiMessagingTopology {

	public static final String EXCHANGE = "platform.ai.v1";

	public static final String HAND_POSE_QUEUE = "platform.ai.hand-pose.v1";

	public static final String HAND_POSE_ROUTING_KEY = "ai.hand-pose.requested.v1";

	private AiMessagingTopology() {
	}

}
