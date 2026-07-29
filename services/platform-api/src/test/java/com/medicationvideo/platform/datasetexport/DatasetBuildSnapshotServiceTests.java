package com.medicationvideo.platform.datasetexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.medicationvideo.platform.assetcapture.CaptureSessionDatasetAdmissionFacts;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.iamproject.ProjectAccessService;

class DatasetBuildSnapshotServiceTests {

	private static final UUID PROJECT_ID = new UUID(0, 1);
	private static final String ACTOR_ID = "dataset.admin";
	private static final String LABEL_DIGEST = digest('a');
	private static final String CLEANING_DIGEST = digest('b');

	@Test
	void sealsACompleteRelationalSnapshotAndBeginsValidation() {
		var fixture = fixture();

		var response = fixture.service().create(
				PROJECT_ID,
				fixture.version().getDatasetVersionId(),
				ACTOR_ID,
				"dataset-snapshot-0001",
				"dataset-snapshot-request-0001",
				"trace-0001");

		assertEquals(DatasetBuildSnapshot.SNAPSHOT_SCHEMA_VERSION, response.snapshotSchemaVersion());
		assertEquals(DatasetBuildSnapshot.INPUT_VARIANT_POLICY, response.inputVariantPolicy());
		assertEquals(2, response.memberCount());
		assertEquals(2, response.streamCount());
		assertEquals(DatasetVersionStatus.VALIDATING, fixture.version().getStatus());
		assertEquals(response.datasetBuildSnapshotId(), fixture.version().getBuildSnapshotId());
		verify(fixture.access()).lockAndRequireAdministrator(PROJECT_ID, ACTOR_ID);
		verify(fixture.snapshots()).saveAndFlush(any(DatasetBuildSnapshot.class));
		verify(fixture.versions()).saveAndFlush(fixture.version());
		verify(fixture.audit()).record(any());
	}

	@Test
	void replaysTheSameSnapshotWithoutReadingOrWritingItsEvidenceAgain() {
		var fixture = fixture();
		var idempotencyKey = "dataset-snapshot-0002";
		var fingerprint = DatasetBuildSnapshotService.requestFingerprint(fixture.version().getDatasetVersionId());
		var snapshot = DatasetBuildSnapshot.create(
				fixture.version(),
				fixture.specification(),
				fixture.splitPlan(),
				fixture.members().size(),
				fixture.streams().size(),
				DatasetBuildSnapshotDigest.compute(
						fixture.version(),
						fixture.specification(),
						fixture.splitPlan(),
						fixture.members(),
						fixture.streams(),
						fixture.assignments()),
				ACTOR_ID,
				idempotencyKey,
				fingerprint,
				Instant.EPOCH);
		fixture.version().beginValidation(snapshot.getDatasetBuildSnapshotId());
		when(fixture.snapshots().findByProjectIdAndCreatedByAndIdempotencyKey(
				PROJECT_ID, ACTOR_ID, idempotencyKey)).thenReturn(Optional.of(snapshot));

		var response = fixture.service().create(
				PROJECT_ID,
				fixture.version().getDatasetVersionId(),
				ACTOR_ID,
				idempotencyKey,
				"dataset-snapshot-request-0002",
				null);

		assertEquals(snapshot.getDatasetBuildSnapshotId(), response.datasetBuildSnapshotId());
		verify(fixture.snapshots(), never()).saveAndFlush(any());
		verify(fixture.versions(), never()).saveAndFlush(any());
		verifyNoInteractions(
				fixture.membersRepository(),
				fixture.streamsRepository(),
				fixture.splitPlans(),
				fixture.assignmentsRepository(),
				fixture.specifications(),
				fixture.audit());
	}

	@Test
	void rejectsAnIncompleteSplitSnapshotWithoutAdvancingTheVersion() {
		var fixture = fixture();
		when(fixture.assignmentsRepository().findByDatasetSplitPlanIdOrderByCandidateIndexAsc(
				fixture.splitPlan().getDatasetSplitPlanId()))
				.thenReturn(List.of(fixture.assignments().getFirst()));

		var exception = assertThrows(DatasetVersionException.class, () -> fixture.service().create(
				PROJECT_ID,
				fixture.version().getDatasetVersionId(),
				ACTOR_ID,
				"dataset-snapshot-0003",
				"dataset-snapshot-request-0003",
				null));

		assertEquals("DATASET_BUILD_SNAPSHOT_INCOMPLETE", exception.getErrorCode());
		assertEquals("SPLIT_ASSIGNMENT_INCOMPLETE", exception.getDetails().get("reason"));
		assertEquals(DatasetVersionStatus.BUILDING, fixture.version().getStatus());
		verify(fixture.snapshots(), never()).saveAndFlush(any());
		verify(fixture.versions(), never()).saveAndFlush(any());
		verifyNoInteractions(fixture.audit());
	}

	@Test
	void canonicalDigestDoesNotDependOnRepositoryRowOrder() {
		var fixture = fixture();
		var expected = DatasetBuildSnapshotDigest.compute(
				fixture.version(),
				fixture.specification(),
				fixture.splitPlan(),
				fixture.members(),
				fixture.streams(),
				fixture.assignments());
		var members = reversed(fixture.members());
		var streams = reversed(fixture.streams());
		var assignments = reversed(fixture.assignments());

		var reordered = DatasetBuildSnapshotDigest.compute(
				fixture.version(), fixture.specification(), fixture.splitPlan(), members, streams, assignments);
		var assignmentsReordered = DatasetBuildSnapshotDigest.compute(
				fixture.version(),
				fixture.specification(),
				fixture.splitPlan(),
				fixture.members(),
				fixture.streams(),
				List.of(fixture.assignments().get(1), fixture.assignments().getFirst()));

		assertEquals(expected, reordered);
		assertEquals(expected, assignmentsReordered);
		assertNotEquals(digest('0'), expected);
	}

	private static Fixture fixture() {
		var access = mock(ProjectAccessService.class);
		var versions = mock(DatasetVersionRepository.class);
		var membersRepository = mock(DatasetMemberSessionRepository.class);
		var streamsRepository = mock(DatasetMemberStreamRepository.class);
		var splitPlans = mock(DatasetSplitPlanRepository.class);
		var assignmentsRepository = mock(DatasetSplitAssignmentRepository.class);
		var specifications = mock(DatasetBuildSpecificationRepository.class);
		var snapshots = mock(DatasetBuildSnapshotRepository.class);
		var audit = mock(AuditEventRecorder.class);
		var version = DatasetVersion.create(
				PROJECT_ID,
				"Synthetic dataset",
				ACTOR_ID,
				"dataset-version-0001",
				digest('c'),
				Instant.EPOCH);
		version.startBuild(
				ACTOR_ID,
				"dataset-build-start-0001",
				DatasetVersionBuildService.requestFingerprint(version.getDatasetVersionId()),
				Instant.EPOCH);
		var firstMember = member(version, 0, new UUID(0, 10));
		var secondMember = member(version, 1, new UUID(0, 11));
		var members = List.of(firstMember, secondMember);
		var streams = List.of(
				stream(version, firstMember, 20),
				stream(version, secondMember, 21));
		var splitPlan = DatasetSplitPlan.create(
				version,
				42,
				1,
				ACTOR_ID,
				"dataset-split-plan-0001",
				digest('d'),
				Instant.EPOCH);
		var assignments = List.of(
				DatasetSplitAssignment.assign(splitPlan, firstMember, DatasetSplit.PILOT, 0),
				DatasetSplitAssignment.assign(splitPlan, secondMember, DatasetSplit.HOLDOUT, 1));
		var specification = DatasetBuildSpecification.create(
				version,
				LABEL_DIGEST,
				CLEANING_DIGEST,
				splitPlan,
				ACTOR_ID,
				"dataset-build-spec-0001",
				digest('e'),
				Instant.EPOCH);

		when(versions.findByProjectIdAndDatasetVersionId(PROJECT_ID, version.getDatasetVersionId()))
				.thenReturn(Optional.of(version));
		when(versions.saveAndFlush(any(DatasetVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(membersRepository.findByDatasetVersionIdOrderByCandidateIndexAsc(version.getDatasetVersionId()))
				.thenReturn(members);
		when(streamsRepository.findByDatasetVersionIdOrderByDatasetMemberSessionIdAscCameraIndexAsc(
				version.getDatasetVersionId())).thenReturn(streams);
		when(splitPlans.findByDatasetVersionId(version.getDatasetVersionId())).thenReturn(Optional.of(splitPlan));
		when(assignmentsRepository.findByDatasetSplitPlanIdOrderByCandidateIndexAsc(
				splitPlan.getDatasetSplitPlanId())).thenReturn(assignments);
		when(specifications.findByDatasetVersionId(version.getDatasetVersionId()))
				.thenReturn(Optional.of(specification));
		when(snapshots.saveAndFlush(any(DatasetBuildSnapshot.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		return new Fixture(
				new DatasetBuildSnapshotService(
						access,
						versions,
						membersRepository,
						streamsRepository,
						splitPlans,
						assignmentsRepository,
						specifications,
						snapshots,
						audit),
				access,
				versions,
				membersRepository,
				streamsRepository,
				splitPlans,
				assignmentsRepository,
				specifications,
				snapshots,
				audit,
				version,
				members,
				streams,
				splitPlan,
				assignments,
				specification);
	}

	private static DatasetMemberSession member(DatasetVersion version, int candidateIndex, UUID captureSessionId) {
		return DatasetMemberSession.snapshot(
				version,
				candidateIndex,
				new CaptureSessionDatasetAdmissionFacts(
						captureSessionId,
						1,
						1,
						"COMPLETE",
						"PASS",
						List.of()));
	}

	private static DatasetMemberStream stream(
			DatasetVersion version, DatasetMemberSession member, long identity) {
		return DatasetMemberStream.snapshot(
				version,
				member,
				new DatasetAdmissionStreamEvidence(
						new UUID(0, identity),
						new UUID(0, identity + 100),
						0,
						digest(identity % 2 == 0 ? 'f' : 'e'),
						"QUALIFIED",
						1,
						new UUID(0, identity + 200),
						digest((char) ('1' + identity % 2)),
						LABEL_DIGEST,
						new UUID(0, identity + 300),
						new UUID(0, identity + 400),
						CLEANING_DIGEST,
						"PASS"));
	}

	private static <T> List<T> reversed(List<T> values) {
		var result = new ArrayList<>(values);
		Collections.reverse(result);
		return result;
	}

	private static String digest(char value) {
		return String.valueOf(value).repeat(64);
	}

	private record Fixture(
			DatasetBuildSnapshotService service,
			ProjectAccessService access,
			DatasetVersionRepository versions,
			DatasetMemberSessionRepository membersRepository,
			DatasetMemberStreamRepository streamsRepository,
			DatasetSplitPlanRepository splitPlans,
			DatasetSplitAssignmentRepository assignmentsRepository,
			DatasetBuildSpecificationRepository specifications,
			DatasetBuildSnapshotRepository snapshots,
			AuditEventRecorder audit,
			DatasetVersion version,
			List<DatasetMemberSession> members,
			List<DatasetMemberStream> streams,
			DatasetSplitPlan splitPlan,
			List<DatasetSplitAssignment> assignments,
			DatasetBuildSpecification specification) {
	}
}
