package com.medicationvideo.platform.integration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "platform.messaging.relay-enabled", havingValue = "true")
class RabbitOutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(RabbitOutboxRelay.class);

	private final OutboxEventStore store;
	private final RabbitTemplate rabbitTemplate;
	private final int batchSize;
	private final Duration claimDuration;
	private final Duration retryDelay;
	private final Duration confirmTimeout;
	private final ConcurrentMap<String, CorrelationData> pendingReturns = new ConcurrentHashMap<>();

	RabbitOutboxRelay(
			OutboxEventStore store,
			RabbitTemplate rabbitTemplate,
			@Value("${platform.messaging.outbox.batch-size:20}") int batchSize,
			@Value("${platform.messaging.outbox.claim-duration:PT30S}") Duration claimDuration,
			@Value("${platform.messaging.outbox.retry-delay:PT5S}") Duration retryDelay,
			@Value("${platform.messaging.outbox.confirm-timeout:PT5S}") Duration confirmTimeout) {
		this.store = store;
		this.rabbitTemplate = rabbitTemplate;
		this.rabbitTemplate.setMandatory(true);
		this.rabbitTemplate.setReturnsCallback(this::recordReturnedMessage);
		this.batchSize = batchSize;
		this.claimDuration = claimDuration;
		this.retryDelay = retryDelay;
		this.confirmTimeout = confirmTimeout;
	}

	@Scheduled(fixedDelayString = "${platform.messaging.outbox.poll-delay:PT1S}")
	void relay() {
		var now = Instant.now();
		for (var event : this.store.claim(this.batchSize, now, this.claimDuration)) {
			try {
				publish(event);
				this.store.markPublished(event.outboxEventId(), event.claimToken(), Instant.now());
			}
			catch (Exception exception) {
				this.store.release(
						event.outboxEventId(),
						event.claimToken(),
						Instant.now().plus(this.retryDelay),
						"BROKER_PUBLISH_FAILED");
				log.warn("Outbox event {} was not confirmed and will be retried.", event.outboxEventId());
			}
		}
	}

	private void publish(OutboxEventStore.ClaimedOutboxEvent event) throws Exception {
		var correlation = new CorrelationData(event.outboxEventId().toString());
		var message = MessageBuilder
				.withBody(event.payloadJson().getBytes(StandardCharsets.UTF_8))
				.setContentType(MessageProperties.CONTENT_TYPE_JSON)
				.setContentEncoding(StandardCharsets.UTF_8.name())
				.setDeliveryMode(MessageDeliveryMode.PERSISTENT)
				.setMessageId(event.outboxEventId().toString())
				.build();
		this.pendingReturns.put(correlation.getId(), correlation);
		try {
			this.rabbitTemplate.send(event.exchangeName(), event.routingKey(), message, correlation);
			var confirm = correlation.getFuture().get(this.confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
			if (!confirm.ack() || correlation.getReturned() != null) {
				throw new IllegalStateException("RabbitMQ did not confirm the Outbox message.");
			}
		}
		finally {
			this.pendingReturns.remove(correlation.getId(), correlation);
		}
	}

	private void recordReturnedMessage(ReturnedMessage returned) {
		var correlationId = returned.getMessage().getMessageProperties()
				.getHeader("spring_returned_message_correlation");
		if (correlationId == null) {
			log.warn("RabbitMQ returned an Outbox message without a correlation identifier.");
			return;
		}
		var correlation = this.pendingReturns.get(correlationId.toString());
		if (correlation == null) {
			log.warn("RabbitMQ returned an Outbox message after its publish confirmation was closed.");
			return;
		}
		correlation.setReturned(returned);
	}
}
