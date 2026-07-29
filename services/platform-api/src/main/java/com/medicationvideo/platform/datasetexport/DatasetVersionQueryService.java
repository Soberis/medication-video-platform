package com.medicationvideo.platform.datasetexport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.iamproject.ProjectAccessService;
import com.medicationvideo.platform.iamproject.ProjectRole;

@Service
class DatasetVersionQueryService {

	private final ProjectAccessService projectAccessService;
	private final DatasetVersionRepository versionRepository;
	private final DatasetMemberSessionRepository memberSessionRepository;
	private final DatasetMemberStreamRepository memberStreamRepository;
	private final DatasetSplitPlanRepository splitPlanRepository;
	private final DatasetSplitAssignmentRepository splitAssignmentRepository;
	private final DatasetBuildSpecificationRepository buildSpecificationRepository;
	private final DatasetBuildSnapshotRepository buildSnapshotRepository;

	DatasetVersionQueryService(
			ProjectAccessService projectAccessService,
			DatasetVersionRepository versionRepository,
			DatasetMemberSessionRepository memberSessionRepository,
			DatasetMemberStreamRepository memberStreamRepository,
			DatasetSplitPlanRepository splitPlanRepository,
			DatasetSplitAssignmentRepository splitAssignmentRepository,
			DatasetBuildSpecificationRepository buildSpecificationRepository,
			DatasetBuildSnapshotRepository buildSnapshotRepository) {
		this.projectAccessService = projectAccessService;
		this.versionRepository = versionRepository;
		this.memberSessionRepository = memberSessionRepository;
		this.memberStreamRepository = memberStreamRepository;
		this.splitPlanRepository = splitPlanRepository;
		this.splitAssignmentRepository = splitAssignmentRepository;
		this.buildSpecificationRepository = buildSpecificationRepository;
		this.buildSnapshotRepository = buildSnapshotRepository;
	}

	@Transactional(readOnly = true)
	DatasetVersionListResponse list(
			UUID projectId, String actorId, String cursor, int limit) {
		this.projectAccessService.requireMember(projectId, actorId);
		var decodedCursor = cursor == null ? null : DatasetVersionCursor.decode(cursor);
		var rows = decodedCursor == null
				? this.versionRepository.findFirstPage(projectId, limit + 1)
				: this.versionRepository.findPageAfter(
						projectId,
						decodedCursor.createdAt(),
						decodedCursor.datasetVersionId(),
						limit + 1);
		var page = rows.size() > limit ? rows.subList(0, limit) : rows;
		var nextCursor = rows.size() > limit
				? DatasetVersionCursor.encode(
						page.getLast().getCreatedAt(), page.getLast().getDatasetVersionId())
				: null;
		return new DatasetVersionListResponse(
				page.stream().map(DatasetVersionResponse::from).toList(), nextCursor);
	}

	@Transactional(readOnly = true)
	DatasetVersionDetailResponse get(UUID projectId, UUID datasetVersionId, String actorId) {
		var role = this.projectAccessService.requireMember(projectId, actorId);
		var version = this.versionRepository.findByProjectIdAndDatasetVersionId(projectId, datasetVersionId)
				.orElseThrow(DatasetVersionQueryService::notFound);
		var snapshotStreams = this.memberStreamRepository
				.findByDatasetVersionIdOrderByDatasetMemberSessionIdAscCameraIndexAsc(datasetVersionId);
		var streamsByMemberId = new HashMap<UUID, List<DatasetMemberStreamResponse>>();
		for (var stream : snapshotStreams) {
			streamsByMemberId.computeIfAbsent(stream.getDatasetMemberSessionId(), ignored -> new ArrayList<>())
					.add(DatasetMemberStreamResponse.from(stream));
		}
		var members = this.memberSessionRepository
				.findByDatasetVersionIdOrderByCandidateIndexAsc(datasetVersionId)
				.stream()
				.map(member -> DatasetMemberSessionResponse.from(
						member,
						streamsByMemberId.getOrDefault(member.getDatasetMemberSessionId(), List.of())))
				.toList();
		var splitPlan = this.splitPlanRepository.findByDatasetVersionId(datasetVersionId)
				.map(plan -> DatasetSplitPlanResponse.from(
						plan,
						this.splitAssignmentRepository.findByDatasetSplitPlanIdOrderByCandidateIndexAsc(
								plan.getDatasetSplitPlanId())))
				.orElse(null);
		var specification = this.buildSpecificationRepository.findByDatasetVersionId(datasetVersionId)
				.orElse(null);
		var buildSnapshot = this.buildSnapshotRepository.findByDatasetVersionId(datasetVersionId)
				.orElse(null);
		var buildEvidence = DatasetBuildEvidence.from(snapshotStreams);
		return DatasetVersionDetailResponse.from(
				version,
				members,
				splitPlan,
				role == ProjectRole.ADMIN
						&& version.getStatus() == DatasetVersionStatus.DRAFT
						&& splitPlan == null
						&& members.size() >= 2,
				specification == null ? null : DatasetBuildSpecificationResponse.from(specification),
				role == ProjectRole.ADMIN
						&& version.getStatus() == DatasetVersionStatus.DRAFT
						&& splitPlan != null
						&& specification == null
						&& buildEvidence.isComplete(),
				buildSnapshot == null ? null : DatasetBuildSnapshotResponse.from(buildSnapshot),
				role == ProjectRole.ADMIN
						&& version.getStatus() == DatasetVersionStatus.BUILDING
						&& buildSnapshot == null);
	}

	private static DatasetVersionException notFound() {
		return new DatasetVersionException(
				"DATASET_VERSION_NOT_FOUND",
				"The dataset version was not found for the current project.",
				404);
	}
}
