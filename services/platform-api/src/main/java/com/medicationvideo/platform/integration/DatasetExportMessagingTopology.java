package com.medicationvideo.platform.integration;

public final class DatasetExportMessagingTopology {

	public static final String EXCHANGE = "platform.dataset-export.v1";
	public static final String QUEUE = "platform.dataset-export.v1";
	public static final String ROUTING_KEY = "dataset.export.requested.v1";
	public static final String DEAD_LETTER_EXCHANGE = "platform.dead-letter.v1";
	public static final String DEAD_LETTER_QUEUE = "platform.dataset-export.dead-letter.v1";
	public static final String DEAD_LETTER_ROUTING_KEY = "dataset-export.dead-letter.v1";

	private DatasetExportMessagingTopology() {
	}
}
