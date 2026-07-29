package com.medicationvideo.platform.datasetexport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;
import com.medicationvideo.platform.iamproject.ProjectAccessService;

@Service
class DatasetBuildSnapshotService {

	private static final String COMMAND_VERSION = "DATASET_BUILD_SNAPSHOT_CREATE_V1";

	private final ProjectAccessService projectAccessService;
	private final DatasetVersionRepository versionRepository;
	private final DatasetMemberSessionRepository memberRepository;
	private final DatasetMemberStreamRepository streamRepository;
	private final DatasetSplitPlanRepository splitPlanRepository;
	private final DatasetSplitAssignmentRepository assignmentRepository;
	private final DatasetBuildSpecificationRepository specificationRepository;
	private final DatasetBuildSnapshotRepository snapshotRepository;
	private final AuditEventRecorder auditRecorder;

	DatasetBuildSnapshotService(
			ProjectAccessService projectAccessService,
			DatasetVersionRepository versionRepository,
			DatasetMemberSessionRepository memberRepository,
			DatasetMemberStreamRepository streamRepository,
			DatasetSplitPlanRepository splitPlanRepository,
			DatasetSplitAssignmentRepository assignmentRepository,
			DatasetBuildSpecificationRepository specificationRepository,
			DatasetBuildSnapshotRepository snapshotRepository,
			AuditEventRecorder auditRecorder) {
		this.projectAccessService = projectAccessService;
		this.versionRepository = versionRepository;
		this.memberRepository = memberRepository;
		this.streamRepository = streamRepository;
		this.splitPlanRepository = splitPlanRepository;
		this.assignmentRepository = assignmentRepository;
		this.specificationRepository = specificationRepository;
		this.snapshotRepository = snapshotRepository;
		this.auditRecorder = auditRecorder;
	}

	@Transactional(isolation = Isolation.REPEATABLE_READ)
	DatasetBuildSnapshotResponse create(
			UUID projectId,
			UUID datasetVersionId,
			String actorId,
			String idempotencyKey,
			String requestId,
			String traceId) {
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		var fingerprint = requestFingerprint(datasetVersionId);
		var replay = this.snapshotRepository.findByProjectIdAndCreatedByAndIdempotencyKey(
				projectId, actorId, idempotencyKey);
		if (replay.isPresent()) {
			if (!replay.get().matchesCreateRequest(fingerprint)) {
				throw new DatasetVersionException(
						"DATASET_BUILD_SNAPSHOT_IDEMPOTENCY_KEY_REUSED",
						"The idempotency key was already used for a different dataset build snapshot.", 409);
			}
			return DatasetBuildSnapshotResponse.from(replay.get());
		}

		var version = this.versionRepository.findByProjectIdAndDatasetVersionId(projectId, datasetVersionId)
				.orElseThrow(DatasetBuildSnapshotService::notFound);
		if (version.getStatus() != DatasetVersionStatus.BUILDING) {
			throw new DatasetVersionException(
					"DATASET_BUILD_SNAPSHOT_STATUS_INVALID",
					"A build snapshot can only be sealed while the dataset version is building.", 409);
		}
		if (this.snapshotRepository.findByDatasetVersionId(datasetVersionId).isPresent()) {
			throw new DatasetVersionException(
					"DATASET_BUILD_SNAPSHOT_ALREADY_EXISTS",
					"The dataset version already has an immutable build snapshot.", 409);
		}

		var specification = this.specificationRepository.findByDatasetVersionId(datasetVersionId)
				.orElseThrow(() -> incomplete("BUILD_SPECIFICATION_MISSING"));
		var splitPlan = this.splitPlanRepository.findByDatasetVersionId(datasetVersionId)
				.orElseThrow(() -> incomplete("SPLIT_PLAN_MISSING"));
		var members = this.memberRepository.findByDatasetVersionIdOrderByCandidateIndexAsc(datasetVersionId);
		var streams = this.streamRepository
				.findByDatasetVersionIdOrderByDatasetMemberSessionIdAscCameraIndexAsc(datasetVersionId);
		var assignments = this.assignmentRepository
				.findByDatasetSplitPlanIdOrderByCandidateIndexAsc(splitPlan.getDatasetSplitPlanId());
		validateComplete(specification, splitPlan, members, streams, assignments);

		var digest = DatasetBuildSnapshotDigest.compute(
				version, specification, splitPlan, members, streams, assignments);
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var snapshot = this.snapshotRepository.saveAndFlush(DatasetBuildSnapshot.create(
				version,
				specification,
				splitPlan,
				members.size(),
				streams.size(),
				digest,
				actorId,
				idempotencyKey,
				fingerprint,
				now));
		version.beginValidation(snapshot.getDatasetBuildSnapshotId());
		this.versionRepository.saveAndFlush(version);
		this.auditRecorder.record(new AuditEventCommand(
				now,
				AuditActorType.USER,
				actorId,
				projectId,
				"DATASET_BUILD_SNAPSHOT_CREATED",
				"DatasetBuildSnapshot",
				snapshot.getDatasetBuildSnapshotId().toString(),
				DatasetBuildSnapshot.SNAPSHOT_SCHEMA_VERSION,
				AuditOutcome.SUCCEEDED,
				"IMMUTABLE_RELATIONAL_SNAPSHOT_SEALED",
				requestId,
				traceId,
				"PROJECT_MEMBERSHIP_ADMIN",
				Map.of(
						"dataset_version_id", datasetVersionId.toString(),
						"dataset_build_specification_id", specification.getDatasetBuildSpecificationId().toString(),
						"dataset_split_plan_id", splitPlan.getDatasetSplitPlanId().toString(),
						"input_variant_policy", DatasetBuildSnapshot.INPUT_VARIANT_POLICY,
						"member_count", Integer.toString(members.size()),
						"stream_count", Integer.toString(streams.size()),
						"snapshot_digest", digest,
						"request_fingerprint", fingerprint)));
		return DatasetBuildSnapshotResponse.from(snapshot);
	}

	private static void validateComplete(
			DatasetBuildSpecification specification,
			DatasetSplitPlan splitPlan,
			List<DatasetMemberSession> members,
			List<DatasetMemberStream> streams,
			List<DatasetSplitAssignment> assignments) {
		var issues = DatasetRelationalSnapshotChecks.structuralIssues(
				specification, splitPlan, members, streams, assignments);
		if (!issues.isEmpty()) {
			throw incomplete(issues.getFirst());
		}
	}

	private static DatasetVersionException incomplete(String reason) {
		return new DatasetVersionException(
				"DATASET_BUILD_SNAPSHOT_INCOMPLETE",
				"The fixed dataset evidence is incomplete or inconsistent.",
				409,
				Map.of("reason", reason));
	}

	private static DatasetVersionException notFound() {
		return new DatasetVersionException(
				"DATASET_VERSION_NOT_FOUND",
				"The dataset version was not found for the current project.",
				404);
	}

	static String requestFingerprint(UUID datasetVersionId) {
		var canonical = COMMAND_VERSION
				+ "\n" + datasetVersionId
				+ "\n" + DatasetBuildSnapshot.SNAPSHOT_SCHEMA_VERSION
				+ "\n" + DatasetBuildSnapshot.INPUT_VARIANT_POLICY;
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 must be available.", exception);
		}
	}
}
