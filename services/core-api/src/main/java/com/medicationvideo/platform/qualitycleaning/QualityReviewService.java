package com.medicationvideo.platform.qualitycleaning;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.assetcapture.ClipAssetReviewQueryService;
import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;
import com.medicationvideo.platform.iamproject.ProjectAccessService;

@Service
class QualityReviewService {

	private final ProjectAccessService projectAccessService;

	private final ClipAssetReviewQueryService assetQueryService;

	private final QualityReviewRepository repository;

	private final AuditEventRecorder auditRecorder;

	QualityReviewService(ProjectAccessService projectAccessService, ClipAssetReviewQueryService assetQueryService,
			QualityReviewRepository repository, AuditEventRecorder auditRecorder) {
		this.projectAccessService = projectAccessService;
		this.assetQueryService = assetQueryService;
		this.repository = repository;
		this.auditRecorder = auditRecorder;
	}

	@Transactional(readOnly = true)
	QualityReviewResult get(UUID projectId, UUID clipAssetId, String actorId) {
		this.projectAccessService.requireMember(projectId, actorId);
		this.assetQueryService.getReviewFacts(projectId, clipAssetId);
		return this.repository.findByProjectIdAndClipAssetId(projectId, clipAssetId)
			.map(QualityReviewResult::from)
			.orElseGet(() -> QualityReviewResult.unreviewed(projectId, clipAssetId));
	}

	@Transactional
	QualityReviewResult record(UUID projectId, UUID clipAssetId, String actorId, QualityReviewCommand command,
			String requestId, String traceId) {
		var projectRole = this.projectAccessService.lockAndRequireMember(projectId, actorId);
		var assetFacts = this.assetQueryService.lockAndGetReviewFacts(projectId, clipAssetId);
		if (!assetFacts.reviewable()) {
			throw new QualityReviewException("QUALITY_REVIEW_NOT_READY",
					"The clip asset must complete media ingest before quality review.", 409);
		}

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var existing = this.repository.findForUpdate(projectId, clipAssetId);
		QualityReview review;
		if (existing.isEmpty()) {
			if (command.expectedVersion() != 0) {
				throw versionConflict();
			}
			review = QualityReview.create(projectId, clipAssetId, command.decision(), command.reasonCode(), actorId,
					now);
		}
		else {
			review = existing.get();
			if (review.getVersion() != command.expectedVersion()) {
				throw versionConflict();
			}
			if (review.hasSameConclusion(command.decision(), command.reasonCode())) {
				return QualityReviewResult.from(review);
			}
			review.revise(command.decision(), command.reasonCode(), actorId, now);
		}

		var saved = this.repository.saveAndFlush(review);
		this.auditRecorder.record(new AuditEventCommand(now, AuditActorType.USER, actorId, projectId,
				"CLIP_ASSET_QUALITY_REVIEWED", "QualityReview", saved.getQualityReviewId().toString(),
				Long.toString(saved.getVersion()), AuditOutcome.SUCCEEDED, saved.getReasonCode().name(), requestId,
				traceId, "PROJECT_MEMBERSHIP_" + projectRole.name(), Map.of("clip_asset_id", clipAssetId.toString(),
						"decision", saved.getDecision().name(), "reason_code", saved.getReasonCode().name())));
		return QualityReviewResult.from(saved);
	}

	private static QualityReviewException versionConflict() {
		return new QualityReviewException("QUALITY_REVIEW_CONFLICT",
				"The quality review changed; reload it before trying again.", 409);
	}

	record QualityReviewCommand(QualityReviewDecision decision, QualityReviewReasonCode reasonCode,
			long expectedVersion) {
	}

	record QualityReviewResult(UUID qualityReviewId, UUID projectId, UUID clipAssetId, String decision,
			QualityReviewReasonCode reasonCode, String reviewedBy, Instant reviewedAt, long version) {

		static QualityReviewResult unreviewed(UUID projectId, UUID clipAssetId) {
			return new QualityReviewResult(null, projectId, clipAssetId, "UNREVIEWED", null, null, null, 0);
		}

		static QualityReviewResult from(QualityReview review) {
			return new QualityReviewResult(review.getQualityReviewId(), review.getProjectId(), review.getClipAssetId(),
					review.getDecision().name(), review.getReasonCode(), review.getReviewedBy(), review.getReviewedAt(),
					review.getVersion());
		}
	}

}
