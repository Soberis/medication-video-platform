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
class DatasetBuildSpecificationService {

	private final ProjectAccessService projectAccessService;
	private final DatasetVersionRepository versionRepository;
	private final DatasetMemberStreamRepository memberStreamRepository;
	private final DatasetSplitPlanRepository splitPlanRepository;
	private final DatasetBuildSpecificationRepository specificationRepository;
	private final AuditEventRecorder auditRecorder;

	DatasetBuildSpecificationService(
			ProjectAccessService projectAccessService,
			DatasetVersionRepository versionRepository,
			DatasetMemberStreamRepository memberStreamRepository,
			DatasetSplitPlanRepository splitPlanRepository,
			DatasetBuildSpecificationRepository specificationRepository,
			AuditEventRecorder auditRecorder) {
		this.projectAccessService = projectAccessService;
		this.versionRepository = versionRepository;
		this.memberStreamRepository = memberStreamRepository;
		this.splitPlanRepository = splitPlanRepository;
		this.specificationRepository = specificationRepository;
		this.auditRecorder = auditRecorder;
	}

	@Transactional
	DatasetBuildSpecificationResponse create(
			UUID projectId,
			UUID datasetVersionId,
			String actorId,
			String idempotencyKey,
			String requestId,
			String traceId) {
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		var fingerprint = requestFingerprint(datasetVersionId);
		var replay = this.specificationRepository.findByProjectIdAndCreatedByAndIdempotencyKey(
				projectId, actorId, idempotencyKey);
		if (replay.isPresent()) {
			if (!replay.get().matchesCreateRequest(fingerprint)) {
				throw new DatasetVersionException(
						"DATASET_BUILD_SPECIFICATION_IDEMPOTENCY_KEY_REUSED",
						"The idempotency key was already used for a different build specification.", 409);
			}
			return DatasetBuildSpecificationResponse.from(replay.get());
		}

		var version = this.versionRepository.findByProjectIdAndDatasetVersionId(projectId, datasetVersionId)
				.orElseThrow(DatasetBuildSpecificationService::notFound);
		if (version.getStatus() != DatasetVersionStatus.DRAFT) {
			throw new DatasetVersionException(
					"DATASET_BUILD_SPECIFICATION_STATUS_INVALID",
					"A build specification can only be fixed for a dataset draft.", 409);
		}
		if (this.specificationRepository.findByDatasetVersionId(datasetVersionId).isPresent()) {
			throw new DatasetVersionException(
					"DATASET_BUILD_SPECIFICATION_ALREADY_EXISTS",
					"The dataset draft already has an immutable build specification.", 409);
		}
		var splitPlan = this.splitPlanRepository.findByDatasetVersionId(datasetVersionId)
				.orElseThrow(() -> new DatasetVersionException(
						"DATASET_BUILD_SPLIT_PLAN_REQUIRED",
						"An immutable split plan is required before fixing the build specification.", 409));
		var streams = this.memberStreamRepository
				.findByDatasetVersionIdOrderByDatasetMemberSessionIdAscCameraIndexAsc(datasetVersionId);
		if (streams.isEmpty()) {
			throw new DatasetVersionException(
					"DATASET_BUILD_EVIDENCE_MISSING",
					"The dataset draft does not contain stream evidence.", 409);
		}
		var buildEvidence = DatasetBuildEvidence.from(streams);
		var labelSchemaDigest = buildEvidence.uniformLabelSchemaDigest();
		if (labelSchemaDigest == null) {
			throw new DatasetVersionException(
					"DATASET_BUILD_LABEL_SCHEMA_NOT_UNIFORM",
					"Every dataset stream must use the same label schema before fixing the build specification.", 409);
		}
		var cleaningRuleSetDigest = buildEvidence.uniformCleaningRuleSetDigest();
		if (cleaningRuleSetDigest == null) {
			throw new DatasetVersionException(
					"DATASET_BUILD_CLEANING_RULE_SET_NOT_UNIFORM",
					"Every dataset stream must use the same cleaning rule set before fixing the build specification.", 409);
		}

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var specification = this.specificationRepository.saveAndFlush(DatasetBuildSpecification.create(
				version,
				labelSchemaDigest,
				cleaningRuleSetDigest,
				splitPlan,
				actorId,
				idempotencyKey,
				fingerprint,
				now));
		this.auditRecorder.record(new AuditEventCommand(
				now,
				AuditActorType.USER,
				actorId,
				projectId,
				"DATASET_BUILD_SPECIFICATION_CREATED",
				"DatasetBuildSpecification",
				specification.getDatasetBuildSpecificationId().toString(),
				DatasetSelectionRuleVersion.EXPLICIT_CAPTURE_SESSION_SNAPSHOT_V1.name(),
				AuditOutcome.SUCCEEDED,
				"IMMUTABLE_DATASET_BUILD_CONFIGURATION_FIXED",
				requestId,
				traceId,
				"PROJECT_MEMBERSHIP_ADMIN",
				Map.of(
						"dataset_version_id", datasetVersionId.toString(),
						"dataset_split_plan_id", splitPlan.getDatasetSplitPlanId().toString(),
						"label_schema_digest", labelSchemaDigest,
						"cleaning_rule_set_digest", cleaningRuleSetDigest,
						"export_profile_key", DatasetExportProfile.PROFILE_KEY,
						"export_profile_digest", DatasetExportProfile.PROFILE_DIGEST,
						"request_fingerprint", fingerprint)));
		return DatasetBuildSpecificationResponse.from(specification);
	}

	private static DatasetVersionException notFound() {
		return new DatasetVersionException(
				"DATASET_VERSION_NOT_FOUND",
				"The dataset version was not found for the current project.",
				404);
	}

	static String requestFingerprint(UUID datasetVersionId) {
		var canonical = DatasetSelectionRuleVersion.EXPLICIT_CAPTURE_SESSION_SNAPSHOT_V1.name()
				+ "\n" + datasetVersionId
				+ "\n" + DatasetExportProfile.PROFILE_KEY
				+ "\n" + DatasetExportProfile.PROFILE_DIGEST;
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 must be available.", exception);
		}
	}
}
