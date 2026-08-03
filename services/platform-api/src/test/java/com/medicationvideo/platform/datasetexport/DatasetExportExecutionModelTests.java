package com.medicationvideo.platform.datasetexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.medicationvideo.platform.governance.AuditEventCommand;

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
		export.queue(APPROVAL_DECISION_ID, "independent.approver", "dataset-export-approve-0001", digest('e'),
				approvedAt);

		var capability = DatasetExportLeaseCapabilityFactory.create();
		var attempt = DatasetExportAttempt.queue(export, 1, new UUID(0, 7), capability.hash(), approvedAt);
		attempt.acquire("export-worker-1", approvedAt.plusSeconds(1), Duration.ofHours(2));
		export.start(approvedAt.plusSeconds(1));
		attempt.attachUpload(new DatasetExportStoragePort.CreatedUpload("synthetic", "export.zip", "upload-1"),
				approvedAt.plusSeconds(2));

		assertEquals(DatasetExportStatus.RUNNING, export.getStatus());
		assertEquals(DatasetExportAttemptStatus.RUNNING, attempt.getStatus());
		assertTrue(attempt.matchesLeaseHash(DatasetExportLeaseCapabilityFactory.hash(capability.token())));
		assertThrows(DatasetExportExecutionException.class,
				() -> attempt.heartbeat("another-worker", approvedAt.plusSeconds(3), Duration.ofHours(2)));

		attempt.succeed(approvedAt.plusSeconds(4));
		export.makeAvailable(approvedAt.plusSeconds(4));

		assertEquals(DatasetExportStatus.AVAILABLE, export.getStatus());
		assertEquals(DatasetExportAttemptStatus.SUCCEEDED, attempt.getStatus());
		assertEquals(APPROVAL_DECISION_ID, export.getApprovalDecisionId());
		assertEquals("independent.approver", export.getApprovedBy());
		assertEquals(approvedAt.plusSeconds(4), export.getCompletedAt());
	}

	@Test
	void redeliveredSuccessfulResultUsesPersistedReceiptWithoutRepeatingSideEffects() {
		var export = requestedExport();
		var startedAt = Instant.now().minusSeconds(10);
		export.queue(APPROVAL_DECISION_ID, "independent.approver", "dataset-export-approve-0001", digest('e'),
				startedAt);
		var capability = DatasetExportLeaseCapabilityFactory.create();
		var messageId = new UUID(0, 7);
		var attempt = DatasetExportAttempt.queue(export, 1, messageId, capability.hash(), startedAt);
		attempt.acquire("export-worker-1", startedAt, Duration.ofHours(2));
		export.start(startedAt);
		var upload = new DatasetExportStoragePort.CreatedUpload("synthetic", "export.zip", "upload-1");
		attempt.attachUpload(upload, startedAt);

		var softwareDigest = "sha256:" + digest('a');
		var packageSha256 = digest('b');
		var files = List.of(new DatasetExportFileResult("README.md", digest('f'), 1),
				new DatasetExportFileResult("label_schema.json", digest('f'), 1),
				new DatasetExportFileResult("manifest.jsonl", digest('f'), 1),
				new DatasetExportFileResult("action_segments.jsonl", digest('f'), 1),
				new DatasetExportFileResult("splits/pilot.txt", digest('f'), 1),
				new DatasetExportFileResult("splits/holdout.txt", digest('f'), 1),
				new DatasetExportFileResult("SHA256SUMS", digest('f'), 1));
		var request = new DatasetExportResultRequest(DatasetExportResultRequest.SCHEMA_VERSION, messageId,
				export.getDatasetExportId(), 1, capability.token(), DatasetExportResultStatus.SUCCEEDED,
				export.getSnapshotDigest(), export.getExportProfileDigest(), packageSha256, 1L, files, softwareDigest,
				startedAt, startedAt.plusSeconds(1), null);
		var receipt = new DatasetExportInboxReceipt(messageId, export.getDatasetExportId(), 1, request.fingerprint(),
				DatasetExportResultStatus.SUCCEEDED.name(), Instant.parse("2026-08-03T00:00:00Z"));
		var part = new DatasetExportStoragePort.StoredPart(1, 1, "etag-1");
		var completed = new DatasetExportStoragePort.CompletedObject(1, "etag-1");

		var exportRepository = mock(DatasetExportRepository.class);
		var attemptRepository = mock(DatasetExportAttemptRepository.class);
		var artifactRepository = mock(ExportArtifactRepository.class);
		var specificationRepository = mock(DatasetBuildSpecificationRepository.class);
		var memberRepository = mock(DatasetMemberSessionRepository.class);
		var streamRepository = mock(DatasetMemberStreamRepository.class);
		var assignmentRepository = mock(DatasetSplitAssignmentRepository.class);
		var assetReadService = mock(com.medicationvideo.platform.assetcapture.ClipAssetExportReadService.class);
		var annotationQueryService = mock(
				com.medicationvideo.platform.annotationreview.AnnotationExportQueryService.class);
		var storage = mock(DatasetExportStoragePort.class);
		var outputCleanup = mock(DatasetExportOutputCleanup.class);
		var inbox = mock(DatasetExportResultInboxPort.class);
		var attemptQueue = mock(DatasetExportAttemptQueueService.class);
		var retryPolicy = mock(DatasetExportRetryPolicy.class);
		var auditRecorder = mock(com.medicationvideo.platform.governance.AuditEventRecorder.class);

		when(exportRepository.findForUpdate(export.getDatasetExportId())).thenReturn(Optional.of(export));
		when(attemptRepository.findForUpdate(export.getDatasetExportId(), 1)).thenReturn(Optional.of(attempt));
		when(streamRepository
			.findByDatasetVersionIdOrderByDatasetMemberSessionIdAscCameraIndexAsc(export.getDatasetVersionId()))
			.thenReturn(List.of());
		when(memberRepository.findByDatasetVersionIdOrderByCandidateIndexAsc(export.getDatasetVersionId()))
			.thenReturn(List.of());
		when(assetReadService.describe(export.getProjectId(), List.of())).thenReturn(List.of());
		when(storage.findCompletedObject(upload)).thenReturn(Optional.empty());
		when(storage.listParts(upload)).thenReturn(List.of(part));
		when(storage.complete(upload, List.of(part))).thenReturn(completed);
		when(storage.calculateSha256(upload)).thenReturn(packageSha256);
		when(artifactRepository.saveAndFlush(any(ExportArtifact.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));
		when(inbox.find(messageId)).thenReturn(Optional.empty(), Optional.of(receipt));
		when(inbox.record(eq(messageId), eq(export.getDatasetExportId()), eq(1), eq(request.fingerprint()),
				eq(DatasetExportResultStatus.SUCCEEDED.name()), any(Instant.class)))
			.thenReturn(receipt);

		var service = new DatasetExportExecutionService(exportRepository, attemptRepository, artifactRepository,
				specificationRepository, memberRepository, streamRepository, assignmentRepository, assetReadService,
				annotationQueryService, storage, outputCleanup, inbox, attemptQueue, retryPolicy, auditRecorder,
				Duration.ofHours(2), Duration.ofMinutes(5), Duration.ofHours(2), Duration.ofMinutes(15),
				Duration.ofMinutes(5), softwareDigest);

		var first = service.acceptResult(export.getDatasetExportId(), 1, capability.token(), request);
		var second = service.acceptResult(export.getDatasetExportId(), 1, capability.token(), request);

		assertEquals(first, second);
		assertEquals(receipt.messageId(), first.messageId());
		assertEquals(receipt.exportId(), first.exportId());
		assertEquals(receipt.attempt(), first.attempt());
		assertEquals(DatasetExportResultStatus.SUCCEEDED, first.status());
		assertEquals(DatasetExportStatus.AVAILABLE, first.exportStatus());
		assertEquals(receipt.processedAt(), first.processedAt());
		assertEquals(DatasetExportStatus.AVAILABLE, export.getStatus());
		assertEquals(DatasetExportAttemptStatus.SUCCEEDED, attempt.getStatus());

		var artifact = ArgumentCaptor.forClass(ExportArtifact.class);
		verify(artifactRepository, times(1)).saveAndFlush(artifact.capture());
		assertEquals(export.getDatasetExportId(), artifact.getValue().getDatasetExportId());
		assertEquals(1, artifact.getValue().getSizeBytes());
		assertEquals(packageSha256, artifact.getValue().getSha256());
		verify(inbox, times(2)).find(messageId);
		verify(inbox, times(1)).record(eq(messageId), eq(export.getDatasetExportId()), eq(1), eq(request.fingerprint()),
				eq(DatasetExportResultStatus.SUCCEEDED.name()), any(Instant.class));
		var audit = ArgumentCaptor.forClass(AuditEventCommand.class);
		verify(auditRecorder, times(1)).record(audit.capture());
		assertEquals("DATASET_EXPORT_AVAILABLE", audit.getValue().action());
		verify(storage, times(1)).findCompletedObject(upload);
		verify(storage, times(1)).listParts(upload);
		verify(storage, times(1)).complete(upload, List.of(part));
		verify(storage, times(1)).calculateSha256(upload);
		verifyNoInteractions(outputCleanup, attemptQueue, retryPolicy);
	}

	@Test
	void retriesOnlyBoundedTransientFailures() {
		var policy = new DatasetExportRetryPolicy(3, Duration.ofSeconds(5), Duration.ofSeconds(30));
		var transientFailure = new DatasetExportErrorPayload(DatasetExportErrorClass.RETRYABLE_TRANSIENT,
				DatasetExportErrorCode.OUTPUT_UPLOAD_FAILED, "Synthetic object storage outage.", true);
		var inputFailure = new DatasetExportErrorPayload(DatasetExportErrorClass.NON_RETRYABLE_INPUT,
				DatasetExportErrorCode.INPUT_DIGEST_MISMATCH, "Synthetic digest mismatch.", false);

		assertTrue(policy.shouldRetry(transientFailure, 1));
		assertTrue(policy.shouldRetry(transientFailure, 2));
		assertFalse(policy.shouldRetry(transientFailure, 3));
		assertFalse(policy.shouldRetry(inputFailure, 1));
		assertTrue(policy.hasAttemptsRemaining(2));
		assertFalse(policy.hasAttemptsRemaining(3));
		assertEquals(Instant.EPOCH.plusSeconds(10), policy.nextAvailableAt(3, Instant.EPOCH));
		assertThrows(IllegalArgumentException.class, () -> policy.nextAvailableAt(4, Instant.EPOCH));
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
		assertTrue(DatasetExportPackagePaths.videoPath(sessionId, 0, assetId, "application/octet-stream")
			.endsWith(".bin"));
	}

	private static DatasetExport requestedExport() {
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
		return DatasetExport.request(version, specification, snapshot, validation, APPROVAL_REQUEST_ID,
				DatasetExportPurpose.MODEL_TRAINING, Instant.parse("2026-08-01T00:00:00Z"), "export.requester",
				"dataset-export-request-0001", digest('d'), Instant.EPOCH);
	}

	private static String digest(char value) {
		return String.valueOf(value).repeat(64);
	}

}
