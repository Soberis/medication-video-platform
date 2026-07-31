package com.medicationvideo.platform.datasetexport;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

final class DatasetExportRecoveryTestFixtures {

	static final Instant STARTED_AT = Instant.parse("2026-07-30T08:00:01Z");

	private static final UUID PROJECT_ID = new UUID(0, 1);

	private static final UUID SPECIFICATION_ID = new UUID(0, 2);

	private static final UUID SNAPSHOT_ID = new UUID(0, 3);

	private static final UUID VALIDATION_ID = new UUID(0, 4);

	private static final UUID APPROVAL_REQUEST_ID = new UUID(0, 5);

	private static final UUID APPROVAL_DECISION_ID = new UUID(0, 6);

	private DatasetExportRecoveryTestFixtures() {
	}

	static DatasetExport runningExport() {
		var version = DatasetVersion.create(PROJECT_ID, "Synthetic dataset", "dataset.owner",
				"dataset-version-create-0001", digest('a'), Instant.EPOCH);
		var specification = mock(DatasetBuildSpecification.class);
		when(specification.getDatasetBuildSpecificationId()).thenReturn(SPECIFICATION_ID);
		when(specification.getExportProfileKey()).thenReturn(DatasetExportProfile.CURRENT_KEY);
		when(specification.getExportProfileDigest()).thenReturn(DatasetExportProfile.CURRENT_DIGEST);
		var snapshot = mock(DatasetBuildSnapshot.class);
		when(snapshot.getDatasetBuildSnapshotId()).thenReturn(SNAPSHOT_ID);
		when(snapshot.getSnapshotDigest()).thenReturn(digest('c'));
		var validation = mock(DatasetVersionValidation.class);
		when(validation.getDatasetVersionValidationId()).thenReturn(VALIDATION_ID);
		var export = DatasetExport.request(version, specification, snapshot, validation, APPROVAL_REQUEST_ID,
				DatasetExportPurpose.MODEL_TRAINING, Instant.parse("2026-08-01T00:00:00Z"), "export.requester",
				"dataset-export-request-0001", digest('d'), Instant.EPOCH);
		export.queue(APPROVAL_DECISION_ID, "independent.approver", "dataset-export-approve-0001", digest('e'),
				STARTED_AT.minusSeconds(1));
		return export;
	}

	static DatasetExportAttempt runningAttempt(int attemptNumber) {
		return runningAttempt(runningExport(), attemptNumber);
	}

	static DatasetExportAttempt runningAttempt(DatasetExport export, int attemptNumber) {
		var capability = DatasetExportLeaseCapabilityFactory.create();
		var attempt = DatasetExportAttempt.queue(export, attemptNumber, UUID.randomUUID(), capability.hash(),
				STARTED_AT.minusSeconds(1));
		attempt.acquire("export-worker-1", STARTED_AT, Duration.ofMinutes(1));
		export.start(STARTED_AT);
		return attempt;
	}

	static DatasetExportStoragePort.CreatedUpload attachUpload(DatasetExportAttempt attempt) {
		var upload = new DatasetExportStoragePort.CreatedUpload("synthetic",
				"dataset-exports/synthetic/dataset-export.zip", "upload-1");
		attempt.attachUpload(upload, STARTED_AT.plusSeconds(1));
		return upload;
	}

	private static String digest(char value) {
		return String.valueOf(value).repeat(64);
	}

}
