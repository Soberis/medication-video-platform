package com.medicationvideo.platform.integration;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "platform.messaging.relay-enabled", havingValue = "true")
class RabbitDatasetExportTopologyConfiguration {

	@Bean
	DirectExchange datasetExportExchange() {
		return new DirectExchange(DatasetExportMessagingTopology.EXCHANGE, true, false);
	}

	@Bean
	Queue datasetExportQueue() {
		return new Queue(DatasetExportMessagingTopology.QUEUE, true, false, false);
	}

	@Bean
	Queue datasetExportDeadLetterQueue() {
		return new Queue(DatasetExportMessagingTopology.DEAD_LETTER_QUEUE, true, false, false);
	}

	@Bean
	Binding datasetExportBinding(@Qualifier("datasetExportQueue") Queue queue,
			@Qualifier("datasetExportExchange") DirectExchange exchange) {
		return BindingBuilder.bind(queue).to(exchange).with(DatasetExportMessagingTopology.ROUTING_KEY);
	}

	@Bean
	Binding datasetExportDeadLetterBinding(@Qualifier("datasetExportDeadLetterQueue") Queue queue,
			@Qualifier("mediaDeadLetterExchange") DirectExchange exchange) {
		return BindingBuilder.bind(queue).to(exchange).with(DatasetExportMessagingTopology.DEAD_LETTER_ROUTING_KEY);
	}

}
