package com.medicationvideo.platform.integration;

public final class MediaMessagingTopology {

	public static final String EXCHANGE = "platform.media.v1";
	public static final String PROBE_QUEUE = "platform.media.probe.v1";
	public static final String PROBE_ROUTING_KEY = "media.probe.requested.v1";
	public static final String DEAD_LETTER_EXCHANGE = "platform.dead-letter.v1";
	public static final String DEAD_LETTER_QUEUE = "platform.media.dead-letter.v1";
	public static final String DEAD_LETTER_ROUTING_KEY = "media.dead-letter.v1";

	private MediaMessagingTopology() {
	}
}
