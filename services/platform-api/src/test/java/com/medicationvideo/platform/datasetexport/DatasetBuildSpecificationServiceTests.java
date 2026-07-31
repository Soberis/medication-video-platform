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

import com.medicationvideo.platform.assetcapture.CaptureSessionDatasetAdmissionFacts;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.iamproject.ProjectAccessService;

class DatasetBuildSpecificationServiceTests {

	private static final UUID PROJECT_ID = new UUID(0, 1);

	private static final String ACTOR_ID = "dataset.admin";

	@Test
	void fixesAndAuditsTheMinimumImmutableBuildConfiguration() {
		var fixture = fixture('a', 'a');

		var response = fixture.service()
			.create(PROJECT_ID, fixture.version().getDatasetVersionId(), ACTOR_ID, "dataset-build-spec-0001",
					"dataset-build-request-0001", "trace-0001");

		assertEquals(DatasetSelectionRuleVersion.EXPLICIT_CAPTURE_SESSION_SNAPSHOT_V1, response.selectionRuleVersion());
		assertEquals(digest('a'), response.labelSchemaDigest());
		assertEquals(digest('d'), response.cleaningRuleSetDigest());
		assertEquals(fixture.splitPlan().getDatasetSplitPlanId(), response.datasetSplitPlanId());
		assertEquals(DatasetExportProfile.CURRENT_KEY, response.exportProfileKey());
		assertEquals(DatasetExportProfile.CURRENT_DIGEST, response.exportProfileDigest());
		verify(fixture.access()).lockAndRequireAdministrator(PROJECT_ID, ACTOR_ID);
		verify(fixture.specifications()).saveAndFlush(any(DatasetBuildSpecification.class));
		verify(fixture.audit()).record(any());
	}

	@Test
	void replaysTheSameIdempotentRequestWithoutWritingAgain() {
		var fixture = fixture('a', 'a');
		var specification = DatasetBuildSpecification.create(fixture.version(), digest('a'), digest('d'),
				fixture.splitPlan(), ACTOR_ID, "dataset-build-spec-0002",
				DatasetBuildSpecificationService.requestFingerprint(fixture.version().getDatasetVersionId()),
				Instant.EPOCH);
		when(fixture.specifications()
			.findByProjectIdAndCreatedByAndIdempotencyKey(PROJECT_ID, ACTOR_ID, "dataset-build-spec-0002"))
			.thenReturn(Optional.of(specification));

		var response = fixture.service()
			.create(PROJECT_ID, fixture.version().getDatasetVersionId(), ACTOR_ID, "dataset-build-spec-0002",
					"dataset-build-request-0002", null);

		assertEquals(specification.getDatasetBuildSpecificationId(), response.datasetBuildSpecificationId());
		verify(fixture.specifications(), never()).saveAndFlush(any());
		verifyNoInteractions(fixture.audit());
	}

	@Test
	void requiresAnImmutableSplitPlanBeforeFixingTheConfiguration() {
		var fixture = fixture('a', 'a');
		when(fixture.splitPlans().findByDatasetVersionId(fixture.version().getDatasetVersionId()))
			.thenReturn(Optional.empty());

		var exception = assertThrows(DatasetVersionException.class,
				() -> fixture.service()
					.create(PROJECT_ID, fixture.version().getDatasetVersionId(), ACTOR_ID, "dataset-build-spec-0003",
							"dataset-build-request-0003", null));

		assertEquals("DATASET_BUILD_SPLIT_PLAN_REQUIRED", exception.getErrorCode());
		verify(fixture.specifications(), never()).saveAndFlush(any());
		verifyNoInteractions(fixture.audit());
	}

	@Test
	void rejectsMixedLabelSchemasWithoutPersistingAPartialSpecification() {
		var fixture = fixture('a', 'b');

		var exception = assertThrows(DatasetVersionException.class,
				() -> fixture.service()
					.create(PROJECT_ID, fixture.version().getDatasetVersionId(), ACTOR_ID, "dataset-build-spec-0004",
							"dataset-build-request-0004", null));

		assertEquals("DATASET_BUILD_LABEL_SCHEMA_NOT_UNIFORM", exception.getErrorCode());
		verify(fixture.specifications(), never()).saveAndFlush(any());
		verifyNoInteractions(fixture.audit());
	}

	private static Fixture fixture(char firstLabelDigest, char secondLabelDigest) {
		var access = mock(ProjectAccessService.class);
		var versions = mock(DatasetVersionRepository.class);
		var streams = mock(DatasetMemberStreamRepository.class);
		var splitPlans = mock(DatasetSplitPlanRepository.class);
		var specifications = mock(DatasetBuildSpecificationRepository.class);
		var audit = mock(AuditEventRecorder.class);
		var version = DatasetVersion.create(PROJECT_ID, "Synthetic draft", ACTOR_ID, "dataset-version-create-0001",
				digest('f'), Instant.EPOCH);
		var firstMember = member(version, 0);
		var secondMember = member(version, 1);
		var snapshotStreams = List.of(stream(version, firstMember, 1, firstLabelDigest),
				stream(version, secondMember, 2, secondLabelDigest));
		var splitPlan = DatasetSplitPlan.create(version, 20260729, 1, ACTOR_ID, "dataset-split-build-spec-0001",
				digest('e'), Instant.EPOCH);
		when(versions.findByProjectIdAndDatasetVersionId(PROJECT_ID, version.getDatasetVersionId()))
			.thenReturn(Optional.of(version));
		when(streams
			.findByDatasetVersionIdOrderByDatasetMemberSessionIdAscCameraIndexAsc(version.getDatasetVersionId()))
			.thenReturn(snapshotStreams);
		when(splitPlans.findByDatasetVersionId(version.getDatasetVersionId())).thenReturn(Optional.of(splitPlan));
		when(specifications.saveAndFlush(any(DatasetBuildSpecification.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));
		return new Fixture(
				new DatasetBuildSpecificationService(access, versions, streams, splitPlans, specifications, audit),
				access, splitPlans, specifications, audit, version, splitPlan);
	}

	private static DatasetMemberSession member(DatasetVersion version, int index) {
		return DatasetMemberSession.snapshot(version, index, new CaptureSessionDatasetAdmissionFacts(
				new UUID(0x1000000000000000L, index + 1L), 1, 1, "COMPLETE", "PASS", List.of()));
	}

	private static DatasetMemberStream stream(DatasetVersion version, DatasetMemberSession member, long sequence,
			char labelDigest) {
		return DatasetMemberStream.snapshot(version, member, new DatasetAdmissionStreamEvidence(
				new UUID(0x2000000000000000L, sequence), new UUID(0x3000000000000000L, sequence), 0, digest('a'),
				"QUALIFIED", 1, new UUID(0x4000000000000000L, sequence), digest('b'), digest(labelDigest),
				new UUID(0x5000000000000000L, sequence), new UUID(0x6000000000000000L, sequence), digest('d'), "PASS"));
	}

	private static String digest(char value) {
		return String.valueOf(value).repeat(64);
	}

	private record Fixture(DatasetBuildSpecificationService service, ProjectAccessService access,
			DatasetSplitPlanRepository splitPlans, DatasetBuildSpecificationRepository specifications,
			AuditEventRecorder audit, DatasetVersion version, DatasetSplitPlan splitPlan) {
	}

}
