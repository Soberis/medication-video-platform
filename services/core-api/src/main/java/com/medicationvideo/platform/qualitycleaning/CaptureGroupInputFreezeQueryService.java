package com.medicationvideo.platform.qualitycleaning;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService;
import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;

/**
 * 标注输入冻结的唯一边界：把 CaptureGroup 接入事实投影成人工标注、复核与导出共用的准入快照。
 *
 * <p>
 * 准入事实由系统在准备人工任务时写入 `capture_group_quality_review`（以 SYSTEM 身份、按事实推导结果），
 * 该行自此只作**输入冻结的持久化凭据**；历史人工质量评审行仍可读，但不参与任何准入判断。
 */
@Service
public class CaptureGroupInputFreezeQueryService {

	static final String SYSTEM_ACTOR_ID = "SYSTEM";

	private static final String FREEZE_REASON = "系统在准备人工任务时按接入事实冻结输入快照，不要求人工质量确认。";

	private static final String AUDIT_EVENT = "CAPTURE_GROUP_INPUT_FROZEN";

	private final CaptureGroupViewQueryService captureGroupViewQueryService;

	private final CaptureGroupQualityReviewRepository repository;

	private final AuditEventRecorder auditRecorder;

	CaptureGroupInputFreezeQueryService(CaptureGroupViewQueryService captureGroupViewQueryService,
			CaptureGroupQualityReviewRepository repository, AuditEventRecorder auditRecorder) {
		this.captureGroupViewQueryService = captureGroupViewQueryService;
		this.repository = repository;
		this.auditRecorder = auditRecorder;
	}

	/** 只读计算当前输入快照；不落库、不锁定。 */
	@Transactional(readOnly = true)
	public CaptureGroupInputFreeze.Snapshot get(UUID projectId, UUID captureGroupId) {
		return CaptureGroupInputFreeze.of(this.captureGroupViewQueryService.get(projectId, captureGroupId));
	}

	/** 批量只读计算准入标志，供列表投影使用。 */
	@Transactional(readOnly = true)
	public Map<UUID, Boolean> getAdmittingById(UUID projectId, List<UUID> captureGroupIds) {
		var selected = new HashSet<>(captureGroupIds);
		if (selected.isEmpty()) {
			return Map.of();
		}
		var admitting = new LinkedHashMap<UUID, Boolean>();
		for (var facts : this.captureGroupViewQueryService.findAll(projectId)) {
			if (selected.contains(facts.captureGroupId())) {
				admitting.put(facts.captureGroupId(), CaptureGroupInputFreeze.of(facts).admitting());
			}
		}
		for (var captureGroupId : selected) {
			admitting.putIfAbsent(captureGroupId, false);
		}
		return Map.copyOf(admitting);
	}

	/** 锁定接入事实并计算输入快照；不落库。 */
	@Transactional
	public CaptureGroupInputFreeze.Snapshot lockAndGet(UUID projectId, UUID captureGroupId) {
		return CaptureGroupInputFreeze.of(this.captureGroupViewQueryService.lockAndGet(projectId, captureGroupId));
	}

	/**
	 * 冻结输入：输入未变时复用当前冻结凭据，输入已变时以 SYSTEM 身份写入新修订。幂等，可重复调用。
	 */
	@Transactional
	public FrozenInput freeze(UUID projectId, UUID captureGroupId, Instant now) {
		var snapshot = this.lockAndGet(projectId, captureGroupId);
		var current = this.repository.findForUpdate(projectId, captureGroupId).stream().findFirst().orElse(null);
		if (current != null && Objects.equals(current.getInputVersion(), snapshot.inputVersion())
				&& Objects.equals(current.getConfirmedViewSetSha256(), snapshot.viewSetSha256())) {
			return new FrozenInput(current.getCaptureGroupQualityReviewId(), current.getRevision(),
					current.getConfirmedViewSet(), current.getInputVersion(), current.getConfirmedViewSetSha256(),
					snapshot);
		}
		var revision = (current == null ? 0 : current.getRevision()) + 1;
		var decidedAt = now == null ? Instant.now().truncatedTo(ChronoUnit.MICROS) : now;
		var review = this.repository
			.saveAndFlush(CaptureGroupQualityReview.record(projectId, captureGroupId, resultFor(snapshot),
					FREEZE_REASON, SYSTEM_ACTOR_ID, revision, snapshot.confirmedViewSet(), snapshot.inputVersion(),
					snapshot.viewSetSha256(), idempotencyKey(revision, snapshot.viewSetSha256()), decidedAt));
		var details = new LinkedHashMap<String, String>();
		details.put("capture_group_id", captureGroupId.toString());
		details.put("revision", Long.toString(revision));
		details.put("input_version", snapshot.inputVersion());
		details.put("confirmed_view_set_sha256", snapshot.viewSetSha256());
		details.put("annotatable_view_count", Integer.toString(snapshot.annotatableViewCount()));
		details.put("view_count", Integer.toString(snapshot.viewCount()));
		details.put("receipt_state", snapshot.receiptState());
		var requestId = UUID.randomUUID().toString();
		this.auditRecorder.record(new AuditEventCommand(decidedAt, AuditActorType.SYSTEM, SYSTEM_ACTOR_ID, projectId,
				AUDIT_EVENT, "CaptureGroupQualityReview", review.getCaptureGroupQualityReviewId().toString(),
				Long.toString(revision), AuditOutcome.SUCCEEDED, "SYSTEM_INPUT_FREEZE_AT_TASK_PREPARATION", requestId,
				requestId, "PROJECT_MEMBERSHIP_MEMBER", Map.copyOf(details)));
		return new FrozenInput(review.getCaptureGroupQualityReviewId(), revision, snapshot.confirmedViewSet(),
				snapshot.inputVersion(), snapshot.viewSetSha256(), snapshot);
	}

	private static CaptureGroupQualityResult resultFor(CaptureGroupInputFreeze.Snapshot snapshot) {
		if (!snapshot.admitting()) {
			return CaptureGroupQualityResult.NEEDS_REVIEW;
		}
		return snapshot.fullyAnnotatable() ? CaptureGroupQualityResult.QUALIFIED
				: CaptureGroupQualityResult.QUALIFIED_WITH_EXCLUSIONS;
	}

	private static String idempotencyKey(long revision, String viewSetSha256) {
		return "system-input-freeze-" + revision + "-" + viewSetSha256.substring(0, 40);
	}

	/** 一次成功冻结的持久化凭据 + 当时计算的快照。 */
	public record FrozenInput(UUID freezeId, long revision, Map<String, Object> confirmedViewSet, String inputVersion,
			String viewSetSha256, CaptureGroupInputFreeze.Snapshot snapshot) {
	}

}
