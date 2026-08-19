package com.medicationvideo.platform.integration;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "platform.messaging.relay-enabled", havingValue = "true")
class RabbitMediaTopologyConfiguration {

	@Bean
	DirectExchange mediaExchange() {
		return new DirectExchange(MediaMessagingTopology.EXCHANGE, true, false);
	}

	@Bean
	DirectExchange mediaDeadLetterExchange() {
		return new DirectExchange(MediaMessagingTopology.DEAD_LETTER_EXCHANGE, true, false);
	}

	@Bean
	Queue mediaProbeQueue() {
		return new Queue(MediaMessagingTopology.PROBE_QUEUE, true, false, false);
	}

	@Bean
	Queue mediaDeadLetterQueue() {
		return new Queue(MediaMessagingTopology.DEAD_LETTER_QUEUE, true, false, false);
	}

	@Bean
	Binding mediaProbeBinding(Queue mediaProbeQueue, DirectExchange mediaExchange) {
		return BindingBuilder.bind(mediaProbeQueue).to(mediaExchange).with(MediaMessagingTopology.PROBE_ROUTING_KEY);
	}

	@Bean
	Binding mediaDeadLetterBinding(Queue mediaDeadLetterQueue, DirectExchange mediaDeadLetterExchange) {
		return BindingBuilder.bind(mediaDeadLetterQueue)
			.to(mediaDeadLetterExchange)
			.with(MediaMessagingTopology.DEAD_LETTER_ROUTING_KEY);
	}

}
