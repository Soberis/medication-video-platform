package com.medicationvideo.platform.mediaprivacy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "platform.messaging.relay-enabled", havingValue = "true")
class MediaJobRecoveryScheduler {

	private static final Logger log = LoggerFactory.getLogger(MediaJobRecoveryScheduler.class);

	private final MediaJobRecoveryService service;

	MediaJobRecoveryScheduler(MediaJobRecoveryService service) {
		this.service = service;
	}

	@Scheduled(fixedDelayString = "${platform.media-probe.recovery-poll-delay:PT30S}")
	void recoverExpiredAttempts() {
		var recovered = this.service.recoverExpiredAttempts();
		if (recovered > 0) {
			log.info("Recovered {} expired media job attempt(s).", recovered);
		}
	}

}
