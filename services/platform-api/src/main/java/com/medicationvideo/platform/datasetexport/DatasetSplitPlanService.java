package com.medicationvideo.platform.datasetexport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;
import com.medicationvideo.platform.iamproject.ProjectAccessService;

@Service
class DatasetSplitPlanService {

	private final ProjectAccessService projectAccessService;
	private final DatasetVersionRepository versionRepository;
	private final DatasetMemberSessionRepository memberRepository;
	private final DatasetSplitPlanRepository planRepository;
	private final DatasetSplitAssignmentRepository assignmentRepository;
	private final DatasetSplitPlanner planner;
	private final AuditEventRecorder auditRecorder;

	DatasetSplitPlanService(
			ProjectAccessService projectAccessService,
			DatasetVersionRepository versionRepository,
			DatasetMemberSessionRepository memberRepository,
			DatasetSplitPlanRepository planRepository,
			DatasetSplitAssignmentRepository assignmentRepository,
			DatasetSplitPlanner planner,
			AuditEventRecorder auditRecorder) {
		this.projectAccessService = projectAccessService;
		this.versionRepository = versionRepository;
		this.memberRepository = memberRepository;
		this.planRepository = planRepository;
		this.assignmentRepository = assignmentRepository;
		this.planner = planner;
		this.auditRecorder = auditRecorder;
	}

	@Transactional
	DatasetSplitPlanResponse create(
			UUID projectId,
			UUID datasetVersionId,
			String actorId,
			String idempotencyKey,
			String requestId,
			String traceId,
			int holdoutCount) {
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		var fingerprint = requestFingerprint(datasetVersionId, holdoutCount);
		var replay = this.planRepository.findByProjectIdAndCreatedByAndIdempotencyKey(
				projectId, actorId, idempotencyKey);
		if (replay.isPresent()) {
			if (!replay.get().matchesCreateRequest(fingerprint)) {
				throw new DatasetVersionException(
						"DATASET_SPLIT_PLAN_IDEMPOTENCY_KEY_REUSED",
						"The idempotency key was already used for a different split plan.", 409);
			}
			return response(replay.get());
		}

		var version = this.versionRepository.findByProjectIdAndDatasetVersionId(projectId, datasetVersionId)
				.orElseThrow(DatasetSplitPlanService::notFound);
		if (this.planRepository.findByDatasetVersionId(datasetVersionId).isPresent()) {
			throw new DatasetVersionException(
					"DATASET_SPLIT_PLAN_ALREADY_EXISTS",
					"The dataset draft already has an immutable split plan.", 409);
		}
		var members = this.memberRepository.findByDatasetVersionIdOrderByCandidateIndexAsc(datasetVersionId);
		if (members.size() < 2) {
			throw new DatasetVersionException(
					"DATASET_SPLIT_PLAN_TOO_FEW_MEMBERS",
					"At least two capture sessions are required to create a pilot/holdout split.", 409);
		}
		if (holdoutCount < 1 || holdoutCount >= members.size()) {
			throw new DatasetVersionException(
					"DATASET_SPLIT_PLAN_HOLDOUT_COUNT_INVALID",
					"The holdout count must leave at least one capture session in each split.", 409,
					Map.of("member_count", Integer.toString(members.size())));
		}

		var planning = this.planner.plan(members, holdoutCount);
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var plan = this.planRepository.saveAndFlush(DatasetSplitPlan.create(
				version,
				planning.splitSeed(),
				holdoutCount,
				actorId,
				idempotencyKey,
				fingerprint,
				now));
		var assignments = planning.assignments().stream()
				.map(item -> DatasetSplitAssignment.assign(
						plan, item.member(), item.split(), item.assignmentRank()))
				.toList();
		this.assignmentRepository.saveAll(assignments);
		this.auditRecorder.record(new AuditEventCommand(
				now,
				AuditActorType.USER,
				actorId,
				projectId,
				"DATASET_SPLIT_PLAN_CREATED",
				"DatasetSplitPlan",
				plan.getDatasetSplitPlanId().toString(),
				DatasetSplitPolicy.CAPTURE_SESSION_HASH_RANK_V1.name(),
				AuditOutcome.SUCCEEDED,
				"IMMUTABLE_PILOT_HOLDOUT_PLAN_CREATED",
				requestId,
				traceId,
				"PROJECT_MEMBERSHIP_ADMIN",
				Map.of(
						"dataset_version_id", datasetVersionId.toString(),
						"member_count", Integer.toString(members.size()),
						"pilot_count", Integer.toString(members.size() - holdoutCount),
						"holdout_count", Integer.toString(holdoutCount),
						"split_seed", Integer.toString(planning.splitSeed()),
						"request_fingerprint", fingerprint)));
		return DatasetSplitPlanResponse.from(plan, assignments);
	}

	private DatasetSplitPlanResponse response(DatasetSplitPlan plan) {
		return DatasetSplitPlanResponse.from(
				plan,
				this.assignmentRepository.findByDatasetSplitPlanIdOrderByCandidateIndexAsc(
						plan.getDatasetSplitPlanId()));
	}

	private static DatasetVersionException notFound() {
		return new DatasetVersionException(
				"DATASET_VERSION_NOT_FOUND",
				"The dataset version was not found for the current project.",
				404);
	}

	static String requestFingerprint(UUID datasetVersionId, int holdoutCount) {
		var canonical = DatasetSplitPolicy.CAPTURE_SESSION_HASH_RANK_V1.name()
				+ "\n" + datasetVersionId
				+ "\n" + holdoutCount;
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 must be available.", exception);
		}
	}
}
