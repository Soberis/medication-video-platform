package com.medicationvideo.platform.datasetexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.medicationvideo.platform.assetcapture.CaptureSessionDatasetAdmissionFacts;

class DatasetSplitPlannerTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Test
	void producesAStableCaptureSessionLevelPilotHoldoutPlan() {
		var members = List.of(member(0, "10000000-0000-0000-0000-000000000001"),
				member(1, "10000000-0000-0000-0000-000000000002"), member(2, "10000000-0000-0000-0000-000000000003"),
				member(3, "10000000-0000-0000-0000-000000000004"));

		var first = DatasetSplitPlanner.plan(members, 1, 20260729);
		var replay = DatasetSplitPlanner.plan(List.of(members.get(3), members.get(1), members.get(0), members.get(2)),
				1, 20260729);

		assertEquals(first.assignments().stream().map(DatasetSplitPlannerTests::identity).toList(),
				replay.assignments().stream().map(DatasetSplitPlannerTests::identity).toList());
		assertEquals(1, first.assignments().stream().filter(item -> item.split() == DatasetSplit.HOLDOUT).count());
		assertEquals(3, first.assignments().stream().filter(item -> item.split() == DatasetSplit.PILOT).count());
		assertEquals(List.of(0, 1, 2, 3),
				first.assignments().stream().map(PlannedDatasetSplit::assignmentRank).toList());
	}

	@Test
	void persistsTheSeedAsPartOfTheReproducibilityBoundary() {
		var members = List.of(member(0, "10000000-0000-0000-0000-000000000001"),
				member(1, "10000000-0000-0000-0000-000000000002"), member(2, "10000000-0000-0000-0000-000000000003"),
				member(3, "10000000-0000-0000-0000-000000000004"));

		var first = DatasetSplitPlanner.plan(members, 2, 20260729);
		var second = DatasetSplitPlanner.plan(members, 2, 20260730);

		assertEquals(20260729, first.splitSeed());
		assertEquals(20260730, second.splitSeed());
		assertNotEquals(first.assignments().stream().map(DatasetSplitPlannerTests::identity).toList(),
				second.assignments().stream().map(DatasetSplitPlannerTests::identity).toList());
	}

	private static String identity(PlannedDatasetSplit assignment) {
		return assignment.assignmentRank() + ":" + assignment.member().getCaptureSessionId() + ":" + assignment.split();
	}

	private static DatasetMemberSession member(int candidateIndex, String captureSessionId) {
		var version = DatasetVersion.create(PROJECT_ID, "Synthetic draft", "dataset.admin",
				"dataset-version-create-0001", "a".repeat(64), Instant.parse("2026-07-29T00:00:00Z"));
		return DatasetMemberSession.snapshot(version, candidateIndex, new CaptureSessionDatasetAdmissionFacts(
				UUID.fromString(captureSessionId), 1, 1, "COMPLETE", "PASS", List.of()));
	}

}
