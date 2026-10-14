package com.medicationvideo.platform.annotationreview;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.medicationvideo.platform.aicandidate.ActionCandidateJobCompletedEvent;
import com.medicationvideo.platform.aicandidate.ActionCandidateJobService;
import com.medicationvideo.platform.aicandidate.ActionCandidateResponse;
import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService;
import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService.CandidateEvidence;
import com.medicationvideo.platform.aicandidate.HandPoseJobCompletedEvent;
import com.medicationvideo.platform.assetcapture.CaptureGroupReceiptState;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService.CaptureGroupFacts;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService.CaptureGroupView;
import com.medicationvideo.platform.iamproject.SystemActors;

/**
 * V1.36 fully-automatic optimistic approval.
 *
 * <p>
 * Once a capture group is fully received and every present camera has both
 * provenance-valid hand detection with candidates and at least one action candidate
 * ("dual qualification"), the system assigns the annotation task, fills each camera from
 * the action candidates, submits a first revision and approves it as {@code Revision 1} —
 * opening the group for HDF5 export without any human touch. The whole flow runs as the
 * internal {@link SystemActors#AUTO_SYSTEM} actor; it never impersonates a real
 * administrator account.
 *
 * <p>
 * Cross-camera correspondence is intentionally recorded as single-camera
 * {@code UNCERTAIN} correspondences; multi-camera action alignment stays a human
 * responsibility. When any camera is missing, detection fails, provenance is invalid or
 * no action candidate exists, the group is left untouched for human exception handling. A
 * task created by a human, or any task that already carries a revision (for example after
 * a one-click re-edit), is never overwritten.
 *
 * <p>
 * The loop is closed both by worker completion events (immediate) and a bounded, paged
 * scheduled reconciliation (restart-safe). Assignment, submission and approval run in a
 * single transaction, so any failure rolls the whole adoption back.
 */
@Component
public class AutomaticOptimisticApprovalService {

	private static final Logger LOG = LoggerFactory.getLogger(AutomaticOptimisticApprovalService.class);

	private static final String TRACE_ID = "auto-approval";

	private static final String AUTO_NOTE = "系统自动采纳";

	private static final String AUTO_REVIEW_NOTE = "系统自动核验通过";

	private static final String UNCERTAIN_REASON = "单机位自动采纳，多机位对应待人工确认";

	private static final int PAGE_SIZE = 100;

	private final CaptureGroupViewQueryService captureGroupViews;

	private final CandidateEvidenceQueryService candidateEvidence;

	private final ActionCandidateJobService actionCandidates;

	private final AnnotationTaskService annotationTaskService;

	private final AnnotationTaskRepository taskRepository;

	private final AnnotationTaskViewRepository taskViewRepository;

	private final AnnotationRevisionRepository revisionRepository;

	private final TransactionTemplate transactionTemplate;

	private final boolean enabled;

	private int page;

	AutomaticOptimisticApprovalService(CaptureGroupViewQueryService captureGroupViews,
			CandidateEvidenceQueryService candidateEvidence, ActionCandidateJobService actionCandidates,
			AnnotationTaskService annotationTaskService, AnnotationTaskRepository taskRepository,
			AnnotationTaskViewRepository taskViewRepository, AnnotationRevisionRepository revisionRepository,
			PlatformTransactionManager transactionManager,
			@Value("${platform.annotation.auto-approval.enabled:true}") boolean enabled) {
		this.captureGroupViews = captureGroupViews;
		this.candidateEvidence = candidateEvidence;
		this.actionCandidates = actionCandidates;
		this.annotationTaskService = annotationTaskService;
		this.taskRepository = taskRepository;
		this.taskViewRepository = taskViewRepository;
		this.revisionRepository = revisionRepository;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.enabled = enabled;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onActionCandidateCompleted(ActionCandidateJobCompletedEvent event) {
		if (!this.enabled) {
			return;
		}
		adoptCaptureGroupIfEligible(event.projectId(), event.captureGroupId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onHandPoseCompleted(HandPoseJobCompletedEvent event) {
		if (!this.enabled) {
			return;
		}
		adoptCaptureGroupIfEligible(event.projectId(), event.captureGroupId());
	}

	public void adoptCaptureGroupIfEligible(UUID projectId, UUID captureGroupId) {
		if (projectId == null || captureGroupId == null) {
			return;
		}
		try {
			CaptureGroupFacts facts = this.captureGroupViews.get(projectId, captureGroupId);
			if (isEligible(facts)) {
				this.transactionTemplate.executeWithoutResult(status -> adoptCaptureGroup(facts));
			}
		}
		catch (RuntimeException exception) {
			LOG.warn("Automatic optimistic approval deferred for capture group {}: {}", captureGroupId,
					exception.getClass().getSimpleName());
		}
	}

	/**
	 * Bounded, paged reconciliation that survives restarts; each group adoption owns its
	 * transaction. Invoked by {@link AutomaticOptimisticApprovalScheduler} on a fixed
	 * delay in production and directly in tests.
	 */
	public void reconcile() {
		List<CaptureGroupFacts> groups = this.captureGroupViews.findCompleteGroupsForSystemProcessing(this.page,
				PAGE_SIZE);
		this.page = groups.size() < PAGE_SIZE ? 0 : this.page + 1;
		for (CaptureGroupFacts facts : groups) {
			try {
				if (isEligible(facts)) {
					this.transactionTemplate.executeWithoutResult(status -> adoptCaptureGroup(facts));
				}
			}
			catch (RuntimeException exception) {
				LOG.warn("Automatic optimistic approval deferred for capture group {}: {}", facts.captureGroupId(),
						exception.getClass().getSimpleName());
			}
		}
	}

	/**
	 * Eligible when the group is fully received with every camera present, no completed
	 * or human-owned task/revision exists, and each present camera has provenance-valid
	 * hand detection with candidates plus at least one action candidate.
	 */
	boolean isEligible(CaptureGroupFacts facts) {
		if (!groupReady(facts)) {
			return false;
		}
		return !isBlockedByTaskState(facts.projectId(), facts.captureGroupId());
	}

	/**
	 * Dual-qualification check against the group facts and the AI candidate read models.
	 */
	private boolean groupReady(CaptureGroupFacts facts) {
		if (facts.receiptState() != CaptureGroupReceiptState.COMPLETE) {
			return false;
		}
		List<CaptureGroupView> views = facts.views();
		if (views.isEmpty() || views.stream().anyMatch(view -> !view.present())) {
			return false;
		}
		List<UUID> clipAssetIds = views.stream().map(CaptureGroupView::clipAssetId).toList();
		Map<UUID, CandidateEvidence> evidenceByClip = this.candidateEvidence
			.findByClipAssets(facts.projectId(), clipAssetIds)
			.stream()
			.collect(Collectors.toMap(CandidateEvidence::clipAssetId, evidence -> evidence, (left, right) -> left));
		for (CaptureGroupView view : views) {
			if (!isSuccessfulHandEvidence(evidenceByClip.get(view.clipAssetId()))) {
				return false;
			}
			if (this.actionCandidates.listByCaptureGroup(facts.projectId(), facts.captureGroupId(), view.clipAssetId())
				.isEmpty()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Completed tasks, any existing revision, or human-created tasks block auto-approval.
	 */
	private boolean isBlockedByTaskState(UUID projectId, UUID captureGroupId) {
		var task = this.taskRepository.findByProjectIdAndCaptureGroupId(projectId, captureGroupId);
		if (task.isEmpty()) {
			return false;
		}
		AnnotationTask existing = task.get();
		if (existing.getStatus() == AnnotationTaskStatus.COMPLETED) {
			return true;
		}
		if (this.revisionRepository.findTopByAnnotationTaskIdOrderByRevisionNumberDesc(existing.getAnnotationTaskId())
			.isPresent()) {
			return true;
		}
		// A task created by a human actor means a person started annotation; never take
		// it over.
		return !SystemActors.AUTO_SYSTEM.equals(existing.getCreatedBy());
	}

	private static boolean isSuccessfulHandEvidence(CandidateEvidence evidence) {
		return evidence != null && evidence.provenanceValid()
				&& CandidateEvidenceQueryService.EXECUTION_STATUS_SUCCEEDED_WITH_CANDIDATES
					.equals(evidence.executionStatus());
	}

	/**
	 * Runs inside a single transaction so any failure rolls back the whole assignment,
	 * submission and approval, never leaving a partially auto-annotated group.
	 */
	private void adoptCaptureGroup(CaptureGroupFacts prechecked) {
		UUID projectId = prechecked.projectId();
		UUID captureGroupId = prechecked.captureGroupId();

		// Idempotent assignment locks the project row first, serialising concurrent
		// triggers.
		AnnotationTaskResponse task = this.annotationTaskService.assignForHumanLabel(projectId, captureGroupId,
				SystemActors.AUTO_SYSTEM, SystemActors.AUTO_SYSTEM, "auto-assign-" + captureGroupId,
				UUID.randomUUID().toString(), TRACE_ID);
		UUID taskId = task.annotationTaskId();
		if (AnnotationTaskStatus.COMPLETED.name().equals(task.status())
				|| this.revisionRepository.findTopByAnnotationTaskIdOrderByRevisionNumberDesc(taskId).isPresent()
				|| !SystemActors.AUTO_SYSTEM.equals(task.createdBy())) {
			return;
		}

		// Re-read the locked group under the project lock and re-verify dual
		// qualification authoritatively.
		CaptureGroupFacts facts = this.captureGroupViews.lockAndGet(projectId, captureGroupId);
		if (!groupReady(facts)) {
			return;
		}

		for (AnnotationTaskCameraViewResponse view : task.cameraViews()) {
			int cameraNumber = view.cameraNumber();
			UUID clipAssetId = view.clipAssetId();
			if (clipAssetId == null) {
				throw new IllegalStateException("Missing camera view " + cameraNumber + " in completed group");
			}
			if (view.state() == AnnotationTaskViewState.SUBMITTED && !view.segments().isEmpty()) {
				continue;
			}
			long expectedVersion = this.taskViewRepository.findForUpdate(projectId, taskId, cameraNumber)
				.map(AnnotationTaskView::getVersion)
				.orElse(view.version());
			AnnotationCameraViewUpdateCommand command = new AnnotationCameraViewUpdateCommand(expectedVersion,
					task.groupInputVersion(), AnnotationTaskViewState.SUBMITTED,
					segmentsFor(projectId, captureGroupId, clipAssetId), AUTO_NOTE, null);
			this.annotationTaskService.updateCameraView(projectId, taskId, cameraNumber, SystemActors.AUTO_SYSTEM, true,
					"auto-view-" + taskId + "-" + cameraNumber, UUID.randomUUID().toString(), TRACE_ID, command);
		}

		submitAndApprove(projectId, taskId, captureGroupId);
	}

	private List<AnnotationCameraViewSegmentCommand> segmentsFor(UUID projectId, UUID captureGroupId,
			UUID clipAssetId) {
		List<ActionCandidateResponse> candidates = this.actionCandidates
			.listByCaptureGroup(projectId, captureGroupId, clipAssetId)
			.stream()
			.sorted(Comparator.comparingLong(ActionCandidateResponse::startMs)
				.thenComparingLong(ActionCandidateResponse::endMs))
			.toList();
		List<AnnotationCameraViewSegmentCommand> segments = new ArrayList<>();
		for (ActionCandidateResponse candidate : candidates) {
			if (candidate.startMs() >= 0 && candidate.endMs() > candidate.startMs()) {
				segments.add(new AnnotationCameraViewSegmentCommand(UUID.randomUUID(), candidate.startMs(),
						candidate.endMs(), candidate.actionLabel(), List.of(candidate.actionCandidateId())));
			}
		}
		if (segments.isEmpty()) {
			throw new IllegalStateException("No usable action candidates for clip " + clipAssetId);
		}
		return segments;
	}

	private void submitAndApprove(UUID projectId, UUID taskId, UUID captureGroupId) {
		AnnotationTask currentTask = this.taskRepository.findForUpdate(projectId, taskId).orElseThrow();
		if (currentTask.getStatus() != AnnotationTaskStatus.ASSIGNED) {
			return;
		}
		List<AnnotationTaskCameraViewResponse> currentViews = this.taskViewRepository
			.findAllByAnnotationTaskIdOrderByCameraNumberAsc(taskId)
			.stream()
			.map(AnnotationTaskCameraViewResponse::from)
			.toList();

		List<AnnotationCorrespondenceCommand> correspondences = new ArrayList<>();
		for (AnnotationTaskCameraViewResponse view : currentViews) {
			for (Map<String, Object> segment : view.segments()) {
				UUID segmentId = parseUuid(segment.get("segment_id"));
				if (segmentId != null) {
					correspondences.add(new AnnotationCorrespondenceCommand(UUID.randomUUID(),
							AnnotationCorrespondenceStatus.UNCERTAIN,
							List.of(new AnnotationCorrespondenceSegmentRefCommand(view.cameraNumber(), segmentId)),
							UNCERTAIN_REASON));
				}
			}
		}
		if (correspondences.isEmpty()) {
			throw new IllegalStateException("No correspondences produced for capture group " + captureGroupId);
		}

		AnnotationRevisionSubmitCommand submitCommand = new AnnotationRevisionSubmitCommand(currentTask.getVersion(),
				currentTask.getGroupInputVersion(), correspondences, AUTO_NOTE);
		AnnotationRevisionResponse revision = this.annotationTaskService.submitRevision(projectId, taskId,
				SystemActors.AUTO_SYSTEM, "auto-rev-" + taskId, UUID.randomUUID().toString(), TRACE_ID, submitCommand);

		List<Integer> reviewedCameraNumbers = currentViews.stream()
			.filter(view -> view.state() == AnnotationTaskViewState.SUBMITTED)
			.map(AnnotationTaskCameraViewResponse::cameraNumber)
			.toList();
		AnnotationReviewCommand reviewCommand = new AnnotationReviewCommand(AnnotationReviewDecision.APPROVED,
				AUTO_REVIEW_NOTE, reviewedCameraNumbers, List.of());
		this.annotationTaskService.reviewRevision(projectId, revision.annotationRevisionId(), SystemActors.AUTO_SYSTEM,
				"auto-review-" + revision.annotationRevisionId(), UUID.randomUUID().toString(), TRACE_ID,
				reviewCommand);
		LOG.info("Capture group {} automatically approved as revision {} by {}", captureGroupId,
				revision.revisionNumber(), SystemActors.AUTO_SYSTEM);
	}

	private static UUID parseUuid(Object value) {
		if (value instanceof UUID uuid) {
			return uuid;
		}
		if (value instanceof String text && !text.isBlank()) {
			try {
				return UUID.fromString(text);
			}
			catch (IllegalArgumentException ignored) {
				return null;
			}
		}
		return null;
	}

}
