package com.medicationvideo.platform.annotationreview;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService;
import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService.CandidateEvidence;
import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService.RunSummary;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService.CaptureGroupFacts;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService.CaptureGroupView;
import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;
import com.medicationvideo.platform.iamproject.ProjectAccessService;
import com.medicationvideo.platform.mediaprivacy.MediaDurationQueryService;
import com.medicationvideo.platform.qualitycleaning.CaptureGroupInputFreeze;
import com.medicationvideo.platform.qualitycleaning.CaptureGroupInputFreezeQueryService;

/** Coordinates the CaptureGroup-owned task, revision and event-cleaning path. */
@Service
class CaptureGroupAnnotationWorkflow {

	private final ProjectAccessService projectAccessService;

	private final CaptureGroupViewQueryService captureGroupViewQueryService;

	private final CaptureGroupInputFreezeQueryService inputFreezeQueryService;

	private final MediaDurationQueryService mediaDurationQueryService;

	private final CandidateEvidenceQueryService candidateEvidenceQueryService;

	private final AnnotationLabelSchemaService labelSchemaService;

	private final AnnotationTaskRepository taskRepository;

	private final AnnotationRevisionRepository revisionRepository;

	private final AnnotationReviewRepository reviewRepository;

	private final AnnotationTaskViewService taskViewService;

	private final AuditEventRecorder auditRecorder;

	CaptureGroupAnnotationWorkflow(ProjectAccessService projectAccessService,
			CaptureGroupViewQueryService captureGroupViewQueryService,
			CaptureGroupInputFreezeQueryService inputFreezeQueryService,
			MediaDurationQueryService mediaDurationQueryService,
			CandidateEvidenceQueryService candidateEvidenceQueryService,
			AnnotationLabelSchemaService labelSchemaService, AnnotationTaskRepository taskRepository,
			AnnotationRevisionRepository revisionRepository, AnnotationReviewRepository reviewRepository,
			AnnotationTaskViewService taskViewService, AuditEventRecorder auditRecorder) {
		this.projectAccessService = projectAccessService;
		this.captureGroupViewQueryService = captureGroupViewQueryService;
		this.inputFreezeQueryService = inputFreezeQueryService;
		this.mediaDurationQueryService = mediaDurationQueryService;
		this.candidateEvidenceQueryService = candidateEvidenceQueryService;
		this.labelSchemaService = labelSchemaService;
		this.taskRepository = taskRepository;
		this.revisionRepository = revisionRepository;
		this.reviewRepository = reviewRepository;
		this.taskViewService = taskViewService;
		this.auditRecorder = auditRecorder;
	}

	@Transactional
	AnnotationTaskResponse assignForHumanLabel(UUID projectId, UUID captureGroupId, String actorId,
			String assigneeActorId, String idempotencyKey, String requestId, String traceId) {
		this.projectAccessService.lockAndRequireMember(projectId, actorId);
		if (!actorId.equals(assigneeActorId)) {
			this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		}
		this.projectAccessService.lockAndRequireMember(projectId, assigneeActorId);
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var frozen = this.inputFreezeQueryService.freeze(projectId, captureGroupId, now);
		if (!frozen.snapshot().admitting()) {
			throw groupNotAnnotatable();
		}
		var existing = this.taskRepository.findForUpdateByProjectIdAndCaptureGroupId(projectId, captureGroupId);
		if (existing.isPresent()) {
			var task = existing.get();
			if (!Objects.equals(task.getGroupInputVersion(), frozen.inputVersion())) {
				this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
			}
			var previousStatus = task.getStatus();
			var previousInputVersion = task.getGroupInputVersion();
			var previousInputFreezeRevision = task.getGroupQualityRevision();
			var inputChanged = task.refreshGroupQualitySnapshot(frozen.freezeId(), frozen.revision(),
					frozen.confirmedViewSet(), frozen.inputVersion(), now);
			this.taskRepository.saveAndFlush(task);
			var facts = this.captureGroupViewQueryService.lockAndGet(projectId, captureGroupId);
			this.taskViewService.synchronize(task, facts.views(), inputChanged, now);
			if (inputChanged && previousStatus != AnnotationTaskStatus.ASSIGNED) {
				this.auditRecorder.record(new AuditEventCommand(now, AuditActorType.USER, actorId, projectId,
						"CAPTURE_GROUP_ANNOTATION_TASK_REOPENED_FOR_INPUT_CHANGE", "AnnotationTask",
						task.getAnnotationTaskId().toString(), Long.toString(task.getVersion()), AuditOutcome.SUCCEEDED,
						"GROUP_INPUT_VERSION_CHANGED", requestId, traceId, "PROJECT_MEMBERSHIP_ADMIN",
						Map.of("capture_group_id", captureGroupId.toString(), "previous_status", previousStatus.name(),
								"new_status", task.getStatus().name(), "previous_input_version",
								String.valueOf(previousInputVersion), "input_version", frozen.inputVersion(),
								"previous_input_freeze_revision", String.valueOf(previousInputFreezeRevision),
								"input_freeze_revision", Long.toString(frozen.revision()), "assignee_actor_id",
								task.getAssigneeActorId(), "created_by", task.getCreatedBy(),
								"revision_chain_preserved", "true")));
			}
			return AnnotationTaskResponse.from(task, true, null, null, null);
		}

		var task = this.taskRepository.saveAndFlush(AnnotationTask.createForCaptureGroup(projectId, captureGroupId,
				assigneeActorId, actorId, idempotencyKey, this.labelSchemaService.currentBinding(), frozen.freezeId(),
				frozen.revision(), frozen.confirmedViewSet(), frozen.inputVersion(), now));
		var facts = this.captureGroupViewQueryService.lockAndGet(projectId, captureGroupId);
		this.taskViewService.synchronize(task, facts.views(), false, now);
		this.auditRecorder.record(new AuditEventCommand(now, AuditActorType.USER, actorId, projectId,
				"CAPTURE_GROUP_ANNOTATION_TASK_ASSIGNED", "AnnotationTask", task.getAnnotationTaskId().toString(),
				Long.toString(task.getVersion()), AuditOutcome.SUCCEEDED, "EXPLICIT_HUMAN_LABEL_ASSIGNMENT", requestId,
				traceId, "PROJECT_MEMBERSHIP_MEMBER",
				Map.of("capture_group_id", captureGroupId.toString(), "input_freeze_id", frozen.freezeId().toString(),
						"input_freeze_revision", Long.toString(frozen.revision()), "assignee_actor_id",
						task.getAssigneeActorId(), "created_by", task.getCreatedBy(), "input_version",
						frozen.inputVersion(), "assignment_mode", "EXPLICIT_HUMAN_LABEL", "annotatable_view_count",
						Integer.toString(frozen.snapshot().annotatableViewCount()), "label_schema_id",
						task.getLabelSchemaId().toString(), "label_schema_digest", task.getLabelSchemaDigest())));
		return AnnotationTaskResponse.from(task, true, null, null, null);
	}

	AnnotationRevisionResponse submitRevision(UUID projectId, AnnotationTask task, String actorId,
			String idempotencyKey, String requestId, String traceId, AnnotationRevisionSubmitCommand request,
			Map<String, Object> document, String documentSha256) {
		var groupContext = requireGroupSubmissionContext(projectId, task);
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		task.submit(now);
		var revisionNumber = this.revisionRepository
			.findTopByAnnotationTaskIdOrderByRevisionNumberDesc(task.getAnnotationTaskId())
			.map(revision -> revision.getRevisionNumber() + 1)
			.orElse(1);
		var revision = AnnotationRevision.submitForCaptureGroupV2(task, revisionNumber, document, documentSha256,
				actorId, idempotencyKey, now, groupContext.confirmedViewSet(), groupContext.inputVersion());
		this.revisionRepository.saveAndFlush(revision);
		this.taskRepository.saveAndFlush(task);
		var auditDetails = new LinkedHashMap<String, String>();
		auditDetails.put("annotation_task_id", task.getAnnotationTaskId().toString());
		auditDetails.put("label_schema_id", revision.getLabelSchemaId().toString());
		auditDetails.put("label_schema_digest", revision.getLabelSchemaDigest());
		auditDetails.put("document_sha256", documentSha256);
		auditDetails.put("camera_view_count", Integer.toString(((List<?>) document.get("camera_views")).size()));
		auditDetails.put("correspondence_count", Integer.toString(request.correspondences().size()));
		auditDetails.put("capture_group_id", task.getCaptureGroupId().toString());
		auditDetails.put("group_input_version", groupContext.inputVersion());
		this.auditRecorder.record(new AuditEventCommand(now, AuditActorType.USER, actorId, projectId,
				"ANNOTATION_REVISION_SUBMITTED", "AnnotationRevision", revision.getAnnotationRevisionId().toString(),
				Integer.toString(revision.getRevisionNumber()), AuditOutcome.SUCCEEDED, "MANUAL_ANNOTATION_SUBMITTED",
				requestId, traceId, "PROJECT_MEMBERSHIP_MEMBER", auditDetails));
		return AnnotationRevisionResponse.from(revision);
	}

	void requireCurrentGroupInput(UUID projectId, AnnotationTask task) {
		var facts = this.captureGroupViewQueryService.lockAndGet(projectId, task.getCaptureGroupId());
		var snapshot = CaptureGroupInputFreeze.of(facts);
		// 只比对 inputVersion 摘要与逐机位事实：冻结快照经 JSON 往返后装箱数值类型会变化，整表 equals 不可靠。
		if (!snapshot.admitting() || !Objects.equals(task.getGroupInputVersion(), snapshot.inputVersion())
				|| !viewSetStillCurrent(task.getConfirmedViewSet(), facts)) {
			throw primaryViewInputChanged();
		}
	}

	void requireCameraViewEligibleForSubmission(AnnotationTask task, AnnotationTaskView view) {
		if (!(task.getConfirmedViewSet().get("views") instanceof List<?> views)) {
			throw primaryViewInputChanged();
		}
		var confirmed = views.stream()
			.filter(Map.class::isInstance)
			.map(Map.class::cast)
			.filter(row -> row.get("camera_number") instanceof Number number
					&& number.intValue() == view.getCameraNumber())
			.findFirst()
			.orElse(null);
		if (confirmed == null || !Boolean.TRUE.equals(confirmed.get("present"))
				|| !Boolean.TRUE.equals(confirmed.get("asset_available"))
				|| !"INGESTED".equals(confirmed.get("media_status"))
				|| !CaptureGroupInputFreeze.ANNOTATABLE.equals(confirmed.get("quality_review_decision"))
				|| !Objects.equals(confirmed.get("clip_asset_id"),
						view.getClipAssetId() == null ? null : view.getClipAssetId().toString())) {
			throw new AnnotationTaskException("ANNOTATION_CAMERA_VIEW_NOT_REVIEWABLE",
					"Only a present, ingested and annotatable camera view can be submitted.", 409);
		}
	}

	void recordCameraViewUpdated(UUID projectId, AnnotationTask task, AnnotationTaskView view, String actorId,
			String requestId, String traceId) {
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		this.auditRecorder.record(new AuditEventCommand(now, AuditActorType.USER, actorId, projectId,
				"ANNOTATION_CAMERA_VIEW_UPDATED", "AnnotationTaskView", view.getAnnotationTaskViewId().toString(),
				Long.toString(view.getVersion()), AuditOutcome.SUCCEEDED, "CAMERA_VIEW_" + view.getState().name(),
				requestId, traceId, "PROJECT_MEMBERSHIP_MEMBER",
				Map.of("annotation_task_id", task.getAnnotationTaskId().toString(), "capture_group_id",
						task.getCaptureGroupId().toString(), "camera_number", Integer.toString(view.getCameraNumber()),
						"view_state", view.getState().name(), "camera_stream_version",
						Long.toString(view.getCameraStreamVersion()), "clip_asset_version",
						Long.toString(view.getClipAssetVersion()), "task_version", Long.toString(task.getVersion()))));
	}

	AnnotationReviewResponse reviewRevision(UUID projectId, AnnotationRevision revision, String actorId,
			String idempotencyKey, String requestId, String traceId, AnnotationReviewCommand request) {
		var task = this.taskRepository.findForUpdate(projectId, revision.getAnnotationTaskId())
			.orElseThrow(CaptureGroupAnnotationWorkflow::taskNotFound);
		if (task.getStatus() != AnnotationTaskStatus.SUBMITTED) {
			throw new AnnotationTaskException("ANNOTATION_REVISION_NOT_CURRENT",
					"Only the current submitted annotation revision can be reviewed.", 409);
		}
		var groupContext = requireGroupReviewContext(projectId, revision, task, request);
		if (request.decision() == AnnotationReviewDecision.APPROVED) {
			validateRevisionBounds(projectId, revision);
		}
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var review = this.reviewRepository.saveAndFlush(AnnotationReview.record(revision, request.decision(),
				request.reviewNote(), actorId, idempotencyKey, now, groupContext));
		var reviewReason = revision.getSubmittedBy().equals(actorId) ? "SELF_ADMIN_APPROVAL"
				: "INDEPENDENT_ANNOTATION_REVIEW";
		if (review.getDecision() == AnnotationReviewDecision.REJECTED) {
			task.returnForRework(now);
			this.taskRepository.saveAndFlush(task);
			if ("annotation-document.v2".equals(revision.getAnnotationSchemaVersion())) {
				this.taskViewService.restoreAfterReview(task, revision, request.returnCameraNumbers(), actorId,
						idempotencyKey, now);
			}
		}
		else {
			task.complete(now);
			this.taskRepository.saveAndFlush(task);
		}
		this.auditRecorder.record(new AuditEventCommand(now, AuditActorType.USER, actorId, projectId,
				"ANNOTATION_REVISION_REVIEWED", "AnnotationReview", review.getAnnotationReviewId().toString(),
				review.getDecision().name(), AuditOutcome.SUCCEEDED, reviewReason, requestId, traceId,
				"PROJECT_MEMBERSHIP_ADMIN",
				Map.of("annotation_revision_id", revision.getAnnotationRevisionId().toString(), "annotation_task_id",
						revision.getAnnotationTaskId().toString(), "decision", review.getDecision().name(),
						"reopened_for_rework",
						Boolean.toString(review.getDecision() == AnnotationReviewDecision.REJECTED), "task_completed",
						Boolean.toString(review.getDecision() == AnnotationReviewDecision.APPROVED))));
		return AnnotationReviewResponse.from(review);
	}

	private GroupSubmissionContext requireGroupSubmissionContext(UUID projectId, AnnotationTask task) {
		this.requireCurrentGroupInput(projectId, task);
		return new GroupSubmissionContext(task.getConfirmedViewSet(), task.getGroupInputVersion());
	}

	private GroupReviewContext requireGroupReviewContext(UUID projectId, AnnotationRevision revision,
			AnnotationTask task, AnnotationReviewCommand request) {
		var facts = this.captureGroupViewQueryService.lockAndGet(projectId, revision.getCaptureGroupId());
		var snapshot = CaptureGroupInputFreeze.of(facts);
		if (!snapshot.admitting() || !Objects.equals(snapshot.inputVersion(), revision.getInputVersion())
				|| !viewSetStillCurrent(revision.getConfirmedViewSet(), facts)) {
			throw new AnnotationTaskException("ANNOTATION_REVISION_NO_LONGER_QUALIFIED",
					"The frozen CaptureGroup input changed since submission and can no longer be reviewed.", 409);
		}
		var revisionCameras = annotationSchemaCameras(revision);
		validateReviewCameraScope(revision, revisionCameras, request);
		var qualifiedViews = new ArrayList<Map<String, Object>>();
		var rejectedMissingViews = new ArrayList<Map<String, Object>>();
		for (var view : facts.views()) {
			var details = groupViewDetails(view);
			if (CaptureGroupInputFreeze.annotatable(view)) {
				if ("annotation-document.v1".equals(revision.getAnnotationSchemaVersion())) {
					details.put("is_primary", Objects.equals(view.clipAssetId(), task.getPrimaryClipAssetId()));
				}
				qualifiedViews.add(Collections.unmodifiableMap(new LinkedHashMap<>(details)));
			}
			else {
				details.put("rejection_reason", groupViewRejectionReason(view));
				rejectedMissingViews.add(Collections.unmodifiableMap(new LinkedHashMap<>(details)));
			}
		}
		var primaryQualified = qualifiedViews.stream()
			.anyMatch(view -> Objects.equals(view.get("clip_asset_id"),
					task.getPrimaryClipAssetId() == null ? null : task.getPrimaryClipAssetId().toString()));
		if ("annotation-document.v1".equals(revision.getAnnotationSchemaVersion())
				&& (!task.hasPrimaryView() || !primaryQualified)) {
			throw new AnnotationTaskException("ANNOTATION_PRIMARY_VIEW_NOT_ELIGIBLE",
					"The primary view must remain present, reviewable and qualified for Review.", 409);
		}
		var candidateEvidence = this.candidateEvidenceQueryService
			.findByClipAssets(projectId,
					facts.views().stream().map(CaptureGroupView::clipAssetId).filter(Objects::nonNull).toList())
			.stream()
			.map(CaptureGroupAnnotationWorkflow::candidateEvidenceDetails)
			.toList();
		return new GroupReviewContext(revision.getCaptureGroupId(), revision.getRevisionNumber(),
				"annotation-document.v1".equals(revision.getAnnotationSchemaVersion()) ? task.getPrimaryClipAssetId()
						: null,
				"annotation-document.v1".equals(revision.getAnnotationSchemaVersion()) ? task.getPrimaryCameraNumber()
						: null,
				revision.getConfirmedViewSet(), revision.getInputVersion(), task.getCaptureGroupQualityReviewId(),
				groupResultFor(snapshot), task.getGroupQualityRevision(), qualifiedViews, rejectedMissingViews,
				candidateEvidence, request.reviewedCameraNumbers(), request.returnCameraNumbers());
	}

	/** 冻结快照的准入结论，与 {@code CaptureGroupInputFreezeQueryService} 的落库取值保持一致。 */
	private static String groupResultFor(CaptureGroupInputFreeze.Snapshot snapshot) {
		if (!snapshot.admitting()) {
			return "NEEDS_REVIEW";
		}
		return snapshot.fullyAnnotatable() ? "QUALIFIED" : "QUALIFIED_WITH_EXCLUSIONS";
	}

	private static void validateReviewCameraScope(AnnotationRevision revision, List<Integer> revisionCameras,
			AnnotationReviewCommand request) {
		if ("annotation-document.v1".equals(revision.getAnnotationSchemaVersion())) {
			if (!request.reviewedCameraNumbers().isEmpty() || !request.returnCameraNumbers().isEmpty()) {
				throw new AnnotationTaskException("ANNOTATION_REVIEW_CAMERA_SCOPE_INVALID",
						"Legacy single-camera reviews cannot declare camera scope.", 400);
			}
			return;
		}
		var cameras = Set.copyOf(revisionCameras);
		if (request.decision() == AnnotationReviewDecision.APPROVED) {
			if (!request.reviewedCameraNumbers().equals(revisionCameras) || !request.returnCameraNumbers().isEmpty()) {
				throw new AnnotationTaskException("ANNOTATION_REVIEW_CAMERA_SCOPE_INVALID",
						"Approval must cover every camera in the submitted revision and return none.", 400);
			}
		}
		else {
			var reviewed = Set.copyOf(request.reviewedCameraNumbers());
			var returned = Set.copyOf(request.returnCameraNumbers());
			var covered = new HashSet<>(reviewed);
			covered.addAll(returned);
			if (request.returnCameraNumbers().isEmpty() || !cameras.containsAll(reviewed)
					|| !cameras.containsAll(returned) || !covered.equals(cameras)
					|| reviewed.stream().anyMatch(returned::contains)) {
				throw new AnnotationTaskException("ANNOTATION_REVIEW_CAMERA_SCOPE_INVALID",
						"A rejected revision needs non-overlapping reviewed and returned cameras from the revision.",
						400);
			}

		}
	}

	private static List<Integer> annotationSchemaCameras(AnnotationRevision revision) {
		if ("annotation-document.v1".equals(revision.getAnnotationSchemaVersion())) {
			return revision.getPrimaryCameraNumber() == null ? List.of() : List.of(revision.getPrimaryCameraNumber());
		}
		if (!(revision.getAnnotationDocument().get("camera_views") instanceof List<?> views)) {
			throw new AnnotationTaskException("ANNOTATION_REVISION_DOCUMENT_INVALID",
					"The submitted camera snapshot is invalid.", 409);
		}
		return views.stream()
			.map(value -> value instanceof Map<?, ?> view && view.get("camera_number") instanceof Number number
					? number.intValue() : -1)
			.sorted()
			.toList();
	}

	private void validateSegmentBounds(UUID projectId, UUID clipAssetId, List<AnnotationSegmentCommand> segments) {
		if (clipAssetId == null) {
			throw new AnnotationTaskException("ANNOTATION_MEDIA_SOURCE_MISSING",
					"The camera clip asset is missing from the submitted revision.", 409);
		}
		var durationMicroseconds = this.mediaDurationQueryService.findVerifiedDurationMicroseconds(projectId,
				clipAssetId);
		if (durationMicroseconds.isEmpty()) {
			throw new AnnotationTaskException("ANNOTATION_MEDIA_DURATION_UNVERIFIED",
					"The primary media duration must be verified before submitting or approving annotations.", 409);
		}
		var maximumEndMilliseconds = durationMicroseconds.getAsLong() / 1_000;
		for (var segment : segments) {
			if (segment.endMs() > maximumEndMilliseconds) {
				throw new AnnotationTaskException("ANNOTATION_SEGMENT_EXCEEDS_MEDIA_DURATION",
						"An annotation segment exceeds the verified media duration.", 400);
			}
		}
	}

	private void validateRevisionBounds(UUID projectId, AnnotationRevision revision) {
		if ("annotation-document.v1".equals(revision.getAnnotationSchemaVersion())) {
			validateSegmentBounds(projectId, revision.getPrimaryClipAssetId(), toRevisionSegments(revision));
			return;
		}
		if (!(revision.getAnnotationDocument().get("camera_views") instanceof List<?> views)) {
			throw new AnnotationTaskException("ANNOTATION_REVISION_DOCUMENT_INVALID",
					"The submitted camera snapshot is invalid.", 409);
		}
		for (var value : views) {
			if (!(value instanceof Map<?, ?> view) || !Boolean.TRUE.equals(view.get("included"))) {
				continue;
			}
			var clipAssetId = uuidValue(view.get("clip_asset_id"));
			var segments = view.get("segments") instanceof List<?> rows
					? rows.stream().map(CaptureGroupAnnotationWorkflow::toRevisionSegment).toList()
					: List.<AnnotationSegmentCommand>of();
			if (segments.isEmpty()) {
				throw new AnnotationTaskException("ANNOTATION_CAMERA_VIEW_SEGMENTS_REQUIRED",
						"Every included camera must contain at least one action segment before approval.", 409);
			}
			if (segments.stream().anyMatch(segment -> segment.startMs() < 0 || segment.endMs() <= segment.startMs())) {
				throw new AnnotationTaskException("ANNOTATION_REVISION_DOCUMENT_INVALID",
						"A submitted camera segment has an invalid local time range.", 409);
			}
			validateSegmentBounds(projectId, clipAssetId, segments);
		}
	}

	private static UUID uuidValue(Object value) {
		try {
			return UUID.fromString(Objects.toString(value, ""));
		}
		catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private static List<AnnotationSegmentCommand> toRevisionSegments(AnnotationRevision revision) {
		var value = revision.getAnnotationDocument().get("segments");
		if (!(value instanceof List<?> storedSegments)) {
			return List.of();
		}
		return storedSegments.stream().map(CaptureGroupAnnotationWorkflow::toRevisionSegment).toList();
	}

	private static AnnotationSegmentCommand toRevisionSegment(Object value) {
		if (!(value instanceof Map<?, ?> segment) || !(segment.get("start_ms") instanceof Number start)
				|| !(segment.get("end_ms") instanceof Number end) || !(segment.get("label") instanceof String label)) {
			return new AnnotationSegmentCommand(0, 0, "");
		}
		return new AnnotationSegmentCommand(start.longValue(), end.longValue(), label);
	}

	/** 冻结快照是否仍与当前接入事实一致；机位可标注判定由探针事实推导，与任何人工质量记录无关。 */
	static boolean viewSetStillCurrent(Map<String, Object> confirmedViewSet, CaptureGroupFacts facts) {
		if (confirmedViewSet == null
				|| !Objects.equals(confirmedViewSet.get("schema_version"), CaptureGroupInputFreeze.SCHEMA_VERSION)
				|| !Objects.equals(confirmedViewSet.get("capture_group_id"), facts.captureGroupId().toString())
				|| !numberEquals(confirmedViewSet.get("capture_group_version"), facts.captureGroupVersion())
				|| !Objects.equals(confirmedViewSet.get("camera_set_snapshot_id"),
						facts.cameraSetSnapshotId().toString())
				|| !Objects.equals(confirmedViewSet.get("receipt_state"), facts.receiptState().name())) {
			return false;
		}
		if (!(confirmedViewSet.get("views") instanceof List<?> storedViews)
				|| storedViews.size() != facts.views().size()) {
			return false;
		}
		for (var index = 0; index < facts.views().size(); index += 1) {
			var view = facts.views().get(index);
			if (!(storedViews.get(index) instanceof Map<?, ?> storedView)
					|| !numberEquals(storedView.get("camera_number"), view.cameraNumber())
					|| !sameNullableString(storedView.get("camera_stream_id"), view.cameraStreamId())
					|| !sameNullableString(storedView.get("clip_asset_id"), view.clipAssetId())
					|| !sameNullableString(storedView.get("camera_channel_id"), view.cameraChannelId())
					|| !Objects.equals(storedView.get("present"), view.present())
					|| !Objects.equals(storedView.get("media_status"), view.mediaStatus())
					|| !Objects.equals(storedView.get("technical_quality_status"), view.technicalQualityStatus())
					|| !numberEquals(storedView.get("stream_version"), view.streamVersion())
					|| !numberEquals(storedView.get("clip_asset_version"), view.clipAssetVersion())
					|| !Objects.equals(storedView.get("asset_available"), view.assetAvailable())
					|| !Objects.equals(storedView.get("quality_review_decision"), disposition(view))) {
				return false;
			}
		}
		return true;
	}

	private static String disposition(CaptureGroupView view) {
		return CaptureGroupInputFreeze.annotatable(view) ? CaptureGroupInputFreeze.ANNOTATABLE
				: CaptureGroupInputFreeze.NOT_ANNOTATABLE;
	}

	private static Map<String, Object> groupViewDetails(CaptureGroupView view) {
		var details = new LinkedHashMap<String, Object>();
		details.put("camera_number", view.cameraNumber());
		if (view.cameraStreamId() != null) {
			details.put("camera_stream_id", view.cameraStreamId().toString());
		}
		if (view.clipAssetId() != null) {
			details.put("clip_asset_id", view.clipAssetId().toString());
		}
		details.put("present", view.present());
		details.put("media_status", view.mediaStatus());
		details.put("technical_quality_status", view.technicalQualityStatus());
		details.put("asset_available", view.assetAvailable());
		details.put("quality_decision", disposition(view));
		details.put("quality_review_version", 0);
		details.put("declared_sync_status", view.declaredSyncStatus());
		details.put("stream_version", view.streamVersion());
		details.put("clip_asset_version", view.clipAssetVersion());
		return details;
	}

	private static String groupViewRejectionReason(CaptureGroupView view) {
		return view.present() ? "VIEW_NOT_REVIEWABLE" : "MISSING_VIEW";
	}

	private static Map<String, Object> candidateEvidenceDetails(CandidateEvidence evidence) {
		var details = new LinkedHashMap<String, Object>();
		details.put("clip_asset_id", evidence.clipAssetId().toString());
		details.put("clip_asset_sha256", evidence.clipAssetSha256());
		details.put("provenance_valid", evidence.provenanceValid());
		details.put("execution_status", evidence.executionStatus());
		if (evidence.provenanceFailureReason() != null) {
			details.put("provenance_failure_reason", evidence.provenanceFailureReason());
		}
		if (evidence.aiJobId() != null) {
			details.put("ai_job_id", evidence.aiJobId().toString());
		}
		if (evidence.inputSha256() != null) {
			details.put("input_sha256", evidence.inputSha256());
		}
		if (evidence.algorithmKey() != null) {
			details.put("algorithm_key", evidence.algorithmKey());
		}
		if (evidence.algorithmVersion() != null) {
			details.put("algorithm_version", evidence.algorithmVersion());
		}
		if (evidence.modelSha256() != null) {
			details.put("model_sha256", evidence.modelSha256());
		}
		if (evidence.profileSha256() != null) {
			details.put("profile_sha256", evidence.profileSha256());
		}
		if (evidence.aiJobAttemptId() != null) {
			details.put("ai_job_attempt_id", evidence.aiJobAttemptId().toString());
		}
		if (evidence.attempt() != null) {
			details.put("attempt", evidence.attempt());
		}
		if (evidence.messageId() != null) {
			details.put("message_id", evidence.messageId().toString());
		}
		if (evidence.resultSha256() != null) {
			details.put("result_sha256", evidence.resultSha256());
		}
		if (evidence.handResult() != null) {
			details.put("hand_result", evidence.handResult());
		}
		details.put("candidate_count", evidence.candidateCount());
		if (evidence.firstTimestampMs() != null) {
			details.put("first_timestamp_ms", evidence.firstTimestampMs());
		}
		if (evidence.lastTimestampMs() != null) {
			details.put("last_timestamp_ms", evidence.lastTimestampMs());
		}
		if (evidence.candidateSetDigest() != null) {
			details.put("candidate_set_digest", evidence.candidateSetDigest());
		}
		if (evidence.runSummary() != null) {
			details.put("run_summary", runSummaryDetails(evidence.runSummary()));
		}
		return Collections.unmodifiableMap(new LinkedHashMap<>(details));
	}

	private static Map<String, Object> runSummaryDetails(RunSummary summary) {
		var details = new LinkedHashMap<String, Object>();
		details.put("analysis_complete", summary.analysisComplete());
		details.put("hand_result", summary.handResult());
		details.put("total_video_frames", summary.totalVideoFrames());
		details.put("inference_frame_count", summary.inferenceFrameCount());
		details.put("frames_with_hand", summary.framesWithHand());
		details.put("frames_without_hand", summary.framesWithoutHand());
		details.put("left_hand_detection_count", summary.leftHandDetectionCount());
		details.put("right_hand_detection_count", summary.rightHandDetectionCount());
		details.put("invalid_landmark_count", summary.invalidLandmarkCount());
		details.put("processing_duration_ms", summary.processingDurationMs());
		details.put("started_at", summary.startedAt().toString());
		details.put("completed_at", summary.completedAt().toString());
		details.put("failure_reason", summary.failureReason());
		details.put("retryable", summary.retryable());
		return Collections.unmodifiableMap(new LinkedHashMap<>(details));
	}

	private static boolean numberEquals(Object value, long expected) {
		return value instanceof Number number && number.longValue() == expected;
	}

	private static boolean sameNullableString(Object value, UUID expected) {
		return expected == null ? value == null : Objects.equals(value, expected.toString());
	}

	private static AnnotationTaskException groupNotAnnotatable() {
		return new AnnotationTaskException("ANNOTATION_TASK_GROUP_QUALITY_NOT_QUALIFIED",
				"The CaptureGroup has no annotatable camera view with finalized receipt.", 409);
	}

	private static AnnotationTaskException primaryViewInputChanged() {
		return new AnnotationTaskException("ANNOTATION_PRIMARY_VIEW_INPUT_CHANGED",
				"The frozen CaptureGroup input is stale; re-prepare the annotation task before continuing.", 409);
	}

	private static AnnotationTaskException taskNotFound() {
		return new AnnotationTaskException("ANNOTATION_TASK_NOT_FOUND",
				"The annotation task was not found in the current project.", 404);
	}

}
