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
import com.medicationvideo.platform.iamproject.ProjectAccessService;

class DatasetVersionBuildServiceTests {

	private static final UUID PROJECT_ID = new UUID(0, 1);
	private static final UUID SPLIT_PLAN_ID = new UUID(0, 2);
	private static final UUID SPECIFICATION_ID = new UUID(0, 3);
	private static final String ACTOR_ID = "dataset.admin";

	@Test
	void startsAndAuditsAReadyDatasetVersion() {
		var fixture = fixture();

		var response = fixture.service().start(
				PROJECT_ID,
				fixture.version().getDatasetVersionId(),
				ACTOR_ID,
				"dataset-build-start-0001",
				"dataset-build-start-request-0001",
				"trace-0001");

		assertEquals(DatasetVersionStatus.BUILDING, response.status());
		assertEquals(ACTOR_ID, response.startedBy());
		assertEquals(DatasetVersionStatus.BUILDING, fixture.version().getStatus());
		verify(fixture.access()).lockAndRequireAdministrator(PROJECT_ID, ACTOR_ID);
		verify(fixture.versions()).saveAndFlush(fixture.version());
		verify(fixture.audit()).record(any());
	}

	@Test
	void replaysTheSameBuildStartWithoutRecheckingOrWriting() {
		var fixture = fixture();
		var idempotencyKey = "dataset-build-start-0002";
		fixture.version().startBuild(
				ACTOR_ID,
				idempotencyKey,
				DatasetVersionBuildService.requestFingerprint(fixture.version().getDatasetVersionId()),
				Instant.EPOCH);
		when(fixture.versions().findByProjectIdAndBuildStartedByAndBuildStartIdempotencyKey(
				PROJECT_ID, ACTOR_ID, idempotencyKey)).thenReturn(Optional.of(fixture.version()));

		var response = fixture.service().start(
				PROJECT_ID,
				fixture.version().getDatasetVersionId(),
				ACTOR_ID,
				idempotencyKey,
				"dataset-build-start-request-0002",
				null);

		assertEquals(DatasetVersionStatus.BUILDING, response.status());
		verify(fixture.versions(), never()).saveAndFlush(any());
		verifyNoInteractions(fixture.currentAdmission(), fixture.audit());
	}

	@Test
	void rejectsAChangedCurrentFactWithoutChangingTheDraft() {
		var fixture = fixture();
		var readiness = new DatasetVersionBuildReadinessResponse(
				false,
				List.of(
						DatasetVersionBuildBlocker.CURRENT_ADMISSION_NOT_ELIGIBLE,
						DatasetVersionBuildBlocker.SNAPSHOT_EVIDENCE_CHANGED),
				false,
				digest('c'),
				digest('d'),
				SPLIT_PLAN_ID,
				specification(fixture.version().getDatasetVersionId()));
		when(fixture.currentAdmission().get(
				PROJECT_ID, fixture.version().getDatasetVersionId(), ACTOR_ID))
				.thenReturn(new DatasetVersionCurrentAdmissionResponse(
						fixture.version().getDatasetVersionId(), false, List.of(), readiness, false));

		var exception = assertThrows(DatasetVersionException.class, () -> fixture.service().start(
				PROJECT_ID,
				fixture.version().getDatasetVersionId(),
				ACTOR_ID,
				"dataset-build-start-0003",
				"dataset-build-start-request-0003",
				null));

		assertEquals("DATASET_VERSION_BUILD_BLOCKED", exception.getErrorCode());
		assertEquals(
				"CURRENT_ADMISSION_NOT_ELIGIBLE,SNAPSHOT_EVIDENCE_CHANGED",
				exception.getDetails().get("blocking_reasons"));
		assertEquals(DatasetVersionStatus.DRAFT, fixture.version().getStatus());
		verify(fixture.versions(), never()).saveAndFlush(any());
		verifyNoInteractions(fixture.audit());
	}

	@Test
	void rejectsAnIdempotencyKeyAlreadyUsedForAnotherDatasetVersion() {
		var fixture = fixture();
		var idempotencyKey = "dataset-build-start-0004";
		fixture.version().startBuild(
				ACTOR_ID,
				idempotencyKey,
				DatasetVersionBuildService.requestFingerprint(fixture.version().getDatasetVersionId()),
				Instant.EPOCH);
		when(fixture.versions().findByProjectIdAndBuildStartedByAndBuildStartIdempotencyKey(
				PROJECT_ID, ACTOR_ID, idempotencyKey)).thenReturn(Optional.of(fixture.version()));

		var exception = assertThrows(DatasetVersionException.class, () -> fixture.service().start(
				PROJECT_ID,
				UUID.randomUUID(),
				ACTOR_ID,
				idempotencyKey,
				"dataset-build-start-request-0004",
				null));

		assertEquals("DATASET_VERSION_BUILD_IDEMPOTENCY_KEY_REUSED", exception.getErrorCode());
		verify(fixture.versions(), never()).saveAndFlush(any());
		verifyNoInteractions(fixture.currentAdmission(), fixture.audit());
	}

	private static Fixture fixture() {
		var access = mock(ProjectAccessService.class);
		var versions = mock(DatasetVersionRepository.class);
		var currentAdmission = mock(DatasetVersionCurrentAdmissionQueryService.class);
		var audit = mock(AuditEventRecorder.class);
		var version = DatasetVersion.create(
				PROJECT_ID,
				"Synthetic draft",
				ACTOR_ID,
				"dataset-version-create-0001",
				digest('a'),
				Instant.EPOCH);
		var readiness = new DatasetVersionBuildReadinessResponse(
				true,
				List.of(),
				true,
				digest('c'),
				digest('d'),
				SPLIT_PLAN_ID,
				specification(version.getDatasetVersionId()));
		when(versions.findByProjectIdAndDatasetVersionId(PROJECT_ID, version.getDatasetVersionId()))
				.thenReturn(Optional.of(version));
		when(versions.saveAndFlush(any(DatasetVersion.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(currentAdmission.get(PROJECT_ID, version.getDatasetVersionId(), ACTOR_ID))
				.thenReturn(new DatasetVersionCurrentAdmissionResponse(
						version.getDatasetVersionId(), true, List.of(), readiness, true));
		return new Fixture(
				new DatasetVersionBuildService(access, versions, currentAdmission, audit),
				access,
				versions,
				currentAdmission,
				audit,
				version);
	}

	private static DatasetBuildSpecificationResponse specification(UUID datasetVersionId) {
		return new DatasetBuildSpecificationResponse(
				SPECIFICATION_ID,
				datasetVersionId,
				DatasetSelectionRuleVersion.EXPLICIT_CAPTURE_SESSION_SNAPSHOT_V1,
				digest('c'),
				digest('d'),
				SPLIT_PLAN_ID,
				DatasetExportProfile.PROFILE_KEY,
				DatasetExportProfile.PROFILE_DIGEST,
				ACTOR_ID,
				Instant.EPOCH);
	}

	private static String digest(char value) {
		return String.valueOf(value).repeat(64);
	}

	private record Fixture(
			DatasetVersionBuildService service,
			ProjectAccessService access,
			DatasetVersionRepository versions,
			DatasetVersionCurrentAdmissionQueryService currentAdmission,
			AuditEventRecorder audit,
			DatasetVersion version) {
	}
}
