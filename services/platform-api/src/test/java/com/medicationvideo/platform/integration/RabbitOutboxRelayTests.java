package com.medicationvideo.platform.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RabbitOutboxRelayTests {

	@Test
	void publishesPersistentJsonAndMarksOnlyConfirmedEvent() {
		var store = mock(OutboxEventStore.class);
		var rabbitTemplate = mock(RabbitTemplate.class);
		var event = event();
		when(store.claim(eq(20), any(), eq(Duration.ofSeconds(30)))).thenReturn(List.of(event));
		doAnswer(invocation -> {
			var message = invocation.getArgument(2, Message.class);
			assertEquals("application/json", message.getMessageProperties().getContentType());
			assertEquals("PERSISTENT", message.getMessageProperties().getDeliveryMode().name());
			invocation.getArgument(3, CorrelationData.class)
					.getFuture()
					.complete(new CorrelationData.Confirm(true, null));
			return null;
		}).when(rabbitTemplate).send(
				eq(MediaMessagingTopology.EXCHANGE),
				eq(MediaMessagingTopology.PROBE_ROUTING_KEY),
				any(Message.class),
				any(CorrelationData.class));

		new RabbitOutboxRelay(
				store,
				rabbitTemplate,
				20,
				Duration.ofSeconds(30),
				Duration.ofSeconds(5),
				Duration.ofSeconds(5)).relay();

		verify(store).markPublished(eq(event.outboxEventId()), eq(event.claimToken()), any());
		verify(store, never()).release(any(), any(), any(), any());
	}

	@Test
	void releasesUnconfirmedEventForBoundedRetry() {
		var store = mock(OutboxEventStore.class);
		var rabbitTemplate = mock(RabbitTemplate.class);
		var event = event();
		when(store.claim(eq(20), any(), eq(Duration.ofSeconds(30)))).thenReturn(List.of(event));
		doAnswer(invocation -> {
			invocation.getArgument(3, CorrelationData.class)
					.getFuture()
					.complete(new CorrelationData.Confirm(false, "synthetic nack"));
			return null;
		}).when(rabbitTemplate).send(any(), any(), any(Message.class), any(CorrelationData.class));

		new RabbitOutboxRelay(
				store,
				rabbitTemplate,
				20,
				Duration.ofSeconds(30),
				Duration.ofSeconds(5),
				Duration.ofSeconds(5)).relay();

		verify(store, never()).markPublished(any(), any(), any());
		verify(store).release(
				eq(event.outboxEventId()),
				eq(event.claimToken()),
				any(),
				eq("BROKER_PUBLISH_FAILED"));
	}

	@Test
	void topologyUsesOneDurableBusinessQueueAndOneDeadLetterQueue() {
		var topology = new RabbitMediaTopologyConfiguration();
		var probeQueue = topology.mediaProbeQueue();
		var deadLetterQueue = topology.mediaDeadLetterQueue();

		assertTrue(probeQueue.isDurable());
		assertTrue(deadLetterQueue.isDurable());
		assertTrue(probeQueue.getArguments().isEmpty());
	}

	private static OutboxEventStore.ClaimedOutboxEvent event() {
		return new OutboxEventStore.ClaimedOutboxEvent(
				UUID.randomUUID(),
				UUID.randomUUID(),
				MediaMessagingTopology.EXCHANGE,
				MediaMessagingTopology.PROBE_ROUTING_KEY,
				"{\"schema_version\":\"media-probe-job.v1\"}");
	}
}
