package com.medicationvideo.platform.qualitycleaning;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.assetcapture.CaptureGroupReceiptState;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService.CaptureGroupFacts;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService.CaptureGroupView;
import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;
import com.medicationvideo.platform.iamproject.ProjectAccessService;

/**
 * CaptureGroup 组级质量记录的历史面。
 *
 * <p>
 * 自输入冻结接管标注准入后，本服务不再参与任何准入判断：标注、复核与导出只读 {@link CaptureGroupInputFreezeQueryService}
 * 的冻结快照。此处保留组质量记录的读写与历史查询， 用于展示与审计回溯；记录页的既有校验语义保持不变。
 */
@Service
class CaptureGroupQualityReviewService {

	private static final Pattern SAFE_REASON = Pattern.compile("^[^\\p{Cc}]+$");

	private final ProjectAccessService projectAccessService;

	private final CaptureGroupViewQueryService captureGroupViewQueryService;

	private final QualityReviewQueryService qualityReviewQueryService;

	private final CaptureGroupQualityReviewRepository repository;

	private final AuditEventRecorder auditRecorder;

	CaptureGroupQualityReviewService(ProjectAccessService projectAccessService,
			CaptureGroupViewQueryService captureGroupViewQueryService,
			QualityReviewQueryService qualityReviewQueryService, CaptureGroupQualityReviewRepository repository,
			AuditEventRecorder auditRecorder) {
		this.projectAccessService = projectAccessService;
		this.captureGroupViewQueryService = captureGroupViewQueryService;
		this.qualityReviewQueryService = qualityReviewQueryService;
		this.repository = repository;
		this.auditRecorder = auditRecorder;
	}

	@Transactional(readOnly = true)
	CaptureGroupQualityReviewResponse get(UUID projectId, UUID captureGroupId, String actorId) {
		this.projectAccessService.requireMember(projectId, actorId);
		this.captureGroupViewQueryService.get(projectId, captureGroupId);
		return this.repository.findTopByProjectIdAndCaptureGroupIdOrderByRevisionDesc(projectId, captureGroupId)
			.map(CaptureGroupQualityReviewResponse::from)
			.orElseGet(() -> CaptureGroupQualityReviewResponse.unreviewed(projectId, captureGroupId));
	}

	@Transactional(readOnly = true)
	CaptureGroupQualityReviewBatchResponse batch(UUID projectId, List<UUID> captureGroupIds, String actorId) {
		this.projectAccessService.requireMember(projectId, actorId);
		var distinctIds = captureGroupIds.stream().distinct().toList();
		var currentByGroup = new LinkedHashMap<UUID, CaptureGroupQualityReview>();
		if (!distinctIds.isEmpty()) {
			this.repository
				.findAllByProjectIdAndCaptureGroupIdInOrderByCaptureGroupIdAscRevisionDesc(projectId, distinctIds)
				.forEach(review -> currentByGroup.putIfAbsent(review.getCaptureGroupId(), review));
		}
		var items = distinctIds.stream()
			.map(captureGroupId -> currentByGroup.containsKey(captureGroupId)
					? CaptureGroupQualityReviewResponse.from(currentByGroup.get(captureGroupId))
					: CaptureGroupQualityReviewResponse.unreviewed(projectId, captureGroupId))
			.toList();
		return new CaptureGroupQualityReviewBatchResponse(items);
	}

	@Transactional(readOnly = true)
	CaptureGroupQualityReviewHistoryResponse history(UUID projectId, UUID captureGroupId, String actorId) {
		this.projectAccessService.requireMember(projectId, actorId);
		this.captureGroupViewQueryService.get(projectId, captureGroupId);
		var items = this.repository.findAllByProjectIdAndCaptureGroupIdOrderByRevisionAsc(projectId, captureGroupId)
			.stream()
			.map(CaptureGroupQualityReviewResponse::from)
			.toList();
		return new CaptureGroupQualityReviewHistoryResponse(items);
	}

	@Transactional
	CaptureGroupQualityReviewResponse record(UUID projectId, UUID captureGroupId, String actorId, String idempotencyKey,
			String requestId, String traceId, CaptureGroupQualityReviewCommand command) {
		var projectRole = this.projectAccessService.lockAndRequireMember(projectId, actorId);
		var facts = this.captureGroupViewQueryService.lockAndGet(projectId, captureGroupId);
		var normalizedReason = normalizeReason(command.reason());
		var replay = this.repository.findByProjectIdAndCaptureGroupIdAndReviewerActorIdAndIdempotencyKey(projectId,
				captureGroupId, actorId, idempotencyKey);
		if (replay.isPresent()) {
			if (!replay.get().matchesSubmission(command.result(), normalizedReason)) {
				throw new QualityReviewException("CAPTURE_GROUP_QUALITY_IDEMPOTENCY_KEY_REUSED",
						"The idempotency key was already used for a different group-quality decision.", 409);
			}
			return CaptureGroupQualityReviewResponse.from(replay.get());
		}

		if (facts.receiptState() == CaptureGroupReceiptState.RECEIVING
				&& command.result() != CaptureGroupQualityResult.NEEDS_REVIEW) {
			throw new QualityReviewException("CAPTURE_GROUP_QUALITY_RECEIPT_NOT_FINALIZED",
					"A receiving capture group may remain NEEDS_REVIEW but cannot accept a final group-quality decision.",
					409);
		}
		var current = this.repository.findForUpdate(projectId, captureGroupId).stream().findFirst().orElse(null);
		var currentRevision = current == null ? 0 : current.getRevision();
		if (command.expectedRevision() != currentRevision) {
			throw new QualityReviewException("CAPTURE_GROUP_QUALITY_CONFLICT",
					"The group-quality decision changed; reload it before trying again.", 409);
		}

		var decisions = this.qualityReviewQueryService.findCurrentDecisions(projectId,
				facts.views().stream().map(CaptureGroupView::clipAssetId).filter(java.util.Objects::nonNull).toList());
		var qualifiedCount = decisions.values()
			.stream()
			.filter(QualityReviewQueryService.CurrentDecision::isQualified)
			.count();
		if (command.result().isAdmitting() && qualifiedCount == 0) {
			throw new QualityReviewException("CAPTURE_GROUP_QUALITY_NO_QUALIFIED_VIEW",
					"At least one participating view must be human-qualified before the group can enter annotation.",
					409);
		}
		if (command.result().isAdmitting()) {
			validateAdmittingResult(facts, decisions, command.result());
		}

		var snapshot = CaptureGroupInputFreeze.of(facts);
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var review = this.repository.saveAndFlush(CaptureGroupQualityReview.record(projectId, captureGroupId,
				command.result(), normalizedReason, actorId, currentRevision + 1, snapshot.confirmedViewSet(),
				snapshot.inputVersion(), snapshot.viewSetSha256(), idempotencyKey, now));
		this.auditRecorder.record(new AuditEventCommand(now, AuditActorType.USER, actorId, projectId,
				"CAPTURE_GROUP_QUALITY_REVIEWED", "CaptureGroupQualityReview",
				review.getCaptureGroupQualityReviewId().toString(), Long.toString(review.getRevision()),
				AuditOutcome.SUCCEEDED, review.getResult().name(), requestId, traceId,
				"PROJECT_MEMBERSHIP_" + projectRole.name(),
				Map.of("capture_group_id", captureGroupId.toString(), "result", review.getResult().name(), "reason",
						review.getReason(), "revision", Long.toString(review.getRevision()), "input_version",
						snapshot.inputVersion(), "confirmed_view_set_sha256", snapshot.viewSetSha256())));
		return CaptureGroupQualityReviewResponse.from(review);
	}

	private static void validateAdmittingResult(CaptureGroupFacts facts,
			Map<UUID, QualityReviewQueryService.CurrentDecision> decisions, CaptureGroupQualityResult result) {
		var unresolved = facts.views()
			.stream()
			.filter(CaptureGroupView::present)
			.filter(view -> !isResolved(decisions.get(view.clipAssetId())))
			.toList();
		if (!unresolved.isEmpty()) {
			throw new QualityReviewException("CAPTURE_GROUP_QUALITY_VIEW_NOT_RESOLVED",
					"Every received view must have a human QUALIFIED or REJECTED quality decision before group admission.",
					409);
		}

		if (result == CaptureGroupQualityResult.QUALIFIED) {
			if (facts.receiptState() == CaptureGroupReceiptState.INCOMPLETE
					|| facts.views().stream().anyMatch(view -> !view.present())) {
				throw new QualityReviewException("CAPTURE_GROUP_QUALITY_INCOMPLETE_REQUIRES_EXCLUSIONS",
						"An incomplete capture group cannot be QUALIFIED; use QUALIFIED_WITH_EXCLUSIONS after confirming missing views.",
						409);
			}
			if (facts.views().stream().anyMatch(view -> !decisions.get(view.clipAssetId()).isQualified())) {
				throw new QualityReviewException("CAPTURE_GROUP_QUALITY_QUALIFIED_HAS_EXCLUSIONS",
						"QUALIFIED requires every received view to be human-qualified; use QUALIFIED_WITH_EXCLUSIONS for rejected views.",
						409);
			}
		}
	}

	private static boolean isResolved(QualityReviewQueryService.CurrentDecision decision) {
		return decision != null && (decision.isQualified() || "REJECTED".equals(decision.decision()));
	}

	private static String normalizeReason(String reason) {
		var normalized = reason == null ? "" : reason.trim();
		if (normalized.isBlank() || !SAFE_REASON.matcher(normalized).matches() || normalized.length() > 1000) {
			throw new QualityReviewException("INVALID_CAPTURE_GROUP_QUALITY_REASON",
					"A non-empty group-quality reason without control characters is required.", 400);
		}
		return normalized;
	}

	record CaptureGroupQualityReviewCommand(CaptureGroupQualityResult result, String reason, long expectedRevision) {
	}

}
