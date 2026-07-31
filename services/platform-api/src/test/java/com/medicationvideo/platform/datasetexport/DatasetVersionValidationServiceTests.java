package com.medicationvideo.platform.datasetexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.medicationvideo.platform.assetcapture.CaptureSessionDatasetAdmissionFacts;

class DatasetVersionValidationServiceTests {

	private static final UUID PROJECT_ID = new UUID(0, 1);

	private static final String ACTOR_ID = "dataset.admin";

	private static final String LABEL_DIGEST = digest('a');

	private static final String CLEANING_DIGEST = digest('b');

	@Test
	void passesWhenCurrentFactsAndTheSealedSnapshotStillMatch() {
		var fixture = fixture(true, true, null);

		var reasons = DatasetVersionValidationService.evaluate(fixture.version(), fixture.snapshot(),
				fixture.specification(), fixture.splitPlan(), fixture.members(), fixture.streams(),
				fixture.assignments(), fixture.currentAdmission());

		assertTrue(reasons.isEmpty());
	}

	@Test
	void reportsCurrentAdmissionAndEvidenceDriftWithoutHidingEitherReason() {
		var fixture = fixture(false, false, null);

		var reasons = DatasetVersionValidationService.evaluate(fixture.version(), fixture.snapshot(),
				fixture.specification(), fixture.splitPlan(), fixture.members(), fixture.streams(),
				fixture.assignments(), fixture.currentAdmission());

		assertEquals(List.of(DatasetVersionValidationService.CURRENT_ADMISSION_NOT_ELIGIBLE,
				DatasetVersionValidationService.SNAPSHOT_EVIDENCE_CHANGED), reasons);
	}

	@Test
	void reportsARecomputedSnapshotDigestMismatch() {
		var fixture = fixture(true, true, digest('0'));

		var reasons = DatasetVersionValidationService.evaluate(fixture.version(), fixture.snapshot(),
				fixture.specification(), fixture.splitPlan(), fixture.members(), fixture.streams(),
				fixture.assignments(), fixture.currentAdmission());

		assertEquals(List.of(DatasetVersionValidationService.SNAPSHOT_DIGEST_MISMATCH), reasons);
	}

	@Test
	void reportsAnIncompleteRelationalSnapshotInsteadOfComputingAMisleadingDigest() {
		var fixture = fixture(true, true, null);

		var reasons = DatasetVersionValidationService.evaluate(fixture.version(), fixture.snapshot(),
				fixture.specification(), fixture.splitPlan(), fixture.members(), fixture.streams(),
				List.of(fixture.assignments().getFirst()), fixture.currentAdmission());

		assertEquals(List.of(DatasetVersionValidationService.SNAPSHOT_STRUCTURE_INVALID), reasons);
	}

	private static Fixture fixture(boolean currentlyEligible, boolean immutableEvidenceCurrent,
			String sealedDigestOverride) {
		var version = DatasetVersion.create(PROJECT_ID, "Synthetic dataset", ACTOR_ID, "dataset-version-0001",
				digest('c'), Instant.EPOCH);
		version.startBuild(ACTOR_ID, "dataset-build-start-0001",
				DatasetVersionBuildService.requestFingerprint(version.getDatasetVersionId()), Instant.EPOCH);
		var firstMember = member(version, 0, new UUID(0, 10));
		var secondMember = member(version, 1, new UUID(0, 11));
		var members = List.of(firstMember, secondMember);
		var streams = List.of(stream(version, firstMember, 20), stream(version, secondMember, 21));
		var splitPlan = DatasetSplitPlan.create(version, 42, 1, ACTOR_ID, "dataset-split-plan-0001", digest('d'),
				Instant.EPOCH);
		var assignments = List.of(DatasetSplitAssignment.assign(splitPlan, firstMember, DatasetSplit.PILOT, 0),
				DatasetSplitAssignment.assign(splitPlan, secondMember, DatasetSplit.HOLDOUT, 1));
		var specification = DatasetBuildSpecification.create(version, LABEL_DIGEST, CLEANING_DIGEST, splitPlan,
				ACTOR_ID, "dataset-build-spec-0001", digest('e'), Instant.EPOCH);
		var computedDigest = DatasetBuildSnapshotDigest.compute(version, specification, splitPlan, members, streams,
				assignments);
		var snapshot = DatasetBuildSnapshot.create(version, specification, splitPlan, members.size(), streams.size(),
				sealedDigestOverride == null ? computedDigest : sealedDigestOverride, ACTOR_ID,
				"dataset-build-snapshot-0001", digest('f'), Instant.EPOCH);
		version.beginValidation(snapshot.getDatasetBuildSnapshotId());
		var readiness = new DatasetVersionBuildReadinessResponse(currentlyEligible && immutableEvidenceCurrent,
				List.of(), immutableEvidenceCurrent, LABEL_DIGEST, CLEANING_DIGEST, splitPlan.getDatasetSplitPlanId(),
				DatasetBuildSpecificationResponse.from(specification));
		var currentAdmission = new DatasetVersionCurrentAdmissionResponse(version.getDatasetVersionId(),
				currentlyEligible, List.of(), readiness, false);
		return new Fixture(version, members, streams, splitPlan, assignments, specification, snapshot,
				currentAdmission);
	}

	private static DatasetMemberSession member(DatasetVersion version, int candidateIndex, UUID captureSessionId) {
		return DatasetMemberSession.snapshot(version, candidateIndex,
				new CaptureSessionDatasetAdmissionFacts(captureSessionId, 1, 1, "COMPLETE", "PASS", List.of()));
	}

	private static DatasetMemberStream stream(DatasetVersion version, DatasetMemberSession member, long identity) {
		return DatasetMemberStream.snapshot(version, member,
				new DatasetAdmissionStreamEvidence(new UUID(0, identity), new UUID(0, identity + 100), 0,
						digest(identity % 2 == 0 ? 'f' : 'e'), "QUALIFIED", 1, new UUID(0, identity + 200),
						digest((char) ('1' + identity % 2)), LABEL_DIGEST, new UUID(0, identity + 300),
						new UUID(0, identity + 400), CLEANING_DIGEST, "PASS"));
	}

	private static String digest(char value) {
		return String.valueOf(value).repeat(64);
	}

	private record Fixture(DatasetVersion version, List<DatasetMemberSession> members,
			List<DatasetMemberStream> streams, DatasetSplitPlan splitPlan, List<DatasetSplitAssignment> assignments,
			DatasetBuildSpecification specification, DatasetBuildSnapshot snapshot,
			DatasetVersionCurrentAdmissionResponse currentAdmission) {
	}

}
