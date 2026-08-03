package com.medicationvideo.platform.datasetexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.iamproject.ProjectAccessService;

class DatasetExportManualReplayServiceTests {

	private static final UUID PROJECT_ID = new UUID(0, 1);

	private static final String ACTOR_ID = "export.admin";

	private static final String BODY_SHA256 = "d".repeat(64);

	private static final String IDEMPOTENCY_KEY = "dataset-export-replay-0001";

	@Test
	void stopsTheCurrentAttemptCleansOutputAndQueuesAFreshAttemptWithAudit() {
		var fixture = fixture();
		var upload = DatasetExportRecoveryTestFixtures.attachUpload(fixture.attempt());
		var request = request(fixture.attempt(), DatasetExportDeadLetterReason.DATASET_EXPORT_JOB_ACQUIRE_REJECTED,
				BODY_SHA256);
		var response = fixture.service()
			.replay(PROJECT_ID, ACTOR_ID, fixture.export().getDatasetExportId(), IDEMPOTENCY_KEY, "replay-request-0001",
					"trace-0001", request);

		assertEquals(DatasetExportAttemptStatus.FAILED, fixture.attempt().getStatus());
		assertEquals("NON_RETRYABLE_POLICY", fixture.attempt().getErrorClass());
		assertEquals(request.deadLetterReason().name(), fixture.attempt().getErrorCode());
		assertEquals("The dataset export attempt was manually stopped after a retained dead-letter diagnostic.",
				fixture.attempt().getSafeErrorMessage());
		assertEquals(DatasetExportStatus.QUEUED, fixture.export().getStatus());
		assertEquals(2, response.getNewAttempt());
		assertEquals(request.deadLetterId(), response.getDeadLetterId());
		assertEquals(upload, fixture.attachedUpload());
		verify(fixture.projectAccess()).lockAndRequireAdministrator(PROJECT_ID, ACTOR_ID);
		assertNotEquals(fixture.attempt().getMessageId(), fixture.newAttempt().getMessageId());
		verify(fixture.outputCleanup()).discard(fixture.attempt());
		verify(fixture.attemptQueue()).enqueue(eq(fixture.export()), eq(2), any(Instant.class), any(Instant.class));
		verify(fixture.replayRepository()).saveAndFlush(any(DatasetExportManualReplay.class));
		var audit = ArgumentCaptor.forClass(AuditEventCommand.class);
		verify(fixture.auditRecorder()).record(audit.capture());
		assertEquals("DATASET_EXPORT_MANUAL_REPLAY", audit.getValue().action());
		assertEquals(request.deadLetterId().toString(), audit.getValue().details().get("dead_letter_id"));
		assertEquals(request.changeReference(), audit.getValue().details().get("change_reference"));
		assertEquals("ROLE_ADMIN", audit.getValue().authorizationReference());
		assertEquals("export.admin", audit.getValue().actorId());
	}

	@Test
	void rejectsContractInvalidDiagnosticsBeforeChangingExportState() {
		var fixture = fixture();
		var request = request(fixture.attempt(), DatasetExportDeadLetterReason.DATASET_EXPORT_JOB_CONTRACT_INVALID,
				BODY_SHA256);

		var exception = assertThrows(DatasetVersionException.class,
				() -> fixture.service()
					.replay(PROJECT_ID, ACTOR_ID, fixture.export().getDatasetExportId(), IDEMPOTENCY_KEY, "request",
							null, request));

		assertEquals("DATASET_EXPORT_REPLAY_DIAGNOSTIC_UNREPLAYABLE", exception.getErrorCode());
		assertEquals(DatasetExportAttemptStatus.RUNNING, fixture.attempt().getStatus());
		assertEquals(DatasetExportStatus.RUNNING, fixture.export().getStatus());
		verifyNoInteractions(fixture.outputCleanup(), fixture.attemptQueue(), fixture.auditRecorder());
	}

	@Test
	void rejectsADeadLetterIdentityMismatchWithoutChangingStateOrDispatching() {
		var fixture = fixture();
		var validReason = DatasetExportDeadLetterReason.DATASET_EXPORT_JOB_LEASE_REJECTED;
		var request = new DatasetExportManualReplayRequest(UUID.randomUUID(), fixture.attempt().getMessageId(),
				fixture.attempt().getAttempt(), validReason, BODY_SHA256,
				DatasetExportManualReplayReason.OTHER_APPROVED, "change-ref-0001");

		var exception = assertThrows(DatasetVersionException.class,
				() -> fixture.service()
					.replay(PROJECT_ID, ACTOR_ID, fixture.export().getDatasetExportId(), IDEMPOTENCY_KEY, "request",
							null, request));

		assertEquals("DATASET_EXPORT_REPLAY_DIAGNOSTIC_MISMATCH", exception.getErrorCode());
		assertEquals(DatasetExportAttemptStatus.RUNNING, fixture.attempt().getStatus());
		assertEquals(DatasetExportStatus.RUNNING, fixture.export().getStatus());
		verifyNoInteractions(fixture.outputCleanup(), fixture.attemptQueue(), fixture.auditRecorder());
	}

	@Test
	void rejectsADeadLetterThatWasAlreadyUsedWithoutChangingStateOrDispatching() {
		var fixture = fixture();
		var request = request(fixture.attempt(), DatasetExportDeadLetterReason.DATASET_EXPORT_JOB_LEASE_REJECTED,
				BODY_SHA256);
		var existing = DatasetExportManualReplay.create(PROJECT_ID, fixture.export().getDatasetExportId(), request, 1,
				"previous.admin", "previous-replay-0001", "0".repeat(64), Instant.EPOCH);
		when(fixture.replayRepository().findByDeadLetterId(request.deadLetterId())).thenReturn(Optional.of(existing));

		var exception = assertThrows(DatasetVersionException.class,
				() -> fixture.service()
					.replay(PROJECT_ID, ACTOR_ID, fixture.export().getDatasetExportId(), IDEMPOTENCY_KEY, "request",
							null, request));

		assertEquals("DATASET_EXPORT_REPLAY_DEAD_LETTER_REUSED", exception.getErrorCode());
		assertEquals(DatasetExportAttemptStatus.RUNNING, fixture.attempt().getStatus());
		assertEquals(DatasetExportStatus.RUNNING, fixture.export().getStatus());
		verifyNoInteractions(fixture.outputCleanup(), fixture.attemptQueue(), fixture.auditRecorder());
	}

	@Test
	void rejectsAnExportFromAnotherProjectAsNotFound() {
		var fixture = fixture();
		var request = request(fixture.attempt(), DatasetExportDeadLetterReason.DATASET_EXPORT_JOB_LEASE_REJECTED,
				BODY_SHA256);
		var otherProjectId = new UUID(0, 99);

		var exception = assertThrows(DatasetVersionException.class,
				() -> fixture.service()
					.replay(otherProjectId, ACTOR_ID, fixture.export().getDatasetExportId(), IDEMPOTENCY_KEY, "request",
							null, request));

		assertEquals("DATASET_EXPORT_NOT_FOUND", exception.getErrorCode());
		assertEquals(DatasetExportAttemptStatus.RUNNING, fixture.attempt().getStatus());
		assertEquals(DatasetExportStatus.RUNNING, fixture.export().getStatus());
		verifyNoInteractions(fixture.outputCleanup(), fixture.attemptQueue(), fixture.auditRecorder());
	}

	@Test
	void rejectsACompletedExportStateBeforeCleanupOrDispatch() {
		var fixture = fixture();
		fixture.export().makeAvailable(Instant.EPOCH.plusSeconds(3));
		var request = request(fixture.attempt(), DatasetExportDeadLetterReason.DATASET_EXPORT_JOB_LEASE_REJECTED,
				BODY_SHA256);

		var exception = assertThrows(DatasetVersionException.class,
				() -> fixture.service()
					.replay(PROJECT_ID, ACTOR_ID, fixture.export().getDatasetExportId(), IDEMPOTENCY_KEY, "request",
							null, request));

		assertEquals("DATASET_EXPORT_REPLAY_STATE_INVALID", exception.getErrorCode());
		assertEquals(DatasetExportStatus.AVAILABLE, fixture.export().getStatus());
		assertEquals(DatasetExportAttemptStatus.RUNNING, fixture.attempt().getStatus());
		verifyNoInteractions(fixture.outputCleanup(), fixture.attemptQueue(), fixture.auditRecorder());
	}

	@Test
	void rejectsACompletedAttemptStateBeforeCleanupOrDispatch() {
		var fixture = fixture();
		fixture.attempt().succeed(Instant.EPOCH.plusSeconds(3));
		var request = request(fixture.attempt(), DatasetExportDeadLetterReason.DATASET_EXPORT_JOB_LEASE_REJECTED,
				BODY_SHA256);

		var exception = assertThrows(DatasetVersionException.class,
				() -> fixture.service()
					.replay(PROJECT_ID, ACTOR_ID, fixture.export().getDatasetExportId(), IDEMPOTENCY_KEY, "request",
							null, request));

		assertEquals("DATASET_EXPORT_REPLAY_STATE_INVALID", exception.getErrorCode());
		assertEquals(DatasetExportStatus.RUNNING, fixture.export().getStatus());
		assertEquals(DatasetExportAttemptStatus.SUCCEEDED, fixture.attempt().getStatus());
		verifyNoInteractions(fixture.outputCleanup(), fixture.attemptQueue(), fixture.auditRecorder());
	}

	@Test
	void rejectsAStaleMessageAndAttemptWithoutMutation() {
		var fixture = fixture();
		var request = new DatasetExportManualReplayRequest(
				DatasetExportDeadLetterIdentity.from(DatasetExportDeadLetterReason.DATASET_EXPORT_JOB_LEASE_REJECTED,
						BODY_SHA256),
				UUID.randomUUID(), fixture.attempt().getAttempt(),
				DatasetExportDeadLetterReason.DATASET_EXPORT_JOB_LEASE_REJECTED, BODY_SHA256,
				DatasetExportManualReplayReason.OTHER_APPROVED, "change-ref-0001");

		var exception = assertThrows(DatasetVersionException.class,
				() -> fixture.service()
					.replay(PROJECT_ID, ACTOR_ID, fixture.export().getDatasetExportId(), IDEMPOTENCY_KEY, "request",
							null, request));

		assertEquals("DATASET_EXPORT_REPLAY_STALE", exception.getErrorCode());
		assertEquals(DatasetExportAttemptStatus.RUNNING, fixture.attempt().getStatus());
		verify(fixture.attemptQueue(), never()).enqueue(any(), anyInt(), any(), any());
		verify(fixture.replayRepository(), never()).saveAndFlush(any());
	}

	@Test
	void rejectsCleanupFailureBeforeCreatingReplayOrOutbox() {
		var fixture = fixture();
		var request = request(fixture.attempt(), DatasetExportDeadLetterReason.DATASET_EXPORT_JOB_RESULT_REJECTED,
				BODY_SHA256);
		doThrow(new IllegalStateException("synthetic storage failure")).when(fixture.outputCleanup())
			.discard(fixture.attempt());

		var exception = assertThrows(DatasetVersionException.class,
				() -> fixture.service()
					.replay(PROJECT_ID, ACTOR_ID, fixture.export().getDatasetExportId(), IDEMPOTENCY_KEY, "request",
							null, request));

		assertEquals("DATASET_EXPORT_REPLAY_CLEANUP_FAILED", exception.getErrorCode());
		assertEquals(DatasetExportAttemptStatus.RUNNING, fixture.attempt().getStatus());
		assertEquals(DatasetExportStatus.RUNNING, fixture.export().getStatus());
		verify(fixture.attemptQueue(), never()).enqueue(any(), anyInt(), any(), any());
		verify(fixture.replayRepository(), never()).saveAndFlush(any());
		verifyNoInteractions(fixture.auditRecorder());
	}

	@Test
	void returnsTheOriginalReplayForAnIdenticalIdempotencyCommand() {
		var fixture = fixture();
		var request = request(fixture.attempt(), DatasetExportDeadLetterReason.DATASET_EXPORT_JOB_PROFILE_UNSUPPORTED,
				BODY_SHA256);
		var saved = new DatasetExportManualReplay[1];
		when(fixture.replayRepository().findByProjectIdAndIdempotencyKey(PROJECT_ID, IDEMPOTENCY_KEY))
			.thenReturn(Optional.empty())
			.thenAnswer(ignored -> Optional.of(saved[0]));
		when(fixture.replayRepository().saveAndFlush(any(DatasetExportManualReplay.class))).thenAnswer(invocation -> {
			saved[0] = invocation.getArgument(0);
			return saved[0];
		});

		var first = fixture.service()
			.replay(PROJECT_ID, ACTOR_ID, fixture.export().getDatasetExportId(), IDEMPOTENCY_KEY, "request-1", null,
					request);
		var second = fixture.service()
			.replay(PROJECT_ID, ACTOR_ID, fixture.export().getDatasetExportId(), IDEMPOTENCY_KEY, "request-2", null,
					request);

		assertEquals(first.getReplayId(), second.getReplayId());
		verify(fixture.attemptQueue()).enqueue(eq(fixture.export()), eq(2), any(Instant.class), any(Instant.class));
	}

	@Test
	void rejectsAnIdempotencyConflictAndTheManualReplayLimit() {
		var fixture = fixture();
		var request = request(fixture.attempt(), DatasetExportDeadLetterReason.DATASET_EXPORT_JOB_ACQUIRE_REJECTED,
				BODY_SHA256);
		var existing = DatasetExportManualReplay.create(PROJECT_ID, fixture.export().getDatasetExportId(), request, 2,
				ACTOR_ID, IDEMPOTENCY_KEY, "0".repeat(64), Instant.EPOCH);
		when(fixture.replayRepository().findByProjectIdAndIdempotencyKey(PROJECT_ID, IDEMPOTENCY_KEY))
			.thenReturn(Optional.of(existing));

		var idempotencyException = assertThrows(DatasetVersionException.class, () -> fixture.service()
			.replay(PROJECT_ID, ACTOR_ID, fixture.export().getDatasetExportId(), IDEMPOTENCY_KEY, "request", null,
					new DatasetExportManualReplayRequest(request.deadLetterId(), request.sourceMessageId(),
							request.sourceAttempt(), request.deadLetterReason(), request.bodySha256(),
							request.operatorReasonCode(), "different-change-0001")));
		assertEquals("DATASET_EXPORT_REPLAY_IDEMPOTENCY_KEY_REUSED", idempotencyException.getErrorCode());

		var limitedFixture = fixture();
		when(limitedFixture.replayRepository().countByDatasetExportId(limitedFixture.export().getDatasetExportId()))
			.thenReturn(2L);
		var limitException = assertThrows(DatasetVersionException.class,
				() -> limitedFixture.service()
					.replay(PROJECT_ID, ACTOR_ID, limitedFixture.export().getDatasetExportId(), IDEMPOTENCY_KEY,
							"request", null, request(limitedFixture.attempt(),
									DatasetExportDeadLetterReason.DATASET_EXPORT_JOB_ACQUIRE_REJECTED, BODY_SHA256)));
		assertEquals("DATASET_EXPORT_REPLAY_LIMIT_REACHED", limitException.getErrorCode());
	}

	private static DatasetExportManualReplayRequest request(DatasetExportAttempt attempt,
			DatasetExportDeadLetterReason reason, String bodySha256) {
		return new DatasetExportManualReplayRequest(DatasetExportDeadLetterIdentity.from(reason, bodySha256),
				attempt.getMessageId(), attempt.getAttempt(), reason, bodySha256,
				DatasetExportManualReplayReason.CONFIGURATION_FIXED, "change-ref-0001");
	}

	private static Fixture fixture() {
		var export = DatasetExportRecoveryTestFixtures.runningExport();
		var attempt = DatasetExportRecoveryTestFixtures.runningAttempt(export, 1);
		var access = mock(ProjectAccessService.class);
		var exportRepository = mock(DatasetExportRepository.class);
		var attemptRepository = mock(DatasetExportAttemptRepository.class);
		var replayRepository = mock(DatasetExportManualReplayRepository.class);
		var attemptQueue = mock(DatasetExportAttemptQueueService.class);
		var outputCleanup = mock(DatasetExportOutputCleanup.class);
		var auditRecorder = mock(AuditEventRecorder.class);
		var newCapability = DatasetExportLeaseCapabilityFactory.create();
		var newMessageId = UUID.randomUUID();
		var newAttempt = DatasetExportAttempt.queue(export, 2, newMessageId, newCapability.hash(), Instant.EPOCH);

		when(exportRepository.findForUpdate(export.getDatasetExportId())).thenReturn(Optional.of(export));
		when(attemptRepository.findCurrentForUpdate(export.getDatasetExportId())).thenReturn(Optional.of(attempt));
		when(replayRepository.findByProjectIdAndIdempotencyKey(any(), anyString())).thenReturn(Optional.empty());
		when(replayRepository.findByDeadLetterId(any())).thenReturn(Optional.empty());
		when(replayRepository.countByDatasetExportId(export.getDatasetExportId())).thenReturn(0L);
		when(exportRepository.saveAndFlush(any(DatasetExport.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));
		when(attemptRepository.saveAndFlush(any(DatasetExportAttempt.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));
		when(attemptQueue.enqueue(any(), anyInt(), any(), any())).thenReturn(newAttempt);
		when(replayRepository.saveAndFlush(any(DatasetExportManualReplay.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));

		var service = new DatasetExportManualReplayService(access, exportRepository, attemptRepository,
				replayRepository, attemptQueue, outputCleanup, auditRecorder, 2);
		return new Fixture(service, export, attempt, replayRepository, attemptQueue, outputCleanup, auditRecorder,
				access, newAttempt);
	}

	private record Fixture(DatasetExportManualReplayService service, DatasetExport export, DatasetExportAttempt attempt,
			DatasetExportManualReplayRepository replayRepository, DatasetExportAttemptQueueService attemptQueue,
			DatasetExportOutputCleanup outputCleanup, AuditEventRecorder auditRecorder,
			ProjectAccessService projectAccess, DatasetExportAttempt newAttempt) {

		DatasetExportStoragePort.CreatedUpload attachedUpload() {
			return attempt.getUpload();
		}

	}

}
