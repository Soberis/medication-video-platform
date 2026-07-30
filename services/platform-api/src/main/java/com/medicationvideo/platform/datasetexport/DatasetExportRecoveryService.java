package com.medicationvideo.platform.datasetexport;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;

@Service
class DatasetExportRecoveryService {

	private final DatasetExportRepository exportRepository;
	private final DatasetExportAttemptRepository attemptRepository;
	private final DatasetExportAttemptQueueService attemptQueue;
	private final DatasetExportRetryPolicy retryPolicy;
	private final DatasetExportOutputCleanup outputCleanup;
	private final AuditEventRecorder auditRecorder;
	private final TransactionTemplate transactionTemplate;
	private final Duration leaseGrace;
	private final int batchSize;

	DatasetExportRecoveryService(
			DatasetExportRepository exportRepository,
			DatasetExportAttemptRepository attemptRepository,
			DatasetExportAttemptQueueService attemptQueue,
			DatasetExportRetryPolicy retryPolicy,
			DatasetExportOutputCleanup outputCleanup,
			AuditEventRecorder auditRecorder,
			PlatformTransactionManager transactionManager,
			@Value("${platform.dataset-export.lease-grace:PT5M}") Duration leaseGrace,
			@Value("${platform.dataset-export.recovery-batch-size:20}") int batchSize) {
		this.exportRepository = exportRepository;
		this.attemptRepository = attemptRepository;
		this.attemptQueue = attemptQueue;
		this.retryPolicy = retryPolicy;
		this.outputCleanup = outputCleanup;
		this.auditRecorder = auditRecorder;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.leaseGrace = requirePositive(leaseGrace, "Dataset export lease grace");
		if (batchSize < 1 || batchSize > 1000) {
			throw new IllegalArgumentException(
					"Dataset export recovery batch size must be between 1 and 1000.");
		}
		this.batchSize = batchSize;
	}

	int recoverExpiredAttempts() {
		return recoverExpiredAttempts(Instant.now().truncatedTo(ChronoUnit.MICROS));
	}

	int recoverExpiredAttempts(Instant now) {
		var cutoff = now.minus(this.leaseGrace);
		var recovered = 0;
		for (var exportId : this.attemptRepository
				.findRecoverableExportIds(cutoff, this.batchSize)) {
			var changed = this.transactionTemplate.execute(
					status -> recoverOne(exportId, cutoff, now));
			if (Boolean.TRUE.equals(changed)) {
				recovered++;
			}
		}
		return recovered;
	}

	private boolean recoverOne(UUID exportId, Instant cutoff, Instant now) {
		var export = this.exportRepository.findForUpdate(exportId).orElse(null);
		if (export == null || export.getStatus() != DatasetExportStatus.RUNNING) {
			return false;
		}
		var attempt = this.attemptRepository.findCurrentForUpdate(exportId).orElse(null);
		if (attempt == null || !attempt.isRecoverableAt(cutoff)) {
			return false;
		}

		this.outputCleanup.discard(attempt);
		attempt.lose(now);
		if (!this.retryPolicy.hasAttemptsRemaining(attempt.getAttempt())) {
			export.fail(now);
			recordAudit(export, attempt, "DATASET_EXPORT_FAILED", now);
			return true;
		}

		export.waitForRetry();
		this.attemptQueue.enqueue(export, attempt.getAttempt() + 1, now, now);
		recordAudit(export, attempt, "DATASET_EXPORT_RETRY_SCHEDULED", now);
		return true;
	}

	private void recordAudit(
			DatasetExport export,
			DatasetExportAttempt attempt,
			String action,
			Instant now) {
		this.auditRecorder.record(new AuditEventCommand(
				now,
				AuditActorType.SYSTEM,
				"dataset-export-recovery",
				export.getProjectId(),
				action,
				"DatasetExport",
				export.getDatasetExportId().toString(),
				Long.toString(export.getVersion()),
				AuditOutcome.SUCCEEDED,
				"WORKER_LEASE_LOST",
				attempt.getMessageId().toString(),
				null,
				"EXPORT_ATTEMPT:" + attempt.getDatasetExportAttemptId(),
				Map.of(
						"attempt", Integer.toString(attempt.getAttempt()),
						"snapshot_digest", export.getSnapshotDigest(),
						"export_profile_digest", export.getExportProfileDigest())));
	}

	private static Duration requirePositive(Duration value, String label) {
		if (value.isNegative() || value.isZero()) {
			throw new IllegalArgumentException(label + " must be positive.");
		}
		return value;
	}
}
