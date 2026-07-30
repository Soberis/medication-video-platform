package com.medicationvideo.platform.datasetexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import com.medicationvideo.platform.governance.AuditEventRecorder;

class DatasetExportRecoveryServiceTests {

	private static final Duration LEASE_GRACE = Duration.ofMinutes(5);
	private static final Instant RECOVERED_AT =
			DatasetExportRecoveryTestFixtures.STARTED_AT.plus(Duration.ofMinutes(7));

	@Test
	void marksTheLostAttemptAndQueuesOneFreshCapability() {
		var fixture = fixture(1);

		assertEquals(1, fixture.service().recoverExpiredAttempts(RECOVERED_AT));

		assertEquals(DatasetExportAttemptStatus.LOST, fixture.attempt().getStatus());
		assertEquals(DatasetExportStatus.RETRY_WAIT, fixture.export().getStatus());
		verify(fixture.outputCleanup()).discard(fixture.attempt());
		verify(fixture.attemptQueue()).enqueue(
				fixture.export(), 2, RECOVERED_AT, RECOVERED_AT);
		verify(fixture.auditRecorder()).record(
				org.mockito.ArgumentMatchers.argThat(command ->
						"DATASET_EXPORT_RETRY_SCHEDULED".equals(command.action())
								&& "WORKER_LEASE_LOST".equals(command.reasonCode())));
	}

	@Test
	void failsTheExportWhenTheFinalAttemptLosesItsLease() {
		var fixture = fixture(3);

		assertEquals(1, fixture.service().recoverExpiredAttempts(RECOVERED_AT));

		assertEquals(DatasetExportAttemptStatus.LOST, fixture.attempt().getStatus());
		assertEquals(DatasetExportStatus.FAILED, fixture.export().getStatus());
		assertEquals(RECOVERED_AT, fixture.export().getCompletedAt());
		verify(fixture.outputCleanup()).discard(fixture.attempt());
		verify(fixture.attemptQueue(), never()).enqueue(
				any(), anyInt(), any(), any());
		verify(fixture.auditRecorder()).record(
				org.mockito.ArgumentMatchers.argThat(command ->
						"DATASET_EXPORT_FAILED".equals(command.action())
								&& "WORKER_LEASE_LOST".equals(command.reasonCode())));
	}

	@Test
	void rechecksTheLeaseAfterLockingAndSkipsAnActiveAttempt() {
		var fixture = fixture(1);
		var beforeGrace = DatasetExportRecoveryTestFixtures.STARTED_AT.plus(Duration.ofMinutes(5));

		assertEquals(0, fixture.service().recoverExpiredAttempts(beforeGrace));

		assertEquals(DatasetExportAttemptStatus.RUNNING, fixture.attempt().getStatus());
		assertEquals(DatasetExportStatus.RUNNING, fixture.export().getStatus());
		verify(fixture.outputCleanup(), never()).discard(fixture.attempt());
		verify(fixture.attemptQueue(), never()).enqueue(
				any(), anyInt(), any(), any());
	}

	private static Fixture fixture(int attemptNumber) {
		var export = DatasetExportRecoveryTestFixtures.runningExport();
		var attempt = DatasetExportRecoveryTestFixtures.runningAttempt(export, attemptNumber);
		var exportRepository = mock(DatasetExportRepository.class);
		var attemptRepository = mock(DatasetExportAttemptRepository.class);
		var attemptQueue = mock(DatasetExportAttemptQueueService.class);
		var outputCleanup = mock(DatasetExportOutputCleanup.class);
		var auditRecorder = mock(AuditEventRecorder.class);
		var transactionManager = mock(PlatformTransactionManager.class);
		var transactionStatus = mock(TransactionStatus.class);
		when(transactionManager.getTransaction(any(TransactionDefinition.class)))
				.thenReturn(transactionStatus);
		when(attemptRepository.findRecoverableExportIds(any(), anyInt()))
				.thenReturn(List.of(export.getDatasetExportId()));
		when(exportRepository.findForUpdate(export.getDatasetExportId()))
				.thenReturn(Optional.of(export));
		when(attemptRepository.findCurrentForUpdate(export.getDatasetExportId()))
				.thenReturn(Optional.of(attempt));
		var service = new DatasetExportRecoveryService(
				exportRepository,
				attemptRepository,
				attemptQueue,
				new DatasetExportRetryPolicy(
						3, Duration.ofSeconds(5), Duration.ofMinutes(5)),
				outputCleanup,
				auditRecorder,
				transactionManager,
				LEASE_GRACE,
				20);
		return new Fixture(
				service, export, attempt, attemptQueue, outputCleanup, auditRecorder);
	}

	private record Fixture(
			DatasetExportRecoveryService service,
			DatasetExport export,
			DatasetExportAttempt attempt,
			DatasetExportAttemptQueueService attemptQueue,
			DatasetExportOutputCleanup outputCleanup,
			AuditEventRecorder auditRecorder) {
	}
}
