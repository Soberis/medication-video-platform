package com.medicationvideo.platform.qualitycleaning;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
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
import com.medicationvideo.platform.iamproject.ProjectRole;

@Service
class CaptureGroupQualityReviewService {

	private static final Pattern SAFE_REASON = Pattern.compile("^[^\\p{Cc}]+$");

	private final ProjectAccessService projectAccessService;

	private final CaptureGroupViewQueryService captureGroupViewQueryService;

	private final CaptureGroupQualityQueryService captureGroupQualityQueryService;

	private final QualityReviewQueryService qualityReviewQueryService;

	private final CaptureGroupQualityReviewRepository repository;

	private final AuditEventRecorder auditRecorder;

	CaptureGroupQualityReviewService(ProjectAccessService projectAccessService,
			CaptureGroupViewQueryService captureGroupViewQueryService,
			CaptureGroupQualityQueryService captureGroupQualityQueryService,
			QualityReviewQueryService qualityReviewQueryService, CaptureGroupQualityReviewRepository repository,
			AuditEventRecorder auditRecorder) {
		this.projectAccessService = projectAccessService;
		this.captureGroupViewQueryService = captureGroupViewQueryService;
		this.captureGroupQualityQueryService = captureGroupQualityQueryService;
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
		var items = this.captureGroupQualityQueryService.getCurrentDecisions(projectId, captureGroupIds)
			.stream()
			.map(CaptureGroupQualityReviewResponse::from)
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
		var viewSet = confirmedViewSet(facts, decisions);
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

		var viewSetSha256 = digestViewSet(facts, decisions);
		var inputVersion = "capture-group-view-set.v1:" + viewSetSha256;
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var review = this.repository.saveAndFlush(
				CaptureGroupQualityReview.record(projectId, captureGroupId, command.result(), normalizedReason, actorId,
						currentRevision + 1, viewSet, inputVersion, viewSetSha256, idempotencyKey, now));
		this.auditRecorder.record(
				new AuditEventCommand(now, AuditActorType.USER, actorId, projectId, "CAPTURE_GROUP_QUALITY_REVIEWED",
						"CaptureGroupQualityReview", review.getCaptureGroupQualityReviewId().toString(),
						Long.toString(review.getRevision()), AuditOutcome.SUCCEEDED, review.getResult().name(),
						requestId, traceId, "PROJECT_MEMBERSHIP_" + projectRole.name(),
						Map.of("capture_group_id", captureGroupId.toString(), "result", review.getResult().name(),
								"reason", review.getReason(), "revision", Long.toString(review.getRevision()),
								"input_version", inputVersion, "confirmed_view_set_sha256", viewSetSha256)));
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

	static Map<String, Object> confirmedViewSet(CaptureGroupFacts facts,
			Map<UUID, QualityReviewQueryService.CurrentDecision> decisions) {
		var root = new LinkedHashMap<String, Object>();
		root.put("schema_version", "capture-group-view-set.v1");
		root.put("capture_group_id", facts.captureGroupId().toString());
		root.put("capture_group_version", facts.captureGroupVersion());
		root.put("camera_set_snapshot_id", facts.cameraSetSnapshotId().toString());
		root.put("receipt_state", facts.receiptState().name());
		root.put("views", facts.views().stream().map(view -> viewSnapshot(view, decisions)).toList());
		return root;
	}

	private static Map<String, Object> viewSnapshot(CaptureGroupView view,
			Map<UUID, QualityReviewQueryService.CurrentDecision> decisions) {
		var quality = view.clipAssetId() == null ? null : decisions.get(view.clipAssetId());
		var snapshot = new LinkedHashMap<String, Object>();
		snapshot.put("camera_number", view.cameraNumber());
		snapshot.put("camera_stream_id", view.cameraStreamId() == null ? null : view.cameraStreamId().toString());
		snapshot.put("clip_asset_id", view.clipAssetId() == null ? null : view.clipAssetId().toString());
		snapshot.put("camera_channel_id", view.cameraChannelId() == null ? null : view.cameraChannelId().toString());
		snapshot.put("present", view.present());
		snapshot.put("media_status", view.mediaStatus());
		snapshot.put("technical_quality_status", view.technicalQualityStatus());
		snapshot.put("stream_version", view.streamVersion());
		snapshot.put("clip_asset_version", view.clipAssetVersion());
		snapshot.put("asset_available", view.assetAvailable());
		snapshot.put("quality_review_decision", quality == null ? "UNREVIEWED" : quality.decision());
		snapshot.put("quality_review_version", quality == null ? 0 : quality.version());
		return snapshot;
	}

	private static String digestViewSet(CaptureGroupFacts facts,
			Map<UUID, QualityReviewQueryService.CurrentDecision> decisions) {
		try {
			var canonical = new StringBuilder();
			append(canonical, facts.captureGroupId());
			append(canonical, facts.captureGroupVersion());
			append(canonical, facts.cameraSetSnapshotId());
			append(canonical, facts.receiptState());
			for (var view : facts.views()) {
				append(canonical, view.cameraNumber());
				append(canonical, view.cameraStreamId());
				append(canonical, view.clipAssetId());
				append(canonical, view.cameraChannelId());
				append(canonical, view.present());
				append(canonical, view.mediaStatus());
				append(canonical, view.technicalQualityStatus());
				append(canonical, view.streamVersion());
				append(canonical, view.clipAssetVersion());
				append(canonical, view.assetAvailable());
				var quality = view.clipAssetId() == null ? null : decisions.get(view.clipAssetId());
				append(canonical, quality == null ? "UNREVIEWED" : quality.decision());
				append(canonical, quality == null ? 0 : quality.version());
			}
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

	private static void append(StringBuilder target, Object value) {
		var text = value == null ? "" : value.toString();
		target.append(text.length()).append(':').append(text).append('\n');
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
