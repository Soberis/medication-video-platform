package com.medicationvideo.platform.integration;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "platform.messaging.relay-enabled", havingValue = "true")
class RabbitAiTopologyConfiguration {

	@Bean
	DirectExchange aiExchange() {
		return new DirectExchange(AiMessagingTopology.EXCHANGE, true, false);
	}

	@Bean
	Queue aiHandPoseQueue() {
		return new Queue(AiMessagingTopology.HAND_POSE_QUEUE, true, false, false);
	}

	@Bean
	Binding aiHandPoseBinding(Queue aiHandPoseQueue, DirectExchange aiExchange) {
		return BindingBuilder.bind(aiHandPoseQueue).to(aiExchange).with(AiMessagingTopology.HAND_POSE_ROUTING_KEY);
	}

}
