package com.medicationvideo.platform.datasetexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.medicationvideo.platform.governance.DatasetExportApprovalService;
import com.medicationvideo.platform.governance.DatasetExportApprovalSnapshot;
import com.medicationvideo.platform.iamproject.ProjectAccessService;
import com.medicationvideo.platform.iamproject.ProjectRole;

class DatasetExportApprovalReadinessServiceTests {

	private static final UUID PROJECT_ID = new UUID(0, 1);
	private static final UUID SPECIFICATION_ID = new UUID(0, 2);
	private static final UUID SNAPSHOT_ID = new UUID(0, 3);
	private static final UUID VALIDATION_ID = new UUID(0, 4);
	private static final UUID FREEZE_APPROVAL_REQUEST_ID = new UUID(0, 5);
	private static final UUID FREEZE_APPROVAL_DECISION_ID = new UUID(0, 6);
	private static final UUID EXPORT_APPROVAL_REQUEST_ID = new UUID(0, 7);
	private static final String REQUESTER_ID = "export.requester";
	private static final String APPROVER_ID = "export.approver";

	@Test
	void reportsReadyForAnotherCurrentAdministrator() {
		var fixture = fixture(APPROVER_ID, ProjectRole.ADMIN, Instant.now().plusSeconds(3600), List.of());

		var response = fixture.service().get(PROJECT_ID, fixture.version().getDatasetVersionId(), APPROVER_ID);

		assertTrue(response.readyForApproval());
		assertTrue(response.blockingReasons().isEmpty());
		assertEquals(fixture.export().getDatasetExportId(), response.datasetExportId());
		assertEquals(EXPORT_APPROVAL_REQUEST_ID, response.approvalRequestId());
	}

	@Test
	void keepsTheRequesterAndOrdinaryMembersOutOfTheApprovalRole() {
		var requester = fixture(REQUESTER_ID, ProjectRole.ADMIN, Instant.now().plusSeconds(3600), List.of());
		var requesterResponse = requester.service()
				.get(PROJECT_ID, requester.version().getDatasetVersionId(), REQUESTER_ID);

		assertFalse(requesterResponse.readyForApproval());
		assertEquals(
				List.of(DatasetExportApprovalReadinessService.SELF_APPROVAL_FORBIDDEN),
				requesterResponse.blockingReasons());

		var member = fixture("project.member", ProjectRole.MEMBER, Instant.now().plusSeconds(3600), List.of());
		var memberResponse = member.service()
				.get(PROJECT_ID, member.version().getDatasetVersionId(), "project.member");

		assertFalse(memberResponse.readyForApproval());
		assertEquals(
				List.of(DatasetExportApprovalReadinessService.APPROVER_ADMIN_REQUIRED),
				memberResponse.blockingReasons());
	}

	@Test
	void reportsExpiredPurposeAuthorization() {
		var fixture = fixture(APPROVER_ID, ProjectRole.ADMIN, Instant.now().minusSeconds(1), List.of());

		var response = fixture.service().get(PROJECT_ID, fixture.version().getDatasetVersionId(), APPROVER_ID);

		assertFalse(response.readyForApproval());
		assertEquals(
				List.of(DatasetExportApprovalReadinessService.EXPORT_AUTHORIZATION_EXPIRED),
				response.blockingReasons());
	}

	@Test
	void carriesForwardCurrentFrozenEvidenceBlockers() {
		var fixture = fixture(
				APPROVER_ID,
				ProjectRole.ADMIN,
				Instant.now().plusSeconds(3600),
				List.of(DatasetVersionValidationService.SNAPSHOT_EVIDENCE_CHANGED));

		var response = fixture.service().get(PROJECT_ID, fixture.version().getDatasetVersionId(), APPROVER_ID);

		assertFalse(response.readyForApproval());
		assertEquals(
				List.of(DatasetVersionValidationService.SNAPSHOT_EVIDENCE_CHANGED),
				response.blockingReasons());
	}

	@Test
	void blocksLegacyProfileExecutionUntilItsRealProducersExist() {
		var fixture = fixture(
				APPROVER_ID,
				ProjectRole.ADMIN,
				Instant.now().plusSeconds(3600),
				List.of(),
				DatasetExportProfile.LEGACY_V1_KEY,
				DatasetExportProfile.LEGACY_V1_DIGEST);

		var response = fixture.service().get(
				PROJECT_ID, fixture.version().getDatasetVersionId(), APPROVER_ID);

		assertFalse(response.readyForApproval());
		assertEquals(
				List.of(DatasetExportApprovalReadinessService.EXPORT_PROFILE_NOT_EXECUTABLE),
				response.blockingReasons());
	}

	private static Fixture fixture(
			String actorId,
			ProjectRole role,
			Instant expiresAt,
			List<String> currentEvidenceBlockers) {
		return fixture(
				actorId,
				role,
				expiresAt,
				currentEvidenceBlockers,
				DatasetExportProfile.CURRENT_KEY,
				DatasetExportProfile.CURRENT_DIGEST);
	}

	private static Fixture fixture(
			String actorId,
			ProjectRole role,
			Instant expiresAt,
			List<String> currentEvidenceBlockers,
			String exportProfileKey,
			String exportProfileDigest) {
		var access = mock(ProjectAccessService.class);
		var versions = mock(DatasetVersionRepository.class);
		var specifications = mock(DatasetBuildSpecificationRepository.class);
		var snapshots = mock(DatasetBuildSnapshotRepository.class);
		var validations = mock(DatasetVersionValidationRepository.class);
		var exports = mock(DatasetExportRepository.class);
		var approvals = mock(DatasetExportApprovalService.class);
		var validationService = mock(DatasetVersionValidationService.class);
		var version = frozenVersion();
		var specification = mock(DatasetBuildSpecification.class);
		var snapshot = mock(DatasetBuildSnapshot.class);
		var validation = mock(DatasetVersionValidation.class);

		when(specification.getDatasetBuildSpecificationId()).thenReturn(SPECIFICATION_ID);
		when(specification.getExportProfileKey()).thenReturn(exportProfileKey);
		when(specification.getExportProfileDigest()).thenReturn(exportProfileDigest);
		when(snapshot.getDatasetBuildSnapshotId()).thenReturn(SNAPSHOT_ID);
		when(snapshot.getDatasetBuildSpecificationId()).thenReturn(SPECIFICATION_ID);
		when(snapshot.getSnapshotDigest()).thenReturn(digest('c'));
		when(validation.getDatasetVersionValidationId()).thenReturn(VALIDATION_ID);
		when(validation.getOutcome()).thenReturn(DatasetVersionValidationOutcome.PASS);
		when(validation.matchesSnapshot(snapshot)).thenReturn(true);

		var export = DatasetExport.request(
				version,
				specification,
				snapshot,
				validation,
				EXPORT_APPROVAL_REQUEST_ID,
				DatasetExportPurpose.MODEL_TRAINING,
				expiresAt,
				REQUESTER_ID,
				"dataset-export-request-0001",
				digest('d'),
				expiresAt.minusSeconds(3600));
		var approval = new DatasetExportApprovalSnapshot(
				EXPORT_APPROVAL_REQUEST_ID,
				version.getDatasetVersionId(),
				VALIDATION_ID,
				"PENDING",
				REQUESTER_ID,
				expiresAt.minusSeconds(3600));

		when(access.requireMember(PROJECT_ID, actorId)).thenReturn(role);
		when(versions.findByProjectIdAndDatasetVersionId(PROJECT_ID, version.getDatasetVersionId()))
				.thenReturn(Optional.of(version));
		when(specifications.findByDatasetVersionId(version.getDatasetVersionId()))
				.thenReturn(Optional.of(specification));
		when(snapshots.findByDatasetVersionId(version.getDatasetVersionId()))
				.thenReturn(Optional.of(snapshot));
		when(validations.findByDatasetVersionId(version.getDatasetVersionId()))
				.thenReturn(Optional.of(validation));
		when(exports.findByDatasetVersionId(version.getDatasetVersionId()))
				.thenReturn(Optional.of(export));
		when(approvals.find(PROJECT_ID, version.getDatasetVersionId()))
				.thenReturn(Optional.of(approval));
		when(validationService.evaluateCurrent(PROJECT_ID, version, actorId))
				.thenReturn(new DatasetVersionValidationEvaluation(snapshot, currentEvidenceBlockers));

		return new Fixture(
				new DatasetExportApprovalReadinessService(
						access,
						versions,
						specifications,
						snapshots,
						validations,
						exports,
						approvals,
						validationService),
				version,
				export);
	}

	private static DatasetVersion frozenVersion() {
		var version = DatasetVersion.create(
				PROJECT_ID,
				"Synthetic dataset",
				REQUESTER_ID,
				"dataset-version-create-0001",
				digest('a'),
				Instant.EPOCH);
		version.startBuild(
				REQUESTER_ID,
				"dataset-build-start-0001",
				DatasetVersionBuildService.requestFingerprint(version.getDatasetVersionId()),
				Instant.EPOCH);
		version.beginValidation(SNAPSHOT_ID);
		version.freeze(
				FREEZE_APPROVAL_REQUEST_ID,
				FREEZE_APPROVAL_DECISION_ID,
				"freeze.approver",
				"dataset-version-freeze-0001",
				digest('b'),
				Instant.EPOCH);
		return version;
	}

	private static String digest(char value) {
		return String.valueOf(value).repeat(64);
	}

	private record Fixture(
			DatasetExportApprovalReadinessService service,
			DatasetVersion version,
			DatasetExport export) {
	}
}
