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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;
import com.medicationvideo.platform.iamproject.ProjectAccessService;

@Service
class DatasetVersionBuildService {

	private static final String COMMAND_VERSION = "DATASET_VERSION_BUILD_START_V1";

	private final ProjectAccessService projectAccessService;
	private final DatasetVersionRepository versionRepository;
	private final DatasetVersionCurrentAdmissionQueryService currentAdmissionQueryService;
	private final AuditEventRecorder auditRecorder;

	DatasetVersionBuildService(
			ProjectAccessService projectAccessService,
			DatasetVersionRepository versionRepository,
			DatasetVersionCurrentAdmissionQueryService currentAdmissionQueryService,
			AuditEventRecorder auditRecorder) {
		this.projectAccessService = projectAccessService;
		this.versionRepository = versionRepository;
		this.currentAdmissionQueryService = currentAdmissionQueryService;
		this.auditRecorder = auditRecorder;
	}

	@Transactional(isolation = Isolation.REPEATABLE_READ)
	DatasetVersionBuildStartResponse start(
			UUID projectId,
			UUID datasetVersionId,
			String actorId,
			String idempotencyKey,
			String requestId,
			String traceId) {
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		var fingerprint = requestFingerprint(datasetVersionId);
		var replay = this.versionRepository.findByProjectIdAndBuildStartedByAndBuildStartIdempotencyKey(
				projectId, actorId, idempotencyKey);
		if (replay.isPresent()) {
			if (!replay.get().matchesBuildStartRequest(actorId, idempotencyKey, fingerprint)) {
				throw new DatasetVersionException(
						"DATASET_VERSION_BUILD_IDEMPOTENCY_KEY_REUSED",
						"The idempotency key was already used to start a different dataset build.", 409);
			}
			return DatasetVersionBuildStartResponse.from(replay.get());
		}

		var version = this.versionRepository.findByProjectIdAndDatasetVersionId(projectId, datasetVersionId)
				.orElseThrow(DatasetVersionBuildService::notFound);
		if (version.getStatus() != DatasetVersionStatus.DRAFT) {
			throw new DatasetVersionException(
					"DATASET_VERSION_BUILD_STATUS_INVALID",
					"Only a dataset draft can start building.", 409);
		}

		var currentAdmission = this.currentAdmissionQueryService.get(projectId, datasetVersionId, actorId);
		var readiness = currentAdmission.buildReadiness();
		if (!readiness.readyForBuild()) {
			throw new DatasetVersionException(
					"DATASET_VERSION_BUILD_BLOCKED",
					"The dataset draft no longer satisfies every build prerequisite.", 409,
					Map.of("blocking_reasons", String.join(",", readiness.blockingReasons().stream()
							.map(Enum::name)
							.toList())));
		}

		var specification = readiness.buildSpecification();
		if (specification == null || readiness.datasetSplitPlanId() == null) {
			throw new IllegalStateException("Build readiness must include the fixed specification and split plan.");
		}

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		version.startBuild(actorId, idempotencyKey, fingerprint, now);
		this.versionRepository.saveAndFlush(version);
		this.auditRecorder.record(new AuditEventCommand(
				now,
				AuditActorType.USER,
				actorId,
				projectId,
				"DATASET_VERSION_BUILD_STARTED",
				"DatasetVersion",
				datasetVersionId.toString(),
				Long.toString(version.getVersion()),
				AuditOutcome.SUCCEEDED,
				"BUILD_PREREQUISITES_REVALIDATED",
				requestId,
				traceId,
				"PROJECT_MEMBERSHIP_ADMIN",
				Map.of(
						"dataset_build_specification_id", specification.datasetBuildSpecificationId().toString(),
						"dataset_split_plan_id", readiness.datasetSplitPlanId().toString(),
						"member_count", Integer.toString(currentAdmission.members().size()),
						"request_fingerprint", fingerprint)));
		return DatasetVersionBuildStartResponse.from(version);
	}

	private static DatasetVersionException notFound() {
		return new DatasetVersionException(
				"DATASET_VERSION_NOT_FOUND",
				"The dataset version was not found for the current project.",
				404);
	}

	static String requestFingerprint(UUID datasetVersionId) {
		var canonical = COMMAND_VERSION + "\n" + datasetVersionId;
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 must be available.", exception);
		}
	}
}
