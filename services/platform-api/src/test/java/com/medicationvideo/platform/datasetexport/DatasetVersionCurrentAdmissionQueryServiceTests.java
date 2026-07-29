package com.medicationvideo.platform.datasetexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.medicationvideo.platform.assetcapture.CaptureSessionDatasetAdmissionFacts;
import com.medicationvideo.platform.iamproject.ProjectAccessService;
import com.medicationvideo.platform.iamproject.ProjectRole;

class DatasetVersionCurrentAdmissionQueryServiceTests {

	private static final UUID PROJECT_ID = uuid(1);
	private static final UUID SESSION_ONE_ID = uuid(101);
	private static final UUID SESSION_TWO_ID = uuid(102);
	private static final UUID STREAM_ONE_ID = uuid(201);
	private static final UUID STREAM_TWO_ID = uuid(202);
	private static final UUID STREAM_THREE_ID = uuid(203);
	private static final UUID STREAM_FOUR_ID = uuid(204);
	private static final String ACTOR_ID = "dataset.member";

	@Test
	void returnsTheCurrentAdmissionWithoutChangesWhenEverySnapshotFactStillMatches() {
		var access = mock(ProjectAccessService.class);
		var versions = mock(DatasetVersionRepository.class);
		var sessions = mock(DatasetMemberSessionRepository.class);
		var streams = mock(DatasetMemberStreamRepository.class);
		var splitPlans = mock(DatasetSplitPlanRepository.class);
		var buildSpecifications = mock(DatasetBuildSpecificationRepository.class);
		var admission = mock(DatasetAdmissionPreviewService.class);
		var version = version();
		var evidence = evidence(STREAM_ONE_ID, 0, 1, 'a', "QUALIFIED", "PASS");
		var facts = facts(SESSION_ONE_ID, 1, 1, "COMPLETE", "PASS", List.of(evidence));
		var member = DatasetMemberSession.snapshot(version, 0, facts);
		var snapshotStream = DatasetMemberStream.snapshot(version, member, evidence);
		var current = evaluation(facts, true, List.of(evidence));
		when(versions.findByProjectIdAndDatasetVersionId(PROJECT_ID, version.getDatasetVersionId()))
				.thenReturn(Optional.of(version));
		when(sessions.findByDatasetVersionIdOrderByCandidateIndexAsc(version.getDatasetVersionId()))
				.thenReturn(List.of(member));
		when(streams.findByDatasetVersionIdOrderByDatasetMemberSessionIdAscCameraIndexAsc(version.getDatasetVersionId()))
				.thenReturn(List.of(snapshotStream));
		when(admission.evaluate(PROJECT_ID, SESSION_ONE_ID)).thenReturn(current);

		var response = service(access, versions, sessions, streams, splitPlans, buildSpecifications, admission)
				.get(PROJECT_ID, version.getDatasetVersionId(), ACTOR_ID);

		assertEquals(version.getDatasetVersionId(), response.datasetVersionId());
		assertTrue(response.allMembersCurrentlyAdmissionEligible());
		assertEquals(1, response.members().size());
		assertEquals(SESSION_ONE_ID, response.members().getFirst().captureSessionId());
		assertEquals(0, response.members().getFirst().candidateIndex());
		assertEquals(current.preview(), response.members().getFirst().currentAdmission());
		assertTrue(response.members().getFirst().evidenceChanges().isEmpty());
		assertFalse(response.buildReadiness().readyForBuild());
		assertEquals(List.of(
				DatasetVersionBuildBlocker.SELECTION_RULE_NOT_FIXED,
				DatasetVersionBuildBlocker.LABEL_SCHEMA_NOT_FIXED,
				DatasetVersionBuildBlocker.CLEANING_RULE_SET_NOT_FIXED,
				DatasetVersionBuildBlocker.SPLIT_PLAN_NOT_FIXED,
				DatasetVersionBuildBlocker.EXPORT_PROFILE_NOT_FIXED),
				response.buildReadiness().blockingReasons());
		assertTrue(response.buildReadiness().immutableEvidenceStillCurrent());
		assertEquals(digest('a'), response.buildReadiness().uniformLabelSchemaDigest());
		assertEquals(digest('a'), response.buildReadiness().uniformCleaningRuleSetDigest());
		assertNull(response.buildReadiness().datasetSplitPlanId());
		verify(access).requireMember(PROJECT_ID, ACTOR_ID);
	}

	@Test
	void reportsEverySessionAndEvidenceDifferenceIncludingQualityVersionAndCleaningRerun() {
		var access = mock(ProjectAccessService.class);
		var versions = mock(DatasetVersionRepository.class);
		var sessions = mock(DatasetMemberSessionRepository.class);
		var streams = mock(DatasetMemberStreamRepository.class);
		var splitPlans = mock(DatasetSplitPlanRepository.class);
		var buildSpecifications = mock(DatasetBuildSpecificationRepository.class);
		var admission = mock(DatasetAdmissionPreviewService.class);
		var version = version();
		var snapshotEvidence = evidence(STREAM_ONE_ID, 0, 1, 'a', "QUALIFIED", "PASS");
		var snapshotFacts = facts(SESSION_ONE_ID, 1, 1, "COMPLETE", "PASS", List.of(snapshotEvidence));
		var member = DatasetMemberSession.snapshot(version, 0, snapshotFacts);
		var snapshotStream = DatasetMemberStream.snapshot(version, member, snapshotEvidence);
		var currentEvidence = evidence(STREAM_ONE_ID, 0, 2, 'b', "REJECT", "FAIL");
		var currentFacts = facts(SESSION_ONE_ID, 2, 0, "INCOMPLETE", "WARNING", List.of(currentEvidence));
		when(versions.findByProjectIdAndDatasetVersionId(PROJECT_ID, version.getDatasetVersionId()))
				.thenReturn(Optional.of(version));
		when(sessions.findByDatasetVersionIdOrderByCandidateIndexAsc(version.getDatasetVersionId()))
				.thenReturn(List.of(member));
		when(streams.findByDatasetVersionIdOrderByDatasetMemberSessionIdAscCameraIndexAsc(version.getDatasetVersionId()))
				.thenReturn(List.of(snapshotStream));
		when(admission.evaluate(PROJECT_ID, SESSION_ONE_ID))
				.thenReturn(evaluation(currentFacts, false, List.of(currentEvidence)));

		var response = service(access, versions, sessions, streams, splitPlans, buildSpecifications, admission)
				.get(PROJECT_ID, version.getDatasetVersionId(), ACTOR_ID);

		assertFalse(response.allMembersCurrentlyAdmissionEligible());
		var changes = response.members().getFirst().evidenceChanges();
		assertEquals(2, changes.size());
		assertEquals(DatasetAdmissionEvidenceChangeScope.SESSION, changes.get(0).scope());
		assertNull(changes.get(0).cameraStreamId());
		assertEquals(List.of(
				"SESSION_EXPECTED_CAMERA_COUNT_CHANGED",
				"SESSION_RECEIVED_CAMERA_COUNT_CHANGED",
				"SESSION_ASSEMBLY_STATUS_CHANGED",
				"SESSION_QUALITY_STATUS_CHANGED"), changes.get(0).changeCodes());
		assertEquals(DatasetAdmissionEvidenceChangeScope.STREAM, changes.get(1).scope());
		assertEquals(STREAM_ONE_ID, changes.get(1).cameraStreamId());
		assertEquals(List.of(
				"STREAM_ASSET_SHA256_CHANGED",
				"STREAM_QUALITY_REVIEW_DECISION_CHANGED",
				"STREAM_QUALITY_REVIEW_VERSION_CHANGED",
				"STREAM_ANNOTATION_REVISION_CHANGED",
				"STREAM_ANNOTATION_DOCUMENT_SHA256_CHANGED",
				"STREAM_LABEL_SCHEMA_DIGEST_CHANGED",
				"STREAM_CLEANING_RESULT_CHANGED",
				"STREAM_CLEANING_RUN_CHANGED",
				"STREAM_CLEANING_RULE_SET_DIGEST_CHANGED",
				"STREAM_POST_CLEANING_OUTCOME_CHANGED"), changes.get(1).changeCodes());
		assertFalse(response.buildReadiness().immutableEvidenceStillCurrent());
		assertTrue(response.buildReadiness().blockingReasons()
				.contains(DatasetVersionBuildBlocker.CURRENT_ADMISSION_NOT_ELIGIBLE));
		assertTrue(response.buildReadiness().blockingReasons()
				.contains(DatasetVersionBuildBlocker.SNAPSHOT_EVIDENCE_CHANGED));
	}

	@Test
	void ordersMembersByCandidateIndexAndAggregatesStreamChangesByCameraIndex() {
		var access = mock(ProjectAccessService.class);
		var versions = mock(DatasetVersionRepository.class);
		var sessions = mock(DatasetMemberSessionRepository.class);
		var streams = mock(DatasetMemberStreamRepository.class);
		var splitPlans = mock(DatasetSplitPlanRepository.class);
		var buildSpecifications = mock(DatasetBuildSpecificationRepository.class);
		var admission = mock(DatasetAdmissionPreviewService.class);
		var version = version();

		var firstEvidence = evidence(STREAM_ONE_ID, 0, 1, 'a', "QUALIFIED", "PASS");
		var firstFacts = facts(SESSION_ONE_ID, 1, 1, "COMPLETE", "PASS", List.of(firstEvidence));
		var firstMember = DatasetMemberSession.snapshot(version, 0, firstFacts);
		var firstSnapshotStream = DatasetMemberStream.snapshot(version, firstMember, firstEvidence);

		var removedEvidence = evidence(STREAM_TWO_ID, 1, 2, 'b', "QUALIFIED", "PASS");
		var changedSnapshotEvidence = evidence(STREAM_THREE_ID, 2, 3, 'c', "QUALIFIED", "PASS");
		var secondSnapshotFacts = facts(
				SESSION_TWO_ID, 2, 2, "COMPLETE", "PASS", List.of(removedEvidence, changedSnapshotEvidence));
		var secondMember = DatasetMemberSession.snapshot(version, 1, secondSnapshotFacts);
		var removedSnapshotStream = DatasetMemberStream.snapshot(version, secondMember, removedEvidence);
		var changedSnapshotStream = DatasetMemberStream.snapshot(version, secondMember, changedSnapshotEvidence);
		var addedEvidence = evidence(STREAM_FOUR_ID, 0, 4, 'd', "QUALIFIED", "PASS");
		var changedCurrentEvidence = evidence(STREAM_THREE_ID, 2, 5, 'e', "QUALIFIED", "PASS");
		var secondCurrentFacts = facts(
				SESSION_TWO_ID, 2, 2, "COMPLETE", "PASS", List.of(addedEvidence, changedCurrentEvidence));

		when(versions.findByProjectIdAndDatasetVersionId(PROJECT_ID, version.getDatasetVersionId()))
				.thenReturn(Optional.of(version));
		when(sessions.findByDatasetVersionIdOrderByCandidateIndexAsc(version.getDatasetVersionId()))
				.thenReturn(List.of(firstMember, secondMember));
		when(streams.findByDatasetVersionIdOrderByDatasetMemberSessionIdAscCameraIndexAsc(version.getDatasetVersionId()))
				.thenReturn(List.of(changedSnapshotStream, firstSnapshotStream, removedSnapshotStream));
		when(admission.evaluate(PROJECT_ID, SESSION_ONE_ID)).thenReturn(evaluation(firstFacts, true, List.of(firstEvidence)));
		when(admission.evaluate(PROJECT_ID, SESSION_TWO_ID))
				.thenReturn(evaluation(secondCurrentFacts, true, List.of(addedEvidence, changedCurrentEvidence)));
		var splitPlan = DatasetSplitPlan.create(
				version,
				20260729,
				1,
				"dataset.admin",
				"dataset-split-readiness-0001",
				digest('f'),
				Instant.EPOCH);
		when(splitPlans.findByDatasetVersionId(version.getDatasetVersionId())).thenReturn(Optional.of(splitPlan));

		var response = service(access, versions, sessions, streams, splitPlans, buildSpecifications, admission)
				.get(PROJECT_ID, version.getDatasetVersionId(), ACTOR_ID);

		assertTrue(response.allMembersCurrentlyAdmissionEligible());
		assertEquals(List.of(0, 1), response.members().stream()
				.map(DatasetVersionCurrentAdmissionMemberResponse::candidateIndex).toList());
		assertTrue(response.members().getFirst().evidenceChanges().isEmpty());
		var changes = response.members().get(1).evidenceChanges();
		assertEquals(List.of(STREAM_FOUR_ID, STREAM_TWO_ID, STREAM_THREE_ID), changes.stream()
				.map(DatasetVersionAdmissionEvidenceChange::cameraStreamId).toList());
		assertEquals(List.of("STREAM_ADDED"), changes.get(0).changeCodes());
		assertEquals(List.of("STREAM_REMOVED"), changes.get(1).changeCodes());
		assertEquals(List.of(
				"STREAM_ASSET_SHA256_CHANGED",
				"STREAM_QUALITY_REVIEW_VERSION_CHANGED",
				"STREAM_ANNOTATION_REVISION_CHANGED",
				"STREAM_ANNOTATION_DOCUMENT_SHA256_CHANGED",
				"STREAM_LABEL_SCHEMA_DIGEST_CHANGED",
				"STREAM_CLEANING_RESULT_CHANGED",
				"STREAM_CLEANING_RUN_CHANGED",
				"STREAM_CLEANING_RULE_SET_DIGEST_CHANGED"), changes.get(2).changeCodes());
		assertEquals(splitPlan.getDatasetSplitPlanId(), response.buildReadiness().datasetSplitPlanId());
		assertFalse(response.buildReadiness().blockingReasons()
				.contains(DatasetVersionBuildBlocker.SPLIT_PLAN_NOT_FIXED));
		assertTrue(response.buildReadiness().blockingReasons()
				.contains(DatasetVersionBuildBlocker.LABEL_SCHEMA_NOT_UNIFORM));
		assertTrue(response.buildReadiness().blockingReasons()
				.contains(DatasetVersionBuildBlocker.CLEANING_RULE_SET_NOT_UNIFORM));
		assertNull(response.buildReadiness().uniformLabelSchemaDigest());
		assertNull(response.buildReadiness().uniformCleaningRuleSetDigest());
	}

	@Test
	void reportsReadyOnlyAfterTheImmutableBuildSpecificationIsFixed() {
		var access = mock(ProjectAccessService.class);
		var versions = mock(DatasetVersionRepository.class);
		var sessions = mock(DatasetMemberSessionRepository.class);
		var streams = mock(DatasetMemberStreamRepository.class);
		var splitPlans = mock(DatasetSplitPlanRepository.class);
		var buildSpecifications = mock(DatasetBuildSpecificationRepository.class);
		var admission = mock(DatasetAdmissionPreviewService.class);
		var version = version();
		var evidence = evidence(STREAM_ONE_ID, 0, 1, 'a', "QUALIFIED", "PASS");
		var facts = facts(SESSION_ONE_ID, 1, 1, "COMPLETE", "PASS", List.of(evidence));
		var member = DatasetMemberSession.snapshot(version, 0, facts);
		var snapshotStream = DatasetMemberStream.snapshot(version, member, evidence);
		var splitPlan = DatasetSplitPlan.create(
				version,
				20260729,
				1,
				"dataset.admin",
				"dataset-split-readiness-0002",
				digest('f'),
				Instant.EPOCH);
		var specification = DatasetBuildSpecification.create(
				version,
				digest('a'),
				digest('a'),
				splitPlan,
				"dataset.admin",
				"dataset-build-readiness-0001",
				digest('e'),
				Instant.EPOCH);
		when(versions.findByProjectIdAndDatasetVersionId(PROJECT_ID, version.getDatasetVersionId()))
				.thenReturn(Optional.of(version));
		when(access.requireMember(PROJECT_ID, ACTOR_ID)).thenReturn(ProjectRole.ADMIN);
		when(sessions.findByDatasetVersionIdOrderByCandidateIndexAsc(version.getDatasetVersionId()))
				.thenReturn(List.of(member));
		when(streams.findByDatasetVersionIdOrderByDatasetMemberSessionIdAscCameraIndexAsc(version.getDatasetVersionId()))
				.thenReturn(List.of(snapshotStream));
		when(splitPlans.findByDatasetVersionId(version.getDatasetVersionId())).thenReturn(Optional.of(splitPlan));
		when(buildSpecifications.findByDatasetVersionId(version.getDatasetVersionId()))
				.thenReturn(Optional.of(specification));
		when(admission.evaluate(PROJECT_ID, SESSION_ONE_ID))
				.thenReturn(evaluation(facts, true, List.of(evidence)));

		var response = service(
				access, versions, sessions, streams, splitPlans, buildSpecifications, admission)
				.get(PROJECT_ID, version.getDatasetVersionId(), ACTOR_ID);

		assertTrue(response.buildReadiness().readyForBuild());
		assertTrue(response.canStartBuild());
		assertTrue(response.buildReadiness().blockingReasons().isEmpty());
		assertEquals(
				specification.getDatasetBuildSpecificationId(),
				response.buildReadiness().buildSpecification().datasetBuildSpecificationId());
	}

	private static DatasetVersionCurrentAdmissionQueryService service(
			ProjectAccessService access,
			DatasetVersionRepository versions,
			DatasetMemberSessionRepository sessions,
			DatasetMemberStreamRepository streams,
			DatasetSplitPlanRepository splitPlans,
			DatasetBuildSpecificationRepository buildSpecifications,
			DatasetAdmissionPreviewService admission) {
		return new DatasetVersionCurrentAdmissionQueryService(
				access, versions, sessions, streams, splitPlans, buildSpecifications, admission);
	}

	private static DatasetVersion version() {
		return DatasetVersion.create(
				PROJECT_ID,
				"Dataset draft",
				"dataset.admin",
				"dataset-version-current-admission-0001",
				"a".repeat(64),
				Instant.EPOCH);
	}

	private static DatasetAdmissionEvaluation evaluation(
			CaptureSessionDatasetAdmissionFacts facts,
			boolean eligible,
			List<DatasetAdmissionStreamEvidence> evidence) {
		return new DatasetAdmissionEvaluation(
				facts,
				new DatasetAdmissionPreviewResponse(
						facts.captureSessionId(),
						eligible,
						facts.assemblyStatus(),
						facts.qualityStatus(),
						facts.expectedCameraCount(),
						facts.receivedCameraCount(),
						List.of(),
						List.of()),
				evidence);
	}

	private static CaptureSessionDatasetAdmissionFacts facts(
			UUID sessionId,
			int expectedCameraCount,
			int receivedCameraCount,
			String assemblyStatus,
			String qualityStatus,
			List<DatasetAdmissionStreamEvidence> evidence) {
		return new CaptureSessionDatasetAdmissionFacts(
				sessionId,
				expectedCameraCount,
				receivedCameraCount,
				assemblyStatus,
				qualityStatus,
				evidence.stream()
						.map(item -> new CaptureSessionDatasetAdmissionFacts.Stream(
								item.cameraStreamId(),
								item.clipAssetId(),
								item.cameraIndex(),
								"INGESTED",
								"PASS",
								"INGESTED",
								false,
								item.assetSha256()))
						.toList());
	}

	private static DatasetAdmissionStreamEvidence evidence(
			UUID streamId,
			int cameraIndex,
			long sequence,
			char digestCharacter,
			String qualityDecision,
			String postCleaningOutcome) {
		return new DatasetAdmissionStreamEvidence(
				streamId,
				uuid(1_000 + sequence),
				cameraIndex,
				digest(digestCharacter),
				qualityDecision,
				sequence,
				uuid(2_000 + sequence),
				digest(digestCharacter),
				digest(digestCharacter),
				uuid(3_000 + sequence),
				uuid(4_000 + sequence),
				digest(digestCharacter),
				postCleaningOutcome);
	}

	private static UUID uuid(long value) {
		return new UUID(0, value);
	}

	private static String digest(char character) {
		return String.valueOf(character).repeat(64);
	}
}
