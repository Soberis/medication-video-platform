package com.medicationvideo.platform.datasetexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.DatasetExportApprovalService;
import com.medicationvideo.platform.governance.DatasetExportApprovalSnapshot;
import com.medicationvideo.platform.iamproject.ProjectAccessService;

class DatasetExportServiceTests {

	private static final UUID PROJECT_ID = new UUID(0, 1);
	private static final UUID SPECIFICATION_ID = new UUID(0, 2);
	private static final UUID SNAPSHOT_ID = new UUID(0, 3);
	private static final UUID VALIDATION_ID = new UUID(0, 4);
	private static final UUID FREEZE_APPROVAL_REQUEST_ID = new UUID(0, 5);
	private static final UUID FREEZE_APPROVAL_DECISION_ID = new UUID(0, 6);
	private static final UUID EXPORT_APPROVAL_REQUEST_ID = new UUID(0, 7);
	private static final String ACTOR_ID = "export.admin";

	@Test
	void requestsAndAuditsOneFrozenDatasetExport() {
		var fixture = fixture(true);
		var expiresAt = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS);

		var response = fixture.service().request(
				PROJECT_ID,
				fixture.version().getDatasetVersionId(),
				ACTOR_ID,
				"dataset-export-request-0001",
				"dataset-export-http-request-0001",
				"trace-0001",
				DatasetExportPurpose.MODEL_TRAINING,
				expiresAt);

		assertEquals(DatasetExportStatus.REQUESTED, response.status());
		assertEquals(DatasetExportPurpose.MODEL_TRAINING, response.purpose());
		assertEquals(expiresAt, response.expiresAt());
		assertEquals("PENDING", response.approval().status());
		verify(fixture.access()).lockAndRequireAdministrator(PROJECT_ID, ACTOR_ID);
		verify(fixture.approvals()).request(
				eq(PROJECT_ID),
				eq(fixture.version().getDatasetVersionId()),
				eq(VALIDATION_ID),
				eq(ACTOR_ID),
				any(Instant.class));
		verify(fixture.exports()).saveAndFlush(any(DatasetExport.class));
		verify(fixture.audit()).record(any());
	}

	@Test
	void replaysTheSameExportRequestWithoutCreatingAnotherApproval() {
		var fixture = fixture(true);
		var expiresAt = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
		var idempotencyKey = "dataset-export-request-0002";
		var fingerprint = DatasetExportService.requestFingerprint(
				fixture.version().getDatasetVersionId(),
				DatasetExportPurpose.MODEL_TRAINING,
				expiresAt);
		var existing = DatasetExport.request(
				fixture.version(),
				fixture.specification(),
				fixture.snapshot(),
				fixture.validation(),
				EXPORT_APPROVAL_REQUEST_ID,
				DatasetExportPurpose.MODEL_TRAINING,
				expiresAt,
				ACTOR_ID,
				idempotencyKey,
				fingerprint,
				Instant.EPOCH);
		when(fixture.exports().findByProjectIdAndRequestedByAndIdempotencyKey(
				PROJECT_ID, ACTOR_ID, idempotencyKey)).thenReturn(Optional.of(existing));

		var response = fixture.service().request(
				PROJECT_ID,
				fixture.version().getDatasetVersionId(),
				ACTOR_ID,
				idempotencyKey,
				"dataset-export-http-request-0002",
				null,
				DatasetExportPurpose.MODEL_TRAINING,
				expiresAt);

		assertEquals(existing.getDatasetExportId(), response.datasetExportId());
		verify(fixture.exports(), never()).saveAndFlush(any());
		verify(fixture.approvals(), never()).request(any(), any(), any(), any(), any());
		verifyNoInteractions(fixture.audit());
	}

	@Test
	void rejectsARequestForANonFrozenDatasetVersion() {
		var fixture = fixture(false);

		var exception = assertThrows(DatasetVersionException.class, () -> fixture.service().request(
				PROJECT_ID,
				fixture.version().getDatasetVersionId(),
				ACTOR_ID,
				"dataset-export-request-0003",
				"dataset-export-http-request-0003",
				null,
				DatasetExportPurpose.MODEL_TRAINING,
				Instant.now().plusSeconds(3600)));

		assertEquals("DATASET_EXPORT_VERSION_NOT_FROZEN", exception.getErrorCode());
		verify(fixture.exports(), never()).saveAndFlush(any());
		verify(fixture.approvals(), never()).request(any(), any(), any(), any(), any());
		verifyNoInteractions(fixture.audit());
	}

	@Test
	void rejectsAnExpiredExportPurposeGrant() {
		var fixture = fixture(true);

		var exception = assertThrows(DatasetVersionException.class, () -> fixture.service().request(
				PROJECT_ID,
				fixture.version().getDatasetVersionId(),
				ACTOR_ID,
				"dataset-export-request-0004",
				"dataset-export-http-request-0004",
				null,
				DatasetExportPurpose.MODEL_TRAINING,
				Instant.EPOCH));

		assertEquals("DATASET_EXPORT_EXPIRY_INVALID", exception.getErrorCode());
		verify(fixture.versions(), never()).findByProjectIdAndDatasetVersionId(any(), any());
		verify(fixture.approvals(), never()).request(any(), any(), any(), any(), any());
		verifyNoInteractions(fixture.audit());
	}

	@Test
	void rejectsAnIdempotencyKeyReusedWithAnotherExpiry() {
		var fixture = fixture(true);
		var firstExpiry = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
		var secondExpiry = firstExpiry.plusSeconds(1);
		var idempotencyKey = "dataset-export-request-0005";
		var existing = DatasetExport.request(
				fixture.version(),
				fixture.specification(),
				fixture.snapshot(),
				fixture.validation(),
				EXPORT_APPROVAL_REQUEST_ID,
				DatasetExportPurpose.MODEL_TRAINING,
				firstExpiry,
				ACTOR_ID,
				idempotencyKey,
				DatasetExportService.requestFingerprint(
						fixture.version().getDatasetVersionId(),
						DatasetExportPurpose.MODEL_TRAINING,
						firstExpiry),
				Instant.EPOCH);
		when(fixture.exports().findByProjectIdAndRequestedByAndIdempotencyKey(
				PROJECT_ID, ACTOR_ID, idempotencyKey)).thenReturn(Optional.of(existing));

		var exception = assertThrows(DatasetVersionException.class, () -> fixture.service().request(
				PROJECT_ID,
				fixture.version().getDatasetVersionId(),
				ACTOR_ID,
				idempotencyKey,
				"dataset-export-http-request-0005",
				null,
				DatasetExportPurpose.MODEL_TRAINING,
				secondExpiry));

		assertEquals("DATASET_EXPORT_IDEMPOTENCY_KEY_REUSED", exception.getErrorCode());
		verify(fixture.exports(), never()).saveAndFlush(any());
		verifyNoInteractions(fixture.audit());
	}

	private static Fixture fixture(boolean frozen) {
		var access = mock(ProjectAccessService.class);
		var versions = mock(DatasetVersionRepository.class);
		var specifications = mock(DatasetBuildSpecificationRepository.class);
		var snapshots = mock(DatasetBuildSnapshotRepository.class);
		var validations = mock(DatasetVersionValidationRepository.class);
		var exports = mock(DatasetExportRepository.class);
		var approvals = mock(DatasetExportApprovalService.class);
		var audit = mock(AuditEventRecorder.class);
		var version = DatasetVersion.create(
				PROJECT_ID,
				"Synthetic dataset",
				ACTOR_ID,
				"dataset-version-create-0001",
				digest('a'),
				Instant.EPOCH);
		version.startBuild(
				ACTOR_ID,
				"dataset-build-start-0001",
				DatasetVersionBuildService.requestFingerprint(version.getDatasetVersionId()),
				Instant.EPOCH);
		version.beginValidation(SNAPSHOT_ID);
		if (frozen) {
			version.freeze(
					FREEZE_APPROVAL_REQUEST_ID,
					FREEZE_APPROVAL_DECISION_ID,
					"freeze.admin",
					"dataset-version-freeze-0001",
					digest('b'),
					Instant.EPOCH);
		}

		var specification = mock(DatasetBuildSpecification.class);
		when(specification.getDatasetBuildSpecificationId()).thenReturn(SPECIFICATION_ID);
		when(specification.getExportProfileKey()).thenReturn(DatasetExportProfile.PROFILE_KEY);
		when(specification.getExportProfileDigest()).thenReturn(DatasetExportProfile.PROFILE_DIGEST);
		var snapshot = mock(DatasetBuildSnapshot.class);
		when(snapshot.getDatasetBuildSnapshotId()).thenReturn(SNAPSHOT_ID);
		when(snapshot.getDatasetBuildSpecificationId()).thenReturn(SPECIFICATION_ID);
		when(snapshot.getSnapshotDigest()).thenReturn(digest('c'));
		var validation = mock(DatasetVersionValidation.class);
		when(validation.getDatasetVersionValidationId()).thenReturn(VALIDATION_ID);
		when(validation.getOutcome()).thenReturn(DatasetVersionValidationOutcome.PASS);
		when(validation.matchesSnapshot(snapshot)).thenReturn(true);
		var approval = new DatasetExportApprovalSnapshot(
				EXPORT_APPROVAL_REQUEST_ID,
				version.getDatasetVersionId(),
				VALIDATION_ID,
				"PENDING",
				ACTOR_ID,
				Instant.EPOCH);

		when(versions.findByProjectIdAndDatasetVersionId(PROJECT_ID, version.getDatasetVersionId()))
				.thenReturn(Optional.of(version));
		when(specifications.findByDatasetVersionId(version.getDatasetVersionId()))
				.thenReturn(Optional.of(specification));
		when(snapshots.findByDatasetVersionId(version.getDatasetVersionId()))
				.thenReturn(Optional.of(snapshot));
		when(validations.findByDatasetVersionId(version.getDatasetVersionId()))
				.thenReturn(Optional.of(validation));
		when(exports.saveAndFlush(any(DatasetExport.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(approvals.request(
				eq(PROJECT_ID),
				eq(version.getDatasetVersionId()),
				eq(VALIDATION_ID),
				eq(ACTOR_ID),
				any(Instant.class))).thenReturn(approval);
		when(approvals.find(PROJECT_ID, version.getDatasetVersionId()))
				.thenReturn(Optional.of(approval));

		return new Fixture(
				new DatasetExportService(
						access,
						versions,
						specifications,
						snapshots,
						validations,
						exports,
						approvals,
						audit),
				access,
				versions,
				exports,
				approvals,
				audit,
				version,
				specification,
				snapshot,
				validation);
	}

	private static String digest(char value) {
		return String.valueOf(value).repeat(64);
	}

	private record Fixture(
			DatasetExportService service,
			ProjectAccessService access,
			DatasetVersionRepository versions,
			DatasetExportRepository exports,
			DatasetExportApprovalService approvals,
			AuditEventRecorder audit,
			DatasetVersion version,
			DatasetBuildSpecification specification,
			DatasetBuildSnapshot snapshot,
			DatasetVersionValidation validation) {
	}
}
