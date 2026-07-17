package com.medicationvideo.platform.mediaprivacy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.assetcapture.AssetCatalogQueryService;
import com.medicationvideo.platform.assetcapture.ClipAssetStatus;
import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;
import com.medicationvideo.platform.qualitycleaning.QuarantineActivationCommand;
import com.medicationvideo.platform.qualitycleaning.QuarantineCommandService;

@Service
class PrivacyAssessmentService {

	private final PrivacyAssessmentRepository repository;
	private final AssetCatalogQueryService assetCatalog;
	private final AuditEventRecorder auditRecorder;
	private final QuarantineCommandService quarantineService;

	PrivacyAssessmentService(
			PrivacyAssessmentRepository repository,
			AssetCatalogQueryService assetCatalog,
			AuditEventRecorder auditRecorder,
			QuarantineCommandService quarantineService) {
		this.repository = repository;
		this.assetCatalog = assetCatalog;
		this.auditRecorder = auditRecorder;
		this.quarantineService = quarantineService;
	}

	@Transactional
	PrivacyAssessment assess(
			UUID projectId,
			String actorId,
			String idempotencyKey,
			String requestId,
			String traceId,
			PrivacyAssessmentRequest request) {
		validateDecision(request);
		var requestFingerprint = fingerprint(projectId, request);
		var byIdempotency = this.repository.findByProjectIdAndIdempotencyKey(
				projectId, idempotencyKey);
		if (byIdempotency.isPresent()) {
			return requireSameRequest(byIdempotency.get(), requestFingerprint);
		}

		var asset = this.assetCatalog.getClipAssetInputSummary(projectId, request.clipAssetId());
		if (asset.status() != ClipAssetStatus.INGESTED) {
			throw new PrivacyAssessmentException(
					"CLIP_ASSET_NOT_ASSESSABLE",
					"Only a successfully ingested clip asset can receive a privacy assessment.",
					409);
		}

		var current = this.repository
				.findTopByProjectIdAndClipAssetIdAndInputDigestOrderByAssessmentVersionDesc(
						projectId, asset.clipAssetId(), asset.inputDigest());
		var nextVersion = 1;
		if (current.isPresent()) {
			if (request.supersedesAssessmentId() == null) {
				throw new PrivacyAssessmentException(
						"PRIVACY_ASSESSMENT_SUPERSESSION_REQUIRED",
						"A current assessment already exists; explicitly supersede it to reassess.",
						409);
			}
			if (!current.get().getPrivacyAssessmentId().equals(request.supersedesAssessmentId())) {
				throw staleAssessment();
			}
			nextVersion = current.get().getAssessmentVersion() + 1;
		}
		else if (request.supersedesAssessmentId() != null) {
			throw staleAssessment();
		}

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var assessment = PrivacyAssessment.assess(
				projectId,
				asset.clipAssetId(),
				asset.inputDigest(),
				nextVersion,
				request,
				actorId,
				idempotencyKey,
				requestFingerprint,
				now);
		try {
			assessment = this.repository.saveAndFlush(assessment);
		}
		catch (DataIntegrityViolationException exception) {
			var concurrentByIdempotency = this.repository.findByProjectIdAndIdempotencyKey(
					projectId, idempotencyKey);
			if (concurrentByIdempotency.isPresent()) {
				return requireSameRequest(concurrentByIdempotency.get(), requestFingerprint);
			}
			throw staleAssessment();
		}

		recordAudit(assessment, requestId, traceId);
		activateRequiredQuarantine(assessment, requestId, traceId);
		return assessment;
	}

	private void activateRequiredQuarantine(
			PrivacyAssessment assessment, String requestId, String traceId) {
		if (assessment.getRequiredAction() != PrivacyRequiredAction.QUARANTINE_REQUIRED) {
			return;
		}
		this.quarantineService.activate(QuarantineActivationCommand.forPrivacyAssessment(
				assessment.getProjectId(),
				assessment.getClipAssetId(),
				assessment.getInputDigest(),
				assessment.getPrivacyAssessmentId(),
				assessment.getAssessmentVersion(),
				assessment.getAssessedBy(),
				requestId,
				traceId));
	}

	@Transactional(readOnly = true)
	PrivacyAssessment get(UUID projectId, UUID privacyAssessmentId) {
		return this.repository
				.findByPrivacyAssessmentIdAndProjectId(privacyAssessmentId, projectId)
				.orElseThrow(() -> new PrivacyAssessmentException(
						"PRIVACY_ASSESSMENT_NOT_FOUND",
						"The privacy assessment was not found in the current project.",
						404));
	}

	@Transactional(readOnly = true)
	PrivacyStatusResponse status(UUID projectId, UUID clipAssetId) {
		var asset = this.assetCatalog.getClipAssetInputSummary(projectId, clipAssetId);
		return this.repository
				.findTopByProjectIdAndClipAssetIdAndInputDigestOrderByAssessmentVersionDesc(
						projectId, asset.clipAssetId(), asset.inputDigest())
				.map(PrivacyStatusResponse::from)
				.orElseGet(() -> PrivacyStatusResponse.unknown(
						asset.clipAssetId(), asset.inputDigest()));
	}

	private void recordAudit(PrivacyAssessment assessment, String requestId, String traceId) {
		var details = new java.util.HashMap<String, String>();
		details.put("clip_asset_id", assessment.getClipAssetId().toString());
		details.put("assessment_version", Integer.toString(assessment.getAssessmentVersion()));
		details.put("risk_level", assessment.getRiskLevel().name());
		details.put("required_action", assessment.getRequiredAction().name());
		details.put("policy_key", assessment.getPolicyKey());
		if (assessment.getSupersedesAssessmentId() != null) {
			details.put(
					"supersedes_assessment_id",
					assessment.getSupersedesAssessmentId().toString());
		}
		this.auditRecorder.record(new AuditEventCommand(
				assessment.getAssessedAt(),
				AuditActorType.USER,
				assessment.getAssessedBy(),
				assessment.getProjectId(),
				"PRIVACY_ASSESSMENT_RECORDED",
				"PrivacyAssessment",
				assessment.getPrivacyAssessmentId().toString(),
				"assessment-" + assessment.getAssessmentVersion(),
				AuditOutcome.SUCCEEDED,
				assessment.getReasonCode().name(),
				requestId,
				traceId,
				"ROLE_ADMIN",
				Map.copyOf(details)));
	}

	private static void validateDecision(PrivacyAssessmentRequest request) {
		if (request.riskLevel() == null
				|| request.requiredAction() == null
				|| request.reasonCode() == null
				|| request.findingTypes() == null) {
			throw invalidDecision();
		}
		var findingsEmpty = request.findingTypes().isEmpty();
		var valid = switch (request.riskLevel()) {
			case NONE -> findingsEmpty
					&& request.requiredAction() == PrivacyRequiredAction.RELEASE_REVIEW_REQUIRED
					&& request.reasonCode()
							== PrivacyAssessmentReasonCode.NO_IDENTIFIABLE_INFORMATION_OBSERVED;
			case SUSPECTED -> !findingsEmpty
					&& request.requiredAction() == PrivacyRequiredAction.QUARANTINE_REQUIRED
					&& request.reasonCode()
							== PrivacyAssessmentReasonCode.RISK_REQUIRES_ISOLATION;
			case CONFIRMED -> !findingsEmpty
					&& ((request.requiredAction() == PrivacyRequiredAction.DEIDENTIFICATION_REQUIRED
							&& request.reasonCode()
									== PrivacyAssessmentReasonCode.IDENTIFIABLE_INFORMATION_OBSERVED)
						|| (request.requiredAction() == PrivacyRequiredAction.QUARANTINE_REQUIRED
								&& request.reasonCode()
										== PrivacyAssessmentReasonCode.RISK_REQUIRES_ISOLATION));
		};
		if (!valid) {
			throw invalidDecision();
		}
	}

	private static PrivacyAssessment requireSameRequest(
			PrivacyAssessment assessment, String requestFingerprint) {
		if (!MessageDigest.isEqual(
				assessment.getRequestFingerprint().getBytes(StandardCharsets.US_ASCII),
				requestFingerprint.getBytes(StandardCharsets.US_ASCII))) {
			throw new PrivacyAssessmentException(
					"IDEMPOTENCY_KEY_REUSED",
					"The idempotency key was already used for a different privacy assessment.",
					409);
		}
		return assessment;
	}

	private static String fingerprint(UUID projectId, PrivacyAssessmentRequest request) {
		var findings = request.findingTypes() == null
				? "null"
				: request.findingTypes().stream()
						.map(Enum::name)
						.sorted()
						.collect(Collectors.joining(","));
		var canonical = String.join(
				"\n",
				projectId.toString(),
				String.valueOf(request.clipAssetId()),
				String.valueOf(request.supersedesAssessmentId()),
				String.valueOf(request.riskLevel()),
				String.valueOf(request.requiredAction()),
				String.valueOf(request.reasonCode()),
				findings,
				PrivacyAssessmentPolicy.POLICY_KEY,
				PrivacyAssessmentPolicy.POLICY_DIGEST);
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

	private static PrivacyAssessmentException invalidDecision() {
		return new PrivacyAssessmentException(
				"INVALID_PRIVACY_ASSESSMENT_DECISION",
				"The privacy risk, required action, reason and findings are inconsistent.",
				400);
	}

	private static PrivacyAssessmentException staleAssessment() {
		return new PrivacyAssessmentException(
				"PRIVACY_ASSESSMENT_STALE",
				"The assessment does not supersede the current assessment version.",
				409);
	}
}
