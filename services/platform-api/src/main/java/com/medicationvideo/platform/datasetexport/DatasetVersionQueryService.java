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

	DatasetVersionQueryService(
			ProjectAccessService projectAccessService,
			DatasetVersionRepository versionRepository,
			DatasetMemberSessionRepository memberSessionRepository,
			DatasetMemberStreamRepository memberStreamRepository,
			DatasetSplitPlanRepository splitPlanRepository,
			DatasetSplitAssignmentRepository splitAssignmentRepository) {
		this.projectAccessService = projectAccessService;
		this.versionRepository = versionRepository;
		this.memberSessionRepository = memberSessionRepository;
		this.memberStreamRepository = memberStreamRepository;
		this.splitPlanRepository = splitPlanRepository;
		this.splitAssignmentRepository = splitAssignmentRepository;
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
		var streamsByMemberId = new HashMap<UUID, List<DatasetMemberStreamResponse>>();
		for (var stream : this.memberStreamRepository
				.findByDatasetVersionIdOrderByDatasetMemberSessionIdAscCameraIndexAsc(datasetVersionId)) {
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
		return DatasetVersionDetailResponse.from(
				version,
				members,
				splitPlan,
				role == ProjectRole.ADMIN && splitPlan == null && members.size() >= 2);
	}

	private static DatasetVersionException notFound() {
		return new DatasetVersionException(
				"DATASET_VERSION_NOT_FOUND",
				"The dataset version was not found for the current project.",
				404);
	}
}
