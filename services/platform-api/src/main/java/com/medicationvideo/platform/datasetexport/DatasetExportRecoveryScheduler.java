package com.medicationvideo.platform.datasetexport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "platform.messaging.relay-enabled", havingValue = "true")
class DatasetExportRecoveryScheduler {

	private static final Logger log = LoggerFactory.getLogger(DatasetExportRecoveryScheduler.class);

	private final DatasetExportRecoveryService service;

	DatasetExportRecoveryScheduler(DatasetExportRecoveryService service) {
		this.service = service;
	}

	@Scheduled(fixedDelayString = "${platform.dataset-export.recovery-poll-delay:PT30S}")
	void recoverExpiredAttempts() {
		var recovered = this.service.recoverExpiredAttempts();
		if (recovered > 0) {
			log.info("Recovered {} expired dataset export attempt(s).", recovered);
		}
	}

}
