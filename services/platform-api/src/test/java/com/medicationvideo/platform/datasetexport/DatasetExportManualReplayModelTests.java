package com.medicationvideo.platform.datasetexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DatasetExportManualReplayModelTests {

	@Test
	void allowsQueuedAndRunningAttemptsAndRequeuesTheExport() {
		var queuedExport = DatasetExportRecoveryTestFixtures.runningExport();
		var queuedAttempt = DatasetExportAttempt.queue(queuedExport, 1, UUID.randomUUID(),
				DatasetExportLeaseCapabilityFactory.create().hash(), Instant.EPOCH);
		queuedAttempt.failAfterManualDeadLetter(DatasetExportDeadLetterReason.DATASET_EXPORT_JOB_ACQUIRE_REJECTED,
				Instant.EPOCH.plusSeconds(1));
		queuedExport.requeueAfterManualReplay();

		assertEquals(DatasetExportAttemptStatus.FAILED, queuedAttempt.getStatus());
		assertEquals(DatasetExportStatus.QUEUED, queuedExport.getStatus());
		assertNull(queuedExport.getStartedAt());
		assertNull(queuedExport.getCompletedAt());

		var runningExport = DatasetExportRecoveryTestFixtures.runningExport();
		var runningAttempt = DatasetExportRecoveryTestFixtures.runningAttempt(runningExport, 1);
		var originalStartedAt = runningExport.getStartedAt();
		runningAttempt.failAfterManualDeadLetter(DatasetExportDeadLetterReason.DATASET_EXPORT_JOB_RESULT_REJECTED,
				Instant.EPOCH.plusSeconds(2));
		runningExport.requeueAfterManualReplay();

		assertEquals(DatasetExportAttemptStatus.FAILED, runningAttempt.getStatus());
		assertEquals(DatasetExportStatus.QUEUED, runningExport.getStatus());
		assertEquals(originalStartedAt, runningExport.getStartedAt());
		assertNull(runningExport.getCompletedAt());
	}

	@Test
	void rejectsTerminalAttemptAndExportStates() {
		var export = DatasetExportRecoveryTestFixtures.runningExport();
		var attempt = DatasetExportRecoveryTestFixtures.runningAttempt(export, 1);
		attempt.failAfterManualDeadLetter(DatasetExportDeadLetterReason.DATASET_EXPORT_JOB_ACQUIRE_REJECTED,
				Instant.EPOCH.plusSeconds(1));

		assertThrows(DatasetExportExecutionException.class,
				() -> attempt.failAfterManualDeadLetter(
						DatasetExportDeadLetterReason.DATASET_EXPORT_JOB_ACQUIRE_REJECTED,
						Instant.EPOCH.plusSeconds(2)));

		var requestedExport = requestedExport();
		assertThrows(DatasetExportExecutionException.class, requestedExport::requeueAfterManualReplay);
	}

	@Test
	void derivesWorkerCompatibleUuidV5Identity() {
		var reason = DatasetExportDeadLetterReason.DATASET_EXPORT_JOB_LEASE_REJECTED;
		var bodySha256 = "a".repeat(64);

		assertEquals(UUID.fromString("d785ea17-4e76-5006-a93e-9f8bd9e98cf2"),
				DatasetExportDeadLetterIdentity.from(reason, bodySha256));
	}

	private static DatasetExport requestedExport() {
		var version = DatasetVersion.create(new UUID(0, 1), "Synthetic dataset", "dataset.owner",
				"dataset-version-create-0001", "a".repeat(64), Instant.EPOCH);
		var specification = mock(DatasetBuildSpecification.class);
		when(specification.getDatasetBuildSpecificationId()).thenReturn(new UUID(0, 2));
		when(specification.getExportProfileKey()).thenReturn(DatasetExportProfile.CURRENT_KEY);
		when(specification.getExportProfileDigest()).thenReturn(DatasetExportProfile.CURRENT_DIGEST);
		var snapshot = mock(DatasetBuildSnapshot.class);
		when(snapshot.getDatasetBuildSnapshotId()).thenReturn(new UUID(0, 3));
		when(snapshot.getSnapshotDigest()).thenReturn("c".repeat(64));
		var validation = mock(DatasetVersionValidation.class);
		when(validation.getDatasetVersionValidationId()).thenReturn(new UUID(0, 4));
		return DatasetExport.request(version, specification, snapshot, validation, new UUID(0, 5),
				DatasetExportPurpose.MODEL_TRAINING, Instant.parse("2026-08-10T00:00:00Z"), "export.requester",
				"dataset-export-request-0001", "d".repeat(64), Instant.EPOCH);
	}

}
