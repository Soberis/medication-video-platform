package com.medicationvideo.platform.datasetexport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashSet;
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
class DatasetVersionValidationService {

	static final String CURRENT_ADMISSION_NOT_ELIGIBLE = "CURRENT_ADMISSION_NOT_ELIGIBLE";
	static final String SNAPSHOT_EVIDENCE_CHANGED = "SNAPSHOT_EVIDENCE_CHANGED";
	static final String SNAPSHOT_REFERENCE_MISMATCH = "SNAPSHOT_REFERENCE_MISMATCH";
	static final String SNAPSHOT_STRUCTURE_INVALID = "SNAPSHOT_STRUCTURE_INVALID";
	static final String SNAPSHOT_DIGEST_MISMATCH = "SNAPSHOT_DIGEST_MISMATCH";
	static final String SPLIT_LEAKAGE_DETECTED = "SPLIT_LEAKAGE_DETECTED";
	static final String BUILD_CONTRACT_MISMATCH = "BUILD_CONTRACT_MISMATCH";

	private static final String COMMAND_VERSION = "DATASET_VERSION_VALIDATION_CREATE_V1";

	private final ProjectAccessService projectAccessService;
	private final DatasetVersionRepository versionRepository;
	private final DatasetMemberSessionRepository memberRepository;
	private final DatasetMemberStreamRepository streamRepository;
	private final DatasetSplitPlanRepository splitPlanRepository;
	private final DatasetSplitAssignmentRepository assignmentRepository;
	private final DatasetBuildSpecificationRepository specificationRepository;
	private final DatasetBuildSnapshotRepository snapshotRepository;
	private final DatasetVersionValidationRepository validationRepository;
	private final DatasetVersionCurrentAdmissionQueryService currentAdmissionQueryService;
	private final AuditEventRecorder auditRecorder;

	DatasetVersionValidationService(
			ProjectAccessService projectAccessService,
			DatasetVersionRepository versionRepository,
			DatasetMemberSessionRepository memberRepository,
			DatasetMemberStreamRepository streamRepository,
			DatasetSplitPlanRepository splitPlanRepository,
			DatasetSplitAssignmentRepository assignmentRepository,
			DatasetBuildSpecificationRepository specificationRepository,
			DatasetBuildSnapshotRepository snapshotRepository,
			DatasetVersionValidationRepository validationRepository,
			DatasetVersionCurrentAdmissionQueryService currentAdmissionQueryService,
			AuditEventRecorder auditRecorder) {
		this.projectAccessService = projectAccessService;
		this.versionRepository = versionRepository;
		this.memberRepository = memberRepository;
		this.streamRepository = streamRepository;
		this.splitPlanRepository = splitPlanRepository;
		this.assignmentRepository = assignmentRepository;
		this.specificationRepository = specificationRepository;
		this.snapshotRepository = snapshotRepository;
		this.validationRepository = validationRepository;
		this.currentAdmissionQueryService = currentAdmissionQueryService;
		this.auditRecorder = auditRecorder;
	}

	@Transactional(isolation = Isolation.REPEATABLE_READ)
	DatasetVersionValidationResponse validate(
			UUID projectId,
			UUID datasetVersionId,
			String actorId,
			String idempotencyKey,
			String requestId,
			String traceId) {
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		var fingerprint = requestFingerprint(datasetVersionId);
		var replay = this.validationRepository.findByProjectIdAndValidatedByAndIdempotencyKey(
				projectId, actorId, idempotencyKey);
		if (replay.isPresent()) {
			if (!replay.get().matchesRequest(fingerprint)) {
				throw new DatasetVersionException(
						"DATASET_VERSION_VALIDATION_IDEMPOTENCY_KEY_REUSED",
						"The idempotency key was already used for another dataset validation.",
						409);
			}
			return DatasetVersionValidationResponse.from(replay.get());
		}

		var version = this.versionRepository.findByProjectIdAndDatasetVersionId(projectId, datasetVersionId)
				.orElseThrow(DatasetVersionValidationService::notFound);
		if (version.getStatus() != DatasetVersionStatus.VALIDATING) {
			throw new DatasetVersionException(
					"DATASET_VERSION_VALIDATION_STATUS_INVALID",
					"Freeze validation can only run while the dataset version is validating.",
					409);
		}
		if (this.validationRepository.findByDatasetVersionId(datasetVersionId).isPresent()) {
			throw new DatasetVersionException(
					"DATASET_VERSION_VALIDATION_ALREADY_EXISTS",
					"The dataset version already has an immutable validation result.",
					409);
		}

		var snapshot = this.snapshotRepository.findByDatasetVersionId(datasetVersionId)
				.orElseThrow(() -> incomplete("BUILD_SNAPSHOT_MISSING"));
		var specification = this.specificationRepository.findByDatasetVersionId(datasetVersionId)
				.orElseThrow(() -> incomplete("BUILD_SPECIFICATION_MISSING"));
		var splitPlan = this.splitPlanRepository.findByDatasetVersionId(datasetVersionId)
				.orElseThrow(() -> incomplete("SPLIT_PLAN_MISSING"));
		var members = this.memberRepository.findByDatasetVersionIdOrderByCandidateIndexAsc(datasetVersionId);
		var streams = this.streamRepository
				.findByDatasetVersionIdOrderByDatasetMemberSessionIdAscCameraIndexAsc(datasetVersionId);
		var assignments = this.assignmentRepository
				.findByDatasetSplitPlanIdOrderByCandidateIndexAsc(splitPlan.getDatasetSplitPlanId());
		var currentAdmission = this.currentAdmissionQueryService.get(projectId, datasetVersionId, actorId);
		var reasons = evaluate(
				version,
				snapshot,
				specification,
				splitPlan,
				members,
				streams,
				assignments,
				currentAdmission);

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var validation = this.validationRepository.saveAndFlush(DatasetVersionValidation.record(
				version,
				snapshot,
				reasons,
				snapshot.getSnapshotDigest(),
				actorId,
				idempotencyKey,
				fingerprint,
				now));
		this.auditRecorder.record(new AuditEventCommand(
				now,
				AuditActorType.USER,
				actorId,
				projectId,
				"DATASET_VERSION_VALIDATION_COMPLETED",
				"DatasetVersionValidation",
				validation.getDatasetVersionValidationId().toString(),
				DatasetVersionValidation.PROFILE_VERSION,
				AuditOutcome.SUCCEEDED,
				"DATASET_VALIDATION_" + validation.getOutcome().name(),
				requestId,
				traceId,
				"PROJECT_MEMBERSHIP_ADMIN",
				Map.of(
						"dataset_version_id", datasetVersionId.toString(),
						"dataset_build_snapshot_id", snapshot.getDatasetBuildSnapshotId().toString(),
						"snapshot_digest", snapshot.getSnapshotDigest(),
						"outcome", validation.getOutcome().name(),
						"reason_codes", String.join(",", validation.getReasonCodes()),
						"request_fingerprint", fingerprint)));
		return DatasetVersionValidationResponse.from(validation);
	}

	static List<String> evaluate(
			DatasetVersion version,
			DatasetBuildSnapshot snapshot,
			DatasetBuildSpecification specification,
			DatasetSplitPlan splitPlan,
			List<DatasetMemberSession> members,
			List<DatasetMemberStream> streams,
			List<DatasetSplitAssignment> assignments,
			DatasetVersionCurrentAdmissionResponse currentAdmission) {
		var reasons = new LinkedHashSet<String>();
		if (!currentAdmission.allMembersCurrentlyAdmissionEligible()) {
			reasons.add(CURRENT_ADMISSION_NOT_ELIGIBLE);
		}
		if (!currentAdmission.buildReadiness().immutableEvidenceStillCurrent()) {
			reasons.add(SNAPSHOT_EVIDENCE_CHANGED);
		}
		if (!referencesMatch(version, snapshot, specification, splitPlan, members, streams)) {
			reasons.add(SNAPSHOT_REFERENCE_MISMATCH);
		}

		var structuralIssues = DatasetRelationalSnapshotChecks.structuralIssues(
				specification, splitPlan, members, streams, assignments);
		if (structuralIssues.contains(DatasetRelationalSnapshotChecks.SPLIT_LEAKAGE_DETECTED)) {
			reasons.add(SPLIT_LEAKAGE_DETECTED);
		}
		if (structuralIssues.stream()
				.anyMatch(issue -> !DatasetRelationalSnapshotChecks.SPLIT_LEAKAGE_DETECTED.equals(issue))) {
			reasons.add(SNAPSHOT_STRUCTURE_INVALID);
		}
		if (structuralIssues.isEmpty()) {
			var recomputedDigest = DatasetBuildSnapshotDigest.compute(
					version, specification, splitPlan, members, streams, assignments);
			if (!snapshot.getSnapshotDigest().equals(recomputedDigest)) {
				reasons.add(SNAPSHOT_DIGEST_MISMATCH);
			}
		}
		if (!buildContractMatches(snapshot, specification, splitPlan)) {
			reasons.add(BUILD_CONTRACT_MISMATCH);
		}
		return List.copyOf(reasons);
	}

	private static boolean referencesMatch(
			DatasetVersion version,
			DatasetBuildSnapshot snapshot,
			DatasetBuildSpecification specification,
			DatasetSplitPlan splitPlan,
			List<DatasetMemberSession> members,
			List<DatasetMemberStream> streams) {
		return snapshot.getDatasetBuildSnapshotId().equals(version.getBuildSnapshotId())
				&& snapshot.getDatasetVersionId().equals(version.getDatasetVersionId())
				&& snapshot.getProjectId().equals(version.getProjectId())
				&& specification.getDatasetVersionId().equals(version.getDatasetVersionId())
				&& specification.getProjectId().equals(version.getProjectId())
				&& splitPlan.getDatasetVersionId().equals(version.getDatasetVersionId())
				&& splitPlan.getProjectId().equals(version.getProjectId())
				&& snapshot.getDatasetBuildSpecificationId()
						.equals(specification.getDatasetBuildSpecificationId())
				&& snapshot.getDatasetSplitPlanId().equals(splitPlan.getDatasetSplitPlanId())
				&& snapshot.getMemberCount() == members.size()
				&& snapshot.getStreamCount() == streams.size();
	}

	private static boolean buildContractMatches(
			DatasetBuildSnapshot snapshot,
			DatasetBuildSpecification specification,
			DatasetSplitPlan splitPlan) {
		return DatasetBuildSnapshot.SNAPSHOT_SCHEMA_VERSION.equals(snapshot.getSnapshotSchemaVersion())
				&& DatasetBuildSnapshot.INPUT_VARIANT_POLICY.equals(snapshot.getInputVariantPolicy())
				&& specification.getSelectionRuleVersion()
						== DatasetSelectionRuleVersion.EXPLICIT_CAPTURE_SESSION_SNAPSHOT_V1
				&& DatasetExportProfile.PROFILE_KEY.equals(specification.getExportProfileKey())
				&& DatasetExportProfile.PROFILE_DIGEST.equals(specification.getExportProfileDigest())
				&& splitPlan.getPolicyVersion() == DatasetSplitPolicy.CAPTURE_SESSION_HASH_RANK_V1;
	}

	private static DatasetVersionException incomplete(String reason) {
		return new DatasetVersionException(
				"DATASET_VERSION_VALIDATION_INCOMPLETE",
				"The fixed dataset snapshot is incomplete and cannot be validated.",
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
				+ "\n" + DatasetVersionValidation.PROFILE_VERSION;
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 must be available.", exception);
		}
	}
}
