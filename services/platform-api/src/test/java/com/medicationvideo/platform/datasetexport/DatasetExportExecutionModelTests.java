package com.medicationvideo.platform.datasetexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DatasetExportExecutionModelTests {

	private static final UUID PROJECT_ID = new UUID(0, 1);
	private static final UUID SPECIFICATION_ID = new UUID(0, 2);
	private static final UUID SNAPSHOT_ID = new UUID(0, 3);
	private static final UUID VALIDATION_ID = new UUID(0, 4);
	private static final UUID APPROVAL_REQUEST_ID = new UUID(0, 5);
	private static final UUID APPROVAL_DECISION_ID = new UUID(0, 6);

	@Test
	void runsOneLeasedAttemptToVerifiedAvailability() {
		var export = requestedExport();
		var approvedAt = Instant.parse("2026-07-30T08:00:00Z");
		export.queue(
				APPROVAL_DECISION_ID,
				"independent.approver",
				"dataset-export-approve-0001",
				digest('e'),
				approvedAt);

		var capability = DatasetExportLeaseCapabilityFactory.create();
		var attempt = DatasetExportAttempt.queue(
				export,
				1,
				new UUID(0, 7),
				capability.hash(),
				approvedAt);
		attempt.acquire("export-worker-1", approvedAt.plusSeconds(1), Duration.ofHours(2));
		export.start(approvedAt.plusSeconds(1));
		attempt.attachUpload(
				new DatasetExportStoragePort.CreatedUpload("synthetic", "export.zip", "upload-1"),
				approvedAt.plusSeconds(2));

		assertEquals(DatasetExportStatus.RUNNING, export.getStatus());
		assertEquals(DatasetExportAttemptStatus.RUNNING, attempt.getStatus());
		assertTrue(attempt.matchesLeaseHash(
				DatasetExportLeaseCapabilityFactory.hash(capability.token())));
		assertThrows(
				DatasetExportExecutionException.class,
				() -> attempt.heartbeat(
						"another-worker", approvedAt.plusSeconds(3), Duration.ofHours(2)));

		attempt.succeed(approvedAt.plusSeconds(4));
		export.makeAvailable(approvedAt.plusSeconds(4));

		assertEquals(DatasetExportStatus.AVAILABLE, export.getStatus());
		assertEquals(DatasetExportAttemptStatus.SUCCEEDED, attempt.getStatus());
		assertEquals(APPROVAL_DECISION_ID, export.getApprovalDecisionId());
		assertEquals("independent.approver", export.getApprovedBy());
		assertEquals(approvedAt.plusSeconds(4), export.getCompletedAt());
	}

	@Test
	void retriesOnlyBoundedTransientFailures() {
		var policy = new DatasetExportRetryPolicy(
				3, Duration.ofSeconds(5), Duration.ofSeconds(30));
		var transientFailure = new DatasetExportErrorPayload(
				DatasetExportErrorClass.RETRYABLE_TRANSIENT,
				DatasetExportErrorCode.OUTPUT_UPLOAD_FAILED,
				"Synthetic object storage outage.",
				true);
		var inputFailure = new DatasetExportErrorPayload(
				DatasetExportErrorClass.NON_RETRYABLE_INPUT,
				DatasetExportErrorCode.INPUT_DIGEST_MISMATCH,
				"Synthetic digest mismatch.",
				false);

		assertTrue(policy.shouldRetry(transientFailure, 1));
		assertTrue(policy.shouldRetry(transientFailure, 2));
		assertFalse(policy.shouldRetry(transientFailure, 3));
		assertFalse(policy.shouldRetry(inputFailure, 1));
		assertTrue(policy.hasAttemptsRemaining(2));
		assertFalse(policy.hasAttemptsRemaining(3));
		assertEquals(
				Instant.EPOCH.plusSeconds(10),
				policy.nextAvailableAt(3, Instant.EPOCH));
		assertThrows(
				IllegalArgumentException.class,
				() -> policy.nextAvailableAt(4, Instant.EPOCH));
	}

	@Test
	void createsDistinctOpaqueLeaseCapabilities() {
		var first = DatasetExportLeaseCapabilityFactory.create();
		var second = DatasetExportLeaseCapabilityFactory.create();

		assertEquals(43, first.token().length());
		assertEquals(64, first.hash().length());
		assertNotEquals(first.token(), second.token());
		assertNotEquals(first.hash(), second.hash());
		assertFalse(first.hash().contains(first.token()));
	}

	@Test
	void derivesOnlyApprovedRawVideoPaths() {
		var sessionId = new UUID(0, 10);
		var assetId = new UUID(0, 11);

		assertEquals(
				"videos/raw/00000000-0000-0000-0000-00000000000a/"
						+ "camera-2-00000000-0000-0000-0000-00000000000b.mp4",
				DatasetExportPackagePaths.videoPath(sessionId, 2, assetId, "video/mp4"));
		assertTrue(DatasetExportPackagePaths
				.videoPath(sessionId, 0, assetId, "application/octet-stream")
				.endsWith(".bin"));
	}

	private static DatasetExport requestedExport() {
		var version = DatasetVersion.create(
				PROJECT_ID,
				"Synthetic dataset",
				"dataset.owner",
				"dataset-version-create-0001",
				digest('a'),
				Instant.EPOCH);
		var specification = mock(DatasetBuildSpecification.class);
		when(specification.getDatasetBuildSpecificationId()).thenReturn(SPECIFICATION_ID);
		when(specification.getExportProfileKey()).thenReturn(DatasetExportProfile.CURRENT_KEY);
		when(specification.getExportProfileDigest()).thenReturn(DatasetExportProfile.CURRENT_DIGEST);
		var snapshot = mock(DatasetBuildSnapshot.class);
		when(snapshot.getDatasetBuildSnapshotId()).thenReturn(SNAPSHOT_ID);
		when(snapshot.getSnapshotDigest()).thenReturn(digest('c'));
		var validation = mock(DatasetVersionValidation.class);
		when(validation.getDatasetVersionValidationId()).thenReturn(VALIDATION_ID);
		return DatasetExport.request(
				version,
				specification,
				snapshot,
				validation,
				APPROVAL_REQUEST_ID,
				DatasetExportPurpose.MODEL_TRAINING,
				Instant.parse("2026-08-01T00:00:00Z"),
				"export.requester",
				"dataset-export-request-0001",
				digest('d'),
				Instant.EPOCH);
	}

	private static String digest(char value) {
		return String.valueOf(value).repeat(64);
	}
}
