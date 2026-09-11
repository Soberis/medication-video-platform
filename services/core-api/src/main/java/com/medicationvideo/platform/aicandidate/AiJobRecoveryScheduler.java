package com.medicationvideo.platform.aicandidate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "platform.messaging.relay-enabled", havingValue = "true")
class AiJobRecoveryScheduler {

	private static final Logger log = LoggerFactory.getLogger(AiJobRecoveryScheduler.class);

	private final AiJobRecoveryService service;

	AiJobRecoveryScheduler(AiJobRecoveryService service) {
		this.service = service;
	}

	@Scheduled(fixedDelayString = "${platform.ai.hand-pose.recovery-poll-delay:PT30S}")
	void recoverExpiredAttempts() {
		var recovered = this.service.recoverExpiredAttempts();
		if (recovered > 0) {
			log.info("Recovered {} AI attempt(s).", recovered);
		}
	}

}
