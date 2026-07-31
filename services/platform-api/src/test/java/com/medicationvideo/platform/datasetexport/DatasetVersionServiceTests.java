package com.medicationvideo.platform.datasetexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.medicationvideo.platform.assetcapture.CaptureSessionDatasetAdmissionFacts;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.iamproject.ProjectAccessService;

class DatasetVersionServiceTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final UUID SESSION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

	private static final UUID STREAM_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

	private static final UUID ASSET_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");

	private static final UUID REVISION_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");

	private static final String ACTOR_ID = "dataset.admin";

	@Test
	void createsAnAuditedDraftAndSnapshotsOnlyEligibleCurrentEvidence() {
		var access = mock(ProjectAccessService.class);
		var admission = mock(DatasetAdmissionPreviewService.class);
		var versions = mock(DatasetVersionRepository.class);
		var sessions = mock(DatasetMemberSessionRepository.class);
		var streams = mock(DatasetMemberStreamRepository.class);
		var audit = mock(AuditEventRecorder.class);
		when(admission.evaluate(PROJECT_ID, SESSION_ID)).thenReturn(eligibleEvaluation());
		when(versions.saveAndFlush(any(DatasetVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));

		var response = new DatasetVersionService(access, admission, versions, sessions, streams, audit).createDraft(
				PROJECT_ID, ACTOR_ID, "dataset-version-create-0001", "dataset-version-request-0001", "trace-0001",
				new DatasetVersionCreateCommand("训练候选集 01", List.of(SESSION_ID)));

		assertEquals(PROJECT_ID, response.projectId());
		assertEquals("训练候选集 01", response.displayName());
		assertEquals(DatasetVersionStatus.DRAFT, response.status());
		assertEquals(ACTOR_ID, response.createdBy());
		verify(access).lockAndRequireAdministrator(PROJECT_ID, ACTOR_ID);
		verify(versions).saveAndFlush(any(DatasetVersion.class));
		verify(sessions).saveAll(any());
		verify(streams).saveAll(any());
		verify(audit).record(any());
	}

	@Test
	void rejectsASessionThatIsNotCurrentlyEligibleBeforeWritingAnyDraft() {
		var access = mock(ProjectAccessService.class);
		var admission = mock(DatasetAdmissionPreviewService.class);
		var versions = mock(DatasetVersionRepository.class);
		var sessions = mock(DatasetMemberSessionRepository.class);
		var streams = mock(DatasetMemberStreamRepository.class);
		var audit = mock(AuditEventRecorder.class);
		var preview = new DatasetAdmissionPreviewResponse(SESSION_ID, false, "ASSEMBLING", "WARNING", 2, 1,
				List.of("SESSION_ASSEMBLY_INCOMPLETE"), List.of());
		when(admission.evaluate(PROJECT_ID, SESSION_ID)).thenReturn(new DatasetAdmissionEvaluation(
				new CaptureSessionDatasetAdmissionFacts(SESSION_ID, 2, 1, "ASSEMBLING", "WARNING", List.of()), preview,
				List.of()));

		var exception = assertThrows(DatasetVersionException.class,
				() -> new DatasetVersionService(access, admission, versions, sessions, streams, audit).createDraft(
						PROJECT_ID, ACTOR_ID, "dataset-version-create-0002", "dataset-version-request-0002", null,
						new DatasetVersionCreateCommand("训练候选集 02", List.of(SESSION_ID))));

		assertEquals("DATASET_VERSION_SESSION_NOT_ELIGIBLE", exception.getErrorCode());
		verifyNoInteractions(sessions, streams, audit);
	}

	private static DatasetAdmissionEvaluation eligibleEvaluation() {
		var facts = new CaptureSessionDatasetAdmissionFacts(SESSION_ID, 1, 1, "COMPLETE", "PASS",
				List.of(new CaptureSessionDatasetAdmissionFacts.Stream(STREAM_ID, ASSET_ID, 0, "INGESTED", "PASS",
						"INGESTED", false, "a".repeat(64))));
		return new DatasetAdmissionEvaluation(facts,
				new DatasetAdmissionPreviewResponse(SESSION_ID, true, "COMPLETE", "PASS", 1, 1, List.of(), List.of()),
				List.of(new DatasetAdmissionStreamEvidence(STREAM_ID, ASSET_ID, 0, "a".repeat(64), "QUALIFIED", 1,
						REVISION_ID, "b".repeat(64), "c".repeat(64),
						UUID.fromString("50000000-0000-0000-0000-000000000001"),
						UUID.fromString("60000000-0000-0000-0000-000000000001"), "d".repeat(64), "PASS")));
	}

}
