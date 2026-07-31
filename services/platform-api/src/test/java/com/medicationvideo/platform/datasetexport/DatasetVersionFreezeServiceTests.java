package com.medicationvideo.platform.datasetexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.DatasetFreezeApprovalService;
import com.medicationvideo.platform.governance.DatasetFreezeApprovalSnapshot;
import com.medicationvideo.platform.iamproject.ProjectAccessService;

class DatasetVersionFreezeServiceTests {

	private static final UUID PROJECT_ID = new UUID(0, 1);

	private static final UUID SNAPSHOT_ID = new UUID(0, 2);

	private static final UUID VALIDATION_ID = new UUID(0, 3);

	private static final UUID APPROVAL_REQUEST_ID = new UUID(0, 4);

	private static final String REQUESTER_ID = "validation.admin";

	private static final String APPROVER_ID = "approval.admin";

	@Test
	void doesNotApproveOrFreezeWhenAnyEvidenceChangedAfterValidation() {
		var fixture = fixture(false, List.of(DatasetVersionValidationService.SNAPSHOT_EVIDENCE_CHANGED,
				DatasetVersionValidationService.BUILD_CONTRACT_MISMATCH));

		var exception = assertThrows(DatasetVersionException.class,
				() -> fixture.service()
					.freeze(PROJECT_ID, fixture.version().getDatasetVersionId(), APPROVER_ID, "dataset-freeze-0001",
							"dataset-freeze-request-0001", "trace-0001"));

		assertEquals("DATASET_VERSION_FREEZE_EVIDENCE_CHANGED", exception.getErrorCode());
		assertEquals("SNAPSHOT_EVIDENCE_CHANGED,BUILD_CONTRACT_MISMATCH,SNAPSHOT_VALIDATION_MISMATCH",
				exception.getDetails().get("blocking_reasons"));
		assertEquals(DatasetVersionStatus.VALIDATING, fixture.version().getStatus());
		verify(fixture.approvals(), never()).approve(any(), any(), any(), any(), any());
		verify(fixture.versions(), never()).saveAndFlush(any());
		verifyNoInteractions(fixture.audit());
	}

	private static Fixture fixture(boolean snapshotMatches, List<String> reasons) {
		var access = mock(ProjectAccessService.class);
		var versions = mock(DatasetVersionRepository.class);
		var validations = mock(DatasetVersionValidationRepository.class);
		var validationService = mock(DatasetVersionValidationService.class);
		var approvals = mock(DatasetFreezeApprovalService.class);
		var audit = mock(AuditEventRecorder.class);
		var version = DatasetVersion.create(PROJECT_ID, "Synthetic dataset", REQUESTER_ID,
				"dataset-version-create-0001", digest('a'), Instant.EPOCH);
		version.startBuild(REQUESTER_ID, "dataset-build-start-0001",
				DatasetVersionBuildService.requestFingerprint(version.getDatasetVersionId()), Instant.EPOCH);
		version.beginValidation(SNAPSHOT_ID);

		var validation = mock(DatasetVersionValidation.class);
		var snapshot = mock(DatasetBuildSnapshot.class);
		when(validation.getDatasetVersionValidationId()).thenReturn(VALIDATION_ID);
		when(validation.getOutcome()).thenReturn(DatasetVersionValidationOutcome.PASS);
		when(validation.matchesSnapshot(snapshot)).thenReturn(snapshotMatches);
		when(versions.findByProjectIdAndFrozenByAndFreezeIdempotencyKey(PROJECT_ID, APPROVER_ID, "dataset-freeze-0001"))
			.thenReturn(Optional.empty());
		when(versions.findByProjectIdAndDatasetVersionId(PROJECT_ID, version.getDatasetVersionId()))
			.thenReturn(Optional.of(version));
		when(validations.findByDatasetVersionId(version.getDatasetVersionId())).thenReturn(Optional.of(validation));
		when(approvals.find(PROJECT_ID, version.getDatasetVersionId())).thenReturn(
				Optional.of(new DatasetFreezeApprovalSnapshot(APPROVAL_REQUEST_ID, version.getDatasetVersionId(),
						VALIDATION_ID, "PENDING", REQUESTER_ID, Instant.EPOCH, null, null, null)));
		when(validationService.evaluateCurrent(PROJECT_ID, version, APPROVER_ID))
			.thenReturn(new DatasetVersionValidationEvaluation(snapshot, reasons));

		return new Fixture(
				new DatasetVersionFreezeService(access, versions, validations, validationService, approvals, audit),
				versions, approvals, audit, version);
	}

	private static String digest(char value) {
		return String.valueOf(value).repeat(64);
	}

	private record Fixture(DatasetVersionFreezeService service, DatasetVersionRepository versions,
			DatasetFreezeApprovalService approvals, AuditEventRecorder audit, DatasetVersion version) {
	}

}
