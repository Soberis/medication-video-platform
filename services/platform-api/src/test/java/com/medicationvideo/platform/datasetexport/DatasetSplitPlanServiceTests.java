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

class DatasetSplitPlanServiceTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final String ACTOR_ID = "dataset.admin";

	@Test
	void createsAndAuditsOneImmutablePilotHoldoutPlan() {
		var fixture = fixture(3);
		when(fixture.planner().plan(fixture.members(), 1))
			.thenReturn(DatasetSplitPlanner.plan(fixture.members(), 1, 20260729));

		var response = fixture.service()
			.create(PROJECT_ID, fixture.version().getDatasetVersionId(), ACTOR_ID, "dataset-split-plan-0001",
					"dataset-split-request-0001", "trace-0001", 1);

		assertEquals(DatasetSplitPolicy.CAPTURE_SESSION_HASH_RANK_V1, response.policyVersion());
		assertEquals(20260729, response.splitSeed());
		assertEquals(2, response.pilotCount());
		assertEquals(1, response.holdoutCount());
		assertEquals(List.of(0, 1, 2),
				response.assignments().stream().map(DatasetSplitAssignmentResponse::candidateIndex).toList());
		verify(fixture.access()).lockAndRequireAdministrator(PROJECT_ID, ACTOR_ID);
		verify(fixture.plans()).saveAndFlush(any(DatasetSplitPlan.class));
		verify(fixture.assignments()).saveAll(any());
		verify(fixture.audit()).record(any());
	}

	@Test
	void replaysTheSameIdempotentRequestWithoutReplanning() {
		var fixture = fixture(2);
		var plan = DatasetSplitPlan.create(fixture.version(), 20260729, 1, ACTOR_ID, "dataset-split-plan-0002",
				fingerprint(fixture, 1), Instant.parse("2026-07-29T00:00:00Z"));
		var assignments = List.of(DatasetSplitAssignment.assign(plan, fixture.members().get(0), DatasetSplit.PILOT, 1),
				DatasetSplitAssignment.assign(plan, fixture.members().get(1), DatasetSplit.HOLDOUT, 0));
		when(fixture.plans()
			.findByProjectIdAndCreatedByAndIdempotencyKey(PROJECT_ID, ACTOR_ID, "dataset-split-plan-0002"))
			.thenReturn(Optional.of(plan));
		when(fixture.assignments().findByDatasetSplitPlanIdOrderByCandidateIndexAsc(plan.getDatasetSplitPlanId()))
			.thenReturn(assignments);

		var response = fixture.service()
			.create(PROJECT_ID, fixture.version().getDatasetVersionId(), ACTOR_ID, "dataset-split-plan-0002",
					"dataset-split-request-0002", null, 1);

		assertEquals(plan.getDatasetSplitPlanId(), response.datasetSplitPlanId());
		assertEquals(List.of(0, 1),
				response.assignments().stream().map(DatasetSplitAssignmentResponse::candidateIndex).toList());
		verifyNoInteractions(fixture.planner(), fixture.audit());
		verify(fixture.plans(), never()).saveAndFlush(any());
	}

	@Test
	void rejectsAPlanThatWouldLeaveEitherSplitEmpty() {
		var fixture = fixture(2);

		var exception = assertThrows(DatasetVersionException.class,
				() -> fixture.service()
					.create(PROJECT_ID, fixture.version().getDatasetVersionId(), ACTOR_ID, "dataset-split-plan-0003",
							"dataset-split-request-0003", null, 2));

		assertEquals("DATASET_SPLIT_PLAN_HOLDOUT_COUNT_INVALID", exception.getErrorCode());
		verifyNoInteractions(fixture.planner(), fixture.assignments(), fixture.audit());
	}

	@Test
	void rejectsASecondPlanForTheSameDraft() {
		var fixture = fixture(2);
		var existing = DatasetSplitPlan.create(fixture.version(), 20260729, 1, ACTOR_ID, "dataset-split-plan-existing",
				"a".repeat(64), Instant.now());
		when(fixture.plans().findByDatasetVersionId(fixture.version().getDatasetVersionId()))
			.thenReturn(Optional.of(existing));

		var exception = assertThrows(DatasetVersionException.class,
				() -> fixture.service()
					.create(PROJECT_ID, fixture.version().getDatasetVersionId(), ACTOR_ID, "dataset-split-plan-0004",
							"dataset-split-request-0004", null, 1));

		assertEquals("DATASET_SPLIT_PLAN_ALREADY_EXISTS", exception.getErrorCode());
		verifyNoInteractions(fixture.planner(), fixture.assignments(), fixture.audit());
	}

	private static Fixture fixture(int memberCount) {
		var access = mock(ProjectAccessService.class);
		var versions = mock(DatasetVersionRepository.class);
		var members = mock(DatasetMemberSessionRepository.class);
		var plans = mock(DatasetSplitPlanRepository.class);
		var assignments = mock(DatasetSplitAssignmentRepository.class);
		var planner = mock(DatasetSplitPlanner.class);
		var audit = mock(AuditEventRecorder.class);
		var version = version();
		var memberRows = java.util.stream.IntStream.range(0, memberCount)
			.mapToObj(index -> member(version, index))
			.toList();
		when(versions.findByProjectIdAndDatasetVersionId(PROJECT_ID, version.getDatasetVersionId()))
			.thenReturn(Optional.of(version));
		when(members.findByDatasetVersionIdOrderByCandidateIndexAsc(version.getDatasetVersionId()))
			.thenReturn(memberRows);
		when(plans.saveAndFlush(any(DatasetSplitPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));
		return new Fixture(new DatasetSplitPlanService(access, versions, members, plans, assignments, planner, audit),
				access, plans, assignments, planner, audit, version, memberRows);
	}

	private static DatasetVersion version() {
		return DatasetVersion.create(PROJECT_ID, "Synthetic draft", ACTOR_ID, "dataset-version-create-0001",
				"b".repeat(64), Instant.parse("2026-07-29T00:00:00Z"));
	}

	private static DatasetMemberSession member(DatasetVersion version, int index) {
		return DatasetMemberSession.snapshot(version, index, new CaptureSessionDatasetAdmissionFacts(
				new UUID(0x1000000000000000L, index + 1L), 1, 1, "COMPLETE", "PASS", List.of()));
	}

	private static String fingerprint(Fixture fixture, int holdoutCount) {
		return DatasetSplitPlanService.requestFingerprint(fixture.version().getDatasetVersionId(), holdoutCount);
	}

	private record Fixture(DatasetSplitPlanService service, ProjectAccessService access,
			DatasetSplitPlanRepository plans, DatasetSplitAssignmentRepository assignments, DatasetSplitPlanner planner,
			AuditEventRecorder audit, DatasetVersion version, List<DatasetMemberSession> members) {
	}

}
