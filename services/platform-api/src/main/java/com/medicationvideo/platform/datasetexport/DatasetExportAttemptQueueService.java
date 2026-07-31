package com.medicationvideo.platform.datasetexport;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
class DatasetExportAttemptQueueService {

	private final DatasetExportAttemptRepository attemptRepository;

	private final DatasetExportDispatchPort dispatchPort;

	DatasetExportAttemptQueueService(DatasetExportAttemptRepository attemptRepository,
			DatasetExportDispatchPort dispatchPort) {
		this.attemptRepository = attemptRepository;
		this.dispatchPort = dispatchPort;
	}

	DatasetExportAttempt enqueue(DatasetExport export, int attemptNumber, Instant occurredAt, Instant availableAt) {
		var capability = DatasetExportLeaseCapabilityFactory.create();
		var messageId = UUID.randomUUID();
		var attempt = this.attemptRepository
			.saveAndFlush(DatasetExportAttempt.queue(export, attemptNumber, messageId, capability.hash(), occurredAt));
		this.dispatchPort.enqueue(
				DatasetExportJobEnvelope.create(export, attemptNumber, messageId, capability.token(), occurredAt),
				availableAt);
		return attempt;
	}

}
