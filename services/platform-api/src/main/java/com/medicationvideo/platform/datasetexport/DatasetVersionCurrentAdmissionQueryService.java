package com.medicationvideo.platform.datasetexport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.assetcapture.CaptureSessionDatasetAdmissionFacts;
import com.medicationvideo.platform.iamproject.ProjectAccessService;

/**
 * Read-only comparison of a dataset draft with current admission facts and DRAFT build prerequisites.
 */
@Service
class DatasetVersionCurrentAdmissionQueryService {

	private static final Comparator<StreamEvidenceChange> STREAM_CHANGE_ORDER = Comparator
			.comparingInt(StreamEvidenceChange::cameraIndex)
			.thenComparing(StreamEvidenceChange::cameraStreamId);

	private final ProjectAccessService projectAccessService;
	private final DatasetVersionRepository versionRepository;
	private final DatasetMemberSessionRepository memberSessionRepository;
	private final DatasetMemberStreamRepository memberStreamRepository;
	private final DatasetSplitPlanRepository splitPlanRepository;
	private final DatasetBuildSpecificationRepository buildSpecificationRepository;
	private final DatasetAdmissionPreviewService admissionService;

	DatasetVersionCurrentAdmissionQueryService(
			ProjectAccessService projectAccessService,
			DatasetVersionRepository versionRepository,
			DatasetMemberSessionRepository memberSessionRepository,
			DatasetMemberStreamRepository memberStreamRepository,
			DatasetSplitPlanRepository splitPlanRepository,
			DatasetBuildSpecificationRepository buildSpecificationRepository,
			DatasetAdmissionPreviewService admissionService) {
		this.projectAccessService = projectAccessService;
		this.versionRepository = versionRepository;
		this.memberSessionRepository = memberSessionRepository;
		this.memberStreamRepository = memberStreamRepository;
		this.splitPlanRepository = splitPlanRepository;
		this.buildSpecificationRepository = buildSpecificationRepository;
		this.admissionService = admissionService;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	DatasetVersionCurrentAdmissionResponse get(UUID projectId, UUID datasetVersionId, String actorId) {
		this.projectAccessService.requireMember(projectId, actorId);
		var version = this.versionRepository.findByProjectIdAndDatasetVersionId(projectId, datasetVersionId)
				.orElseThrow(DatasetVersionCurrentAdmissionQueryService::notFound);
		var snapshotStreams = this.memberStreamRepository
				.findByDatasetVersionIdOrderByDatasetMemberSessionIdAscCameraIndexAsc(datasetVersionId);
		var streamsByMemberId = streamsByMemberId(snapshotStreams);
		var members = this.memberSessionRepository
				.findByDatasetVersionIdOrderByCandidateIndexAsc(datasetVersionId)
				.stream()
				.map(member -> currentAdmission(
						projectId,
						member,
						streamsByMemberId.getOrDefault(member.getDatasetMemberSessionId(), List.of())))
				.toList();
		var buildReadiness = DatasetVersionBuildReadinessResponse.from(
				members,
				snapshotStreams,
				this.splitPlanRepository.findByDatasetVersionId(datasetVersionId).orElse(null),
				this.buildSpecificationRepository.findByDatasetVersionId(datasetVersionId).orElse(null));
		return new DatasetVersionCurrentAdmissionResponse(
				version.getDatasetVersionId(),
				members.stream().allMatch(member -> member.currentAdmission().eligible()),
				members,
				buildReadiness);
	}

	private static Map<UUID, List<DatasetMemberStream>> streamsByMemberId(
			List<DatasetMemberStream> snapshotStreams) {
		var streamsByMemberId = new HashMap<UUID, List<DatasetMemberStream>>();
		for (var stream : snapshotStreams) {
			streamsByMemberId.computeIfAbsent(stream.getDatasetMemberSessionId(), ignored -> new ArrayList<>())
					.add(stream);
		}
		return streamsByMemberId;
	}

	private DatasetVersionCurrentAdmissionMemberResponse currentAdmission(
			UUID projectId, DatasetMemberSession snapshot, List<DatasetMemberStream> snapshotStreams) {
		var current = this.admissionService.evaluate(projectId, snapshot.getCaptureSessionId());
		return new DatasetVersionCurrentAdmissionMemberResponse(
				snapshot.getCaptureSessionId(),
				snapshot.getCandidateIndex(),
				current.preview(),
				evidenceChanges(snapshot, snapshotStreams, current));
	}

	private static List<DatasetVersionAdmissionEvidenceChange> evidenceChanges(
			DatasetMemberSession snapshot,
			List<DatasetMemberStream> snapshotStreams,
			DatasetAdmissionEvaluation current) {
		var changes = new ArrayList<DatasetVersionAdmissionEvidenceChange>();
		var sessionChangeCodes = sessionChangeCodes(snapshot, current.captureSession());
		if (!sessionChangeCodes.isEmpty()) {
			changes.add(new DatasetVersionAdmissionEvidenceChange(
					DatasetAdmissionEvidenceChangeScope.SESSION, null, sessionChangeCodes));
		}
		streamEvidenceChanges(snapshotStreams, current.streamEvidence()).stream()
				.sorted(STREAM_CHANGE_ORDER)
				.map(StreamEvidenceChange::response)
				.forEach(changes::add);
		return List.copyOf(changes);
	}

	private static List<String> sessionChangeCodes(
			DatasetMemberSession snapshot,
			CaptureSessionDatasetAdmissionFacts current) {
		var codes = new ArrayList<String>();
		if (snapshot.getExpectedCameraCount() != current.expectedCameraCount()) {
			codes.add("SESSION_EXPECTED_CAMERA_COUNT_CHANGED");
		}
		if (snapshot.getReceivedCameraCount() != current.receivedCameraCount()) {
			codes.add("SESSION_RECEIVED_CAMERA_COUNT_CHANGED");
		}
		if (!snapshot.getAssemblyStatus().equals(current.assemblyStatus())) {
			codes.add("SESSION_ASSEMBLY_STATUS_CHANGED");
		}
		if (!snapshot.getQualityStatus().equals(current.qualityStatus())) {
			codes.add("SESSION_QUALITY_STATUS_CHANGED");
		}
		return List.copyOf(codes);
	}

	private static List<StreamEvidenceChange> streamEvidenceChanges(
			List<DatasetMemberStream> snapshotStreams,
			List<DatasetAdmissionStreamEvidence> currentStreams) {
		var currentByCameraStreamId = currentStreamsByCameraStreamId(currentStreams);
		var changes = new ArrayList<StreamEvidenceChange>();
		for (var snapshot : snapshotStreams) {
			var current = currentByCameraStreamId.remove(snapshot.getCameraStreamId());
			if (current == null) {
				changes.add(new StreamEvidenceChange(
						snapshot.getCameraIndex(),
						snapshot.getCameraStreamId(),
						List.of("STREAM_REMOVED")));
				continue;
			}
			var codes = streamChangeCodes(snapshot, current);
			if (!codes.isEmpty()) {
				changes.add(new StreamEvidenceChange(snapshot.getCameraIndex(), snapshot.getCameraStreamId(), codes));
			}
		}
		for (var current : currentByCameraStreamId.values()) {
			changes.add(new StreamEvidenceChange(
					current.cameraIndex(), current.cameraStreamId(), List.of("STREAM_ADDED")));
		}
		return changes;
	}

	private static Map<UUID, DatasetAdmissionStreamEvidence> currentStreamsByCameraStreamId(
			List<DatasetAdmissionStreamEvidence> currentStreams) {
		var result = new HashMap<UUID, DatasetAdmissionStreamEvidence>();
		for (var current : currentStreams) {
			if (result.put(current.cameraStreamId(), current) != null) {
				throw new IllegalStateException("Current dataset admission facts contain duplicate camera streams.");
			}
		}
		return result;
	}

	private static List<String> streamChangeCodes(
			DatasetMemberStream snapshot, DatasetAdmissionStreamEvidence current) {
		var codes = new ArrayList<String>();
		if (!Objects.equals(snapshot.getAssetSha256(), current.assetSha256())) {
			codes.add("STREAM_ASSET_SHA256_CHANGED");
		}
		if (!Objects.equals(snapshot.getQualityReviewDecision(), current.qualityReviewDecision())) {
			codes.add("STREAM_QUALITY_REVIEW_DECISION_CHANGED");
		}
		if (snapshot.getQualityReviewVersion() != current.qualityReviewVersion()) {
			codes.add("STREAM_QUALITY_REVIEW_VERSION_CHANGED");
		}
		if (!Objects.equals(snapshot.getAnnotationRevisionId(), current.annotationRevisionId())) {
			codes.add("STREAM_ANNOTATION_REVISION_CHANGED");
		}
		if (!Objects.equals(snapshot.getAnnotationDocumentSha256(), current.annotationDocumentSha256())) {
			codes.add("STREAM_ANNOTATION_DOCUMENT_SHA256_CHANGED");
		}
		if (!Objects.equals(snapshot.getLabelSchemaDigest(), current.labelSchemaDigest())) {
			codes.add("STREAM_LABEL_SCHEMA_DIGEST_CHANGED");
		}
		if (!Objects.equals(snapshot.getCleaningResultId(), current.cleaningResultId())) {
			codes.add("STREAM_CLEANING_RESULT_CHANGED");
		}
		if (!Objects.equals(snapshot.getCleaningRunId(), current.cleaningRunId())) {
			codes.add("STREAM_CLEANING_RUN_CHANGED");
		}
		if (!Objects.equals(snapshot.getCleaningRuleSetDigest(), current.cleaningRuleSetDigest())) {
			codes.add("STREAM_CLEANING_RULE_SET_DIGEST_CHANGED");
		}
		if (!Objects.equals(snapshot.getPostCleaningOutcome(), current.postCleaningOutcome())) {
			codes.add("STREAM_POST_CLEANING_OUTCOME_CHANGED");
		}
		return List.copyOf(codes);
	}

	private static DatasetVersionException notFound() {
		return new DatasetVersionException(
				"DATASET_VERSION_NOT_FOUND",
				"The dataset version was not found for the current project.",
				404);
	}

	private record StreamEvidenceChange(int cameraIndex, UUID cameraStreamId, List<String> changeCodes) {

		DatasetVersionAdmissionEvidenceChange response() {
			return new DatasetVersionAdmissionEvidenceChange(
					DatasetAdmissionEvidenceChangeScope.STREAM, this.cameraStreamId, this.changeCodes);
		}
	}
}

record DatasetVersionCurrentAdmissionResponse(
		UUID datasetVersionId,
		boolean allMembersCurrentlyAdmissionEligible,
		List<DatasetVersionCurrentAdmissionMemberResponse> members,
		DatasetVersionBuildReadinessResponse buildReadiness) {

	DatasetVersionCurrentAdmissionResponse {
		members = List.copyOf(members);
	}
}

record DatasetVersionCurrentAdmissionMemberResponse(
		UUID captureSessionId,
		int candidateIndex,
		DatasetAdmissionPreviewResponse currentAdmission,
		List<DatasetVersionAdmissionEvidenceChange> evidenceChanges) {

	DatasetVersionCurrentAdmissionMemberResponse {
		evidenceChanges = List.copyOf(evidenceChanges);
	}
}

record DatasetVersionAdmissionEvidenceChange(
		DatasetAdmissionEvidenceChangeScope scope,
		UUID cameraStreamId,
		List<String> changeCodes) {

	DatasetVersionAdmissionEvidenceChange {
		changeCodes = List.copyOf(changeCodes);
	}
}

enum DatasetAdmissionEvidenceChangeScope {
	SESSION,
	STREAM
}
