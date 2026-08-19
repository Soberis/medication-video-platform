package com.medicationvideo.platform.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import tools.jackson.databind.ObjectMapper;

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
		}).when(rabbitTemplate)
			.send(eq(MediaMessagingTopology.EXCHANGE), eq(MediaMessagingTopology.PROBE_ROUTING_KEY), any(Message.class),
					any(CorrelationData.class));

		new RabbitOutboxRelay(store, rabbitTemplate, 20, Duration.ofSeconds(30), Duration.ofSeconds(5),
				Duration.ofSeconds(5))
			.relay();

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

		new RabbitOutboxRelay(store, rabbitTemplate, 20, Duration.ofSeconds(30), Duration.ofSeconds(5),
				Duration.ofSeconds(5))
			.relay();

		verify(store, never()).markPublished(any(), any(), any());
		verify(store).release(eq(event.outboxEventId()), eq(event.claimToken()), any(), eq("BROKER_PUBLISH_FAILED"));
	}

	@Test
	void releasesReturnedEventInsteadOfMarkingItPublished() {
		var store = mock(OutboxEventStore.class);
		var rabbitTemplate = mock(RabbitTemplate.class);
		var returnsCallback = new AtomicReference<RabbitTemplate.ReturnsCallback>();
		doAnswer(invocation -> {
			returnsCallback.set(invocation.getArgument(0, RabbitTemplate.ReturnsCallback.class));
			return null;
		}).when(rabbitTemplate).setReturnsCallback(any());
		var event = event();
		when(store.claim(eq(20), any(), eq(Duration.ofSeconds(30)))).thenReturn(List.of(event));
		doAnswer(invocation -> {
			var correlation = invocation.getArgument(3, CorrelationData.class);
			var properties = new MessageProperties();
			properties.setHeader("spring_returned_message_correlation", correlation.getId());
			returnsCallback.get()
				.returnedMessage(new ReturnedMessage(new Message(new byte[0], properties), 312, "NO_ROUTE",
						"missing.exchange", "missing.route"));
			correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
			return null;
		}).when(rabbitTemplate).send(any(), any(), any(Message.class), any(CorrelationData.class));

		new RabbitOutboxRelay(store, rabbitTemplate, 20, Duration.ofSeconds(30), Duration.ofSeconds(5),
				Duration.ofSeconds(5))
			.relay();

		verify(store, never()).markPublished(any(), any(), any());
		verify(store).release(eq(event.outboxEventId()), eq(event.claimToken()), any(), eq("BROKER_PUBLISH_FAILED"));
	}

	@Test
	void republishesIdenticalEnvelopeAfterConfirmedPublishAndLeaseExpiryWhenMarkPublishedFails() throws Exception {
		var store = mock(OutboxEventStore.class);
		var outboxEventId = UUID.randomUUID();
		var envelopeMessageId = UUID.randomUUID();
		var claimToken1 = UUID.randomUUID();
		var claimToken2 = UUID.randomUUID();
		var payload = completeMediaProbePayload(envelopeMessageId);
		var event1 = new OutboxEventStore.ClaimedOutboxEvent(outboxEventId, claimToken1,
				MediaMessagingTopology.EXCHANGE, MediaMessagingTopology.PROBE_ROUTING_KEY, payload);
		var event2 = new OutboxEventStore.ClaimedOutboxEvent(outboxEventId, claimToken2,
				MediaMessagingTopology.EXCHANGE, MediaMessagingTopology.PROBE_ROUTING_KEY, payload);
		when(store.claim(eq(1), any(), eq(Duration.ofSeconds(30)))).thenReturn(List.of(event1),
				List.<OutboxEventStore.ClaimedOutboxEvent>of(), List.of(event2));
		doThrow(new IllegalStateException("Synthetic markPublished persistence failure")).doNothing()
			.when(store)
			.markPublished(eq(outboxEventId), any(), any());
		doThrow(new SimulatedProcessCrash()).when(store)
			.release(eq(outboxEventId), eq(claimToken1), any(), eq("BROKER_PUBLISH_FAILED"));

		var rabbitTemplate = mock(RabbitTemplate.class);
		var publishedEnvelopes = new ArrayList<PublishedEnvelope>();
		doAnswer(invocation -> {
			var message = invocation.getArgument(2, Message.class);
			var correlation = invocation.getArgument(3, CorrelationData.class);
			publishedEnvelopes.add(PublishedEnvelope.capture(invocation.getArgument(0, String.class),
					invocation.getArgument(1, String.class), message, correlation));
			correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
			return null;
		}).when(rabbitTemplate).send(any(), any(), any(Message.class), any(CorrelationData.class));

		var relay = new RabbitOutboxRelay(store, rabbitTemplate, 1, Duration.ofSeconds(30), Duration.ofSeconds(5),
				Duration.ofSeconds(5));

		// markPublished fails after broker confirm, then release fails before claim
		// persistence: process crash.
		assertThrows(SimulatedProcessCrash.class, relay::relay);
		assertEquals(1, publishedEnvelopes.size());

		relay.relay();
		assertEquals(1, publishedEnvelopes.size());

		relay.relay();

		assertEquals(2, publishedEnvelopes.size());

		var first = publishedEnvelopes.get(0);
		var second = publishedEnvelopes.get(1);
		assertEquals(first.exchangeName(), second.exchangeName());
		assertEquals(first.routingKey(), second.routingKey());
		assertEquals(first.messageId(), second.messageId());
		assertEquals(first.correlationId(), second.correlationId());
		assertEquals(outboxEventId.toString(), first.messageId());
		assertEquals(first.contentType(), second.contentType());
		assertEquals(first.contentEncoding(), second.contentEncoding());
		assertEquals(first.deliveryMode(), second.deliveryMode());
		assertArrayEquals(first.payload(), second.payload());

		var objectMapper = new ObjectMapper();
		var firstPayload = objectMapper.readTree(new String(first.payload(), StandardCharsets.UTF_8));
		var secondPayload = objectMapper.readTree(new String(second.payload(), StandardCharsets.UTF_8));
		assertEquals(envelopeMessageId.toString(), firstPayload.get("message_id").asText());
		assertEquals(firstPayload.get("message_id").asText(), secondPayload.get("message_id").asText());
		assertEquals(1, firstPayload.get("attempt").asInt());
		assertEquals(firstPayload.get("attempt").asInt(), secondPayload.get("attempt").asInt());
		verify(store, times(2)).markPublished(eq(outboxEventId), any(), any());
		verify(store).markPublished(eq(outboxEventId), eq(claimToken1), any());
		verify(store).markPublished(eq(outboxEventId), eq(claimToken2), any());
		verify(store, times(1)).release(eq(outboxEventId), eq(claimToken1), any(), eq("BROKER_PUBLISH_FAILED"));
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
		return new OutboxEventStore.ClaimedOutboxEvent(UUID.randomUUID(), UUID.randomUUID(),
				MediaMessagingTopology.EXCHANGE, MediaMessagingTopology.PROBE_ROUTING_KEY,
				"{\"schema_version\":\"media-probe-job.v1\"}");
	}

	private static String completeMediaProbePayload(UUID envelopeMessageId) {
		return """
				{"schema_version":"media-probe-job.v1","message_id":"%s","job_id":"00000000-0000-0000-0000-000000000001","job_type":"MEDIA_PROBE","attempt":1,"lease_token":"synthetic-lease-token-012345678901234567890123","project_id":"00000000-0000-0000-0000-000000000002","clip_asset_id":"00000000-0000-0000-0000-000000000003","input_sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","input_size_bytes":1024,"content_type":"video/mp4","probe_profile_key":"ffprobe-default","recipe_digest":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","requested_at":"2026-01-01T00:00:00Z"}
				"""
			.formatted(envelopeMessageId);
	}

	private record PublishedEnvelope(String exchangeName, String routingKey, String messageId, String correlationId,
			String contentType, String contentEncoding, String deliveryMode, byte[] payload) {

		private static PublishedEnvelope capture(String exchangeName, String routingKey, Message message,
				CorrelationData correlation) {
			var properties = message.getMessageProperties();
			return new PublishedEnvelope(exchangeName, routingKey, properties.getMessageId(), correlation.getId(),
					properties.getContentType(), properties.getContentEncoding(), properties.getDeliveryMode().name(),
					message.getBody().clone());
		}

	}

	private static final class SimulatedProcessCrash extends RuntimeException {

	}

}
