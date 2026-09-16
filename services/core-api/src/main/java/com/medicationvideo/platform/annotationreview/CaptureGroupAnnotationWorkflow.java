package com.medicationvideo.platform.annotationreview;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService;
import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService.CandidateEvidence;
import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService.RunSummary;
import com.medicationvideo.platform.assetcapture.ClipAssetReviewQueryService;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService.CaptureGroupFacts;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService.CaptureGroupView;
import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;
import com.medicationvideo.platform.iamproject.ProjectAccessService;
import com.medicationvideo.platform.mediaprivacy.MediaDurationQueryService;
import com.medicationvideo.platform.qualitycleaning.CaptureGroupQualityQueryService;
import com.medicationvideo.platform.qualitycleaning.QualityReviewQueryService;

/** Coordinates the CaptureGroup-owned task, revision and event-cleaning path. */
@Service
class CaptureGroupAnnotationWorkflow {

	private final ProjectAccessService projectAccessService;

	private final ClipAssetReviewQueryService assetQueryService;

	private final QualityReviewQueryService qualityReviewQueryService;

	private final CaptureGroupViewQueryService captureGroupViewQueryService;

	private final CaptureGroupQualityQueryService captureGroupQualityQueryService;

	private final MediaDurationQueryService mediaDurationQueryService;

	private final CandidateEvidenceQueryService candidateEvidenceQueryService;

	private final AnnotationLabelSchemaService labelSchemaService;

	private final AnnotationTaskRepository taskRepository;

	private final AnnotationRevisionRepository revisionRepository;

	private final AnnotationReviewRepository reviewRepository;

	private final AuditEventRecorder auditRecorder;

	CaptureGroupAnnotationWorkflow(ProjectAccessService projectAccessService,
			ClipAssetReviewQueryService assetQueryService, QualityReviewQueryService qualityReviewQueryService,
			CaptureGroupViewQueryService captureGroupViewQueryService,
			CaptureGroupQualityQueryService captureGroupQualityQueryService,
			MediaDurationQueryService mediaDurationQueryService,
			CandidateEvidenceQueryService candidateEvidenceQueryService,
			AnnotationLabelSchemaService labelSchemaService, AnnotationTaskRepository taskRepository,
			AnnotationRevisionRepository revisionRepository, AnnotationReviewRepository reviewRepository,
			AuditEventRecorder auditRecorder) {
		this.projectAccessService = projectAccessService;
		this.assetQueryService = assetQueryService;
		this.qualityReviewQueryService = qualityReviewQueryService;
		this.captureGroupViewQueryService = captureGroupViewQueryService;
		this.captureGroupQualityQueryService = captureGroupQualityQueryService;
		this.mediaDurationQueryService = mediaDurationQueryService;
		this.candidateEvidenceQueryService = candidateEvidenceQueryService;
		this.labelSchemaService = labelSchemaService;
		this.taskRepository = taskRepository;
		this.revisionRepository = revisionRepository;
		this.reviewRepository = reviewRepository;
		this.auditRecorder = auditRecorder;
	}

	@Transactional
	AnnotationTaskResponse assignForHumanLabel(UUID projectId, UUID captureGroupId, String actorId,
			String assigneeActorId, String idempotencyKey, String requestId, String traceId) {
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		this.projectAccessService.lockAndRequireMember(projectId, assigneeActorId);
		var current = this.captureGroupQualityQueryService.lockAndGetCurrentDecision(projectId, captureGroupId);
		if (!current.isAdmitting()) {
			throw groupQualityNotAdmitting();
		}
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var existing = this.taskRepository.findForUpdateByProjectIdAndCaptureGroupId(projectId, captureGroupId);
		if (existing.isPresent()) {
			var task = existing.get();
			if (!task.getAssigneeActorId().equals(assigneeActorId)) {
				throw new AnnotationTaskException("ANNOTATION_TASK_ALREADY_ASSIGNED",
						"The CaptureGroup already has an annotation task assigned to another member.", 409);
			}
			var previousStatus = task.getStatus();
			var previousInputVersion = task.getGroupInputVersion();
			var previousGroupQualityRevision = task.getGroupQualityRevision();
			var inputChanged = task.refreshGroupQualitySnapshot(current.captureGroupQualityReviewId(),
					current.revision(), current.confirmedViewSet(), current.inputVersion(), now);
			this.taskRepository.saveAndFlush(task);
			if (inputChanged && previousStatus != AnnotationTaskStatus.ASSIGNED) {
				this.auditRecorder.record(new AuditEventCommand(now, AuditActorType.USER, actorId, projectId,
						"CAPTURE_GROUP_ANNOTATION_TASK_REOPENED_FOR_INPUT_CHANGE", "AnnotationTask",
						task.getAnnotationTaskId().toString(), Long.toString(task.getVersion()), AuditOutcome.SUCCEEDED,
						"GROUP_INPUT_VERSION_CHANGED", requestId, traceId, "PROJECT_MEMBERSHIP_ADMIN",
						Map.of("capture_group_id", captureGroupId.toString(), "previous_status", previousStatus.name(),
								"new_status", task.getStatus().name(), "previous_input_version",
								String.valueOf(previousInputVersion), "input_version", current.inputVersion(),
								"previous_group_quality_revision", String.valueOf(previousGroupQualityRevision),
								"group_quality_revision", Long.toString(current.revision()), "assignee_actor_id",
								task.getAssigneeActorId(), "created_by", task.getCreatedBy(),
								"revision_chain_preserved", "true")));
			}
			return AnnotationTaskResponse.from(task, actorId, true, null, null, null, true);
		}

		var task = this.taskRepository
			.saveAndFlush(AnnotationTask.createForCaptureGroup(projectId, captureGroupId, assigneeActorId, actorId,
					idempotencyKey, this.labelSchemaService.currentBinding(), current.captureGroupQualityReviewId(),
					current.revision(), current.confirmedViewSet(), current.inputVersion(), now));
		this.auditRecorder.record(new AuditEventCommand(now, AuditActorType.USER, actorId, projectId,
				"CAPTURE_GROUP_ANNOTATION_TASK_ASSIGNED", "AnnotationTask", task.getAnnotationTaskId().toString(),
				Long.toString(task.getVersion()), AuditOutcome.SUCCEEDED, "EXPLICIT_HUMAN_LABEL_ASSIGNMENT", requestId,
				traceId, "PROJECT_MEMBERSHIP_ADMIN",
				Map.of("capture_group_id", captureGroupId.toString(), "group_quality_review_id",
						current.captureGroupQualityReviewId().toString(), "group_quality_revision",
						Long.toString(current.revision()), "assignee_actor_id", task.getAssigneeActorId(), "created_by",
						task.getCreatedBy(), "input_version", current.inputVersion(), "assignment_mode",
						"EXPLICIT_HUMAN_LABEL", "label_schema_id", task.getLabelSchemaId().toString(),
						"label_schema_digest", task.getLabelSchemaDigest())));
		return AnnotationTaskResponse.from(task, actorId, true, null, null, null, true);
	}

	AnnotationRevisionResponse submitRevision(UUID projectId, AnnotationTask task, String actorId,
			String idempotencyKey, String requestId, String traceId, AnnotationRevisionSubmitCommand request,
			Map<String, Object> document, String documentSha256) {
		var groupContext = requireGroupSubmissionContext(projectId, task);
		validateSegmentBounds(projectId, groupContext.primaryClipAssetId(), request.segments());
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		task.submit(now);
		var revisionNumber = this.revisionRepository
			.findTopByAnnotationTaskIdOrderByRevisionNumberDesc(task.getAnnotationTaskId())
			.map(revision -> revision.getRevisionNumber() + 1)
			.orElse(1);
		var revision = AnnotationRevision.submitForCaptureGroup(task, revisionNumber, document, documentSha256, actorId,
				idempotencyKey, now, groupContext.confirmedViewSet(), groupContext.inputVersion());
		this.revisionRepository.saveAndFlush(revision);
		this.taskRepository.saveAndFlush(task);
		var auditDetails = new LinkedHashMap<String, String>();
		auditDetails.put("annotation_task_id", task.getAnnotationTaskId().toString());
		auditDetails.put("clip_asset_id", groupContext.primaryClipAssetId().toString());
		auditDetails.put("label_schema_id", revision.getLabelSchemaId().toString());
		auditDetails.put("label_schema_digest", revision.getLabelSchemaDigest());
		auditDetails.put("document_sha256", documentSha256);
		auditDetails.put("segment_count", Integer.toString(request.segments().size()));
		auditDetails.put("capture_group_id", task.getCaptureGroupId().toString());
		auditDetails.put("primary_clip_asset_id", task.getPrimaryClipAssetId().toString());
		auditDetails.put("primary_camera_number", task.getPrimaryCameraNumber().toString());
		auditDetails.put("group_input_version", groupContext.inputVersion());
		this.auditRecorder.record(new AuditEventCommand(now, AuditActorType.USER, actorId, projectId,
				"ANNOTATION_REVISION_SUBMITTED", "AnnotationRevision", revision.getAnnotationRevisionId().toString(),
				Integer.toString(revision.getRevisionNumber()), AuditOutcome.SUCCEEDED, "MANUAL_ANNOTATION_SUBMITTED",
				requestId, traceId, "ANNOTATION_TASK_ASSIGNEE", auditDetails));
		return AnnotationRevisionResponse.from(revision);
	}

	@Transactional
	AnnotationTaskResponse selectPrimaryView(UUID projectId, UUID annotationTaskId, String actorId, UUID clipAssetId,
			String requestId, String traceId) {
		this.projectAccessService.lockAndRequireMember(projectId, actorId);
		var task = this.taskRepository.findForUpdate(projectId, annotationTaskId)
			.orElseThrow(CaptureGroupAnnotationWorkflow::taskNotFound);
		if (!task.getAssigneeActorId().equals(actorId)) {
			throw new AnnotationTaskException("ANNOTATION_TASK_NOT_ASSIGNEE",
					"Only the assigned project member can select the primary view.", 403);
		}
		var existingRevision = this.revisionRepository
			.findTopByAnnotationTaskIdOrderByRevisionNumberDesc(task.getAnnotationTaskId());
		if (existingRevision.isPresent() && !Objects.equals(task.getPrimaryClipAssetId(), clipAssetId)
				&& (task.getStatus() != AnnotationTaskStatus.ASSIGNED
						|| task.isRevisionForCurrentGroupInput(existingRevision.get()))) {
			throw new AnnotationTaskException("ANNOTATION_PRIMARY_VIEW_FROZEN",
					"The primary view is frozen for the current input after a formal annotation revision.", 409);
		}
		if (Objects.equals(task.getPrimaryClipAssetId(), clipAssetId)) {
			return AnnotationTaskResponse.from(task, actorId, false,
					existingRevision.filter(task::isRevisionForCurrentGroupInput).orElse(null), null, null);
		}

		var groupQuality = this.captureGroupQualityQueryService.lockAndGetCurrentDecision(projectId,
				task.getCaptureGroupId());
		if (!groupQuality.isAdmitting()) {
			throw groupQualityNotAdmitting();
		}
		var facts = this.captureGroupViewQueryService.lockAndGet(projectId, task.getCaptureGroupId());
		var selectedView = facts.views()
			.stream()
			.filter(view -> clipAssetId.equals(view.clipAssetId()))
			.findFirst()
			.orElseThrow(CaptureGroupAnnotationWorkflow::primaryViewNotEligible);
		var quality = this.qualityReviewQueryService.lockAndGetCurrentDecision(projectId, clipAssetId);
		var assetFacts = this.assetQueryService.lockAndGetReviewFacts(projectId, clipAssetId);
		if (!selectedView.present() || !selectedView.assetAvailable() || !assetFacts.reviewable()
				|| !quality.isQualified()) {
			throw primaryViewNotEligible();
		}
		var decisions = this.qualityReviewQueryService.findCurrentDecisions(projectId,
				facts.views().stream().map(CaptureGroupView::clipAssetId).filter(Objects::nonNull).toList());
		if (!viewSetStillCurrent(groupQuality.confirmedViewSet(), facts, decisions)) {
			throw primaryViewInputChanged();
		}
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		task.selectPrimaryView(clipAssetId, selectedView.cameraNumber(), now);
		this.taskRepository.saveAndFlush(task);
		this.auditRecorder.record(new AuditEventCommand(now, AuditActorType.USER, actorId, projectId,
				"CAPTURE_GROUP_PRIMARY_VIEW_SELECTED", "AnnotationTask", task.getAnnotationTaskId().toString(),
				Long.toString(task.getVersion()), AuditOutcome.SUCCEEDED, "PRIMARY_VIEW_TIMEBASE_SELECTED", requestId,
				traceId, "ANNOTATION_TASK_ASSIGNEE",
				Map.of("capture_group_id", task.getCaptureGroupId().toString(), "primary_clip_asset_id",
						clipAssetId.toString(), "primary_camera_number", Integer.toString(selectedView.cameraNumber()),
						"group_quality_revision", Long.toString(groupQuality.revision()), "input_version",
						groupQuality.inputVersion())));
		return AnnotationTaskResponse.from(task, actorId, false, null, null, null);
	}

	AnnotationReviewResponse reviewRevision(UUID projectId, AnnotationRevision revision, String actorId,
			String idempotencyKey, String requestId, String traceId, AnnotationReviewCommand request) {
		var task = this.taskRepository.findForUpdate(projectId, revision.getAnnotationTaskId())
			.orElseThrow(CaptureGroupAnnotationWorkflow::taskNotFound);
		if (task.getStatus() != AnnotationTaskStatus.SUBMITTED) {
			throw new AnnotationTaskException("ANNOTATION_REVISION_NOT_CURRENT",
					"Only the current submitted annotation revision can be reviewed.", 409);
		}
		var groupContext = requireGroupReviewContext(projectId, revision, task);
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
		var groupQuality = this.captureGroupQualityQueryService.lockAndGetCurrentDecision(projectId,
				task.getCaptureGroupId());
		if (!groupQuality.isAdmitting()) {
			throw groupQualityNotAdmitting();
		}
		if (!Objects.equals(task.getGroupInputVersion(), groupQuality.inputVersion())
				|| !Objects.equals(task.getConfirmedViewSet(), groupQuality.confirmedViewSet())) {
			throw primaryViewInputChanged();
		}
		if (!task.hasPrimaryView()) {
			throw new AnnotationTaskException("ANNOTATION_PRIMARY_VIEW_REQUIRED",
					"A primary view must be selected before submitting a group annotation revision.", 409);
		}
		var facts = this.captureGroupViewQueryService.lockAndGet(projectId, task.getCaptureGroupId());
		var primaryView = facts.views()
			.stream()
			.filter(view -> task.getPrimaryClipAssetId().equals(view.clipAssetId()))
			.findFirst()
			.orElseThrow(CaptureGroupAnnotationWorkflow::primaryViewNotEligible);
		var viewAssetFacts = this.assetQueryService.lockAndGetReviewFacts(projectId, task.getPrimaryClipAssetId());
		var primaryQuality = this.qualityReviewQueryService.lockAndGetCurrentDecision(projectId,
				task.getPrimaryClipAssetId());
		var decisions = new HashMap<>(this.qualityReviewQueryService.findCurrentDecisions(projectId,
				facts.views().stream().map(CaptureGroupView::clipAssetId).filter(Objects::nonNull).toList()));
		decisions.put(task.getPrimaryClipAssetId(), primaryQuality);
		if (!primaryView.present() || !primaryView.assetAvailable() || !viewAssetFacts.reviewable()
				|| !primaryQuality.isQualified() || primaryView.cameraNumber() != task.getPrimaryCameraNumber()) {
			throw primaryViewNotEligible();
		}
		if (!viewSetStillCurrent(groupQuality.confirmedViewSet(), facts, decisions)) {
			throw primaryViewInputChanged();
		}
		return new GroupSubmissionContext(task.getPrimaryClipAssetId(), groupQuality.confirmedViewSet(),
				groupQuality.inputVersion());
	}

	private GroupReviewContext requireGroupReviewContext(UUID projectId, AnnotationRevision revision,
			AnnotationTask task) {
		var groupQuality = this.captureGroupQualityQueryService.lockAndGetCurrentDecision(projectId,
				revision.getCaptureGroupId());
		if (!groupQuality.isAdmitting() || groupQuality.captureGroupQualityReviewId() == null
				|| !Objects.equals(groupQuality.confirmedViewSet(), revision.getConfirmedViewSet())
				|| !Objects.equals(groupQuality.inputVersion(), revision.getInputVersion())) {
			throw new AnnotationTaskException("ANNOTATION_REVISION_NO_LONGER_QUALIFIED",
					"The CaptureGroup quality admission or confirmed View Set has changed since submission.", 409);
		}
		var facts = this.captureGroupViewQueryService.lockAndGet(projectId, revision.getCaptureGroupId());
		var decisions = new HashMap<>(this.qualityReviewQueryService.findCurrentDecisions(projectId,
				facts.views().stream().map(CaptureGroupView::clipAssetId).filter(Objects::nonNull).toList()));
		if (!viewSetStillCurrent(revision.getConfirmedViewSet(), facts, decisions)) {
			throw new AnnotationTaskException("ANNOTATION_REVISION_NO_LONGER_QUALIFIED",
					"The CaptureGroup View Set is stale and cannot be reviewed.", 409);
		}
		var qualifiedViews = new ArrayList<Map<String, Object>>();
		var rejectedMissingViews = new ArrayList<Map<String, Object>>();
		for (var view : facts.views()) {
			var quality = view.clipAssetId() == null ? null : decisions.get(view.clipAssetId());
			var qualityDecision = quality == null ? "UNREVIEWED" : quality.decision();
			var qualityVersion = quality == null ? 0 : quality.version();
			var details = groupViewDetails(view, qualityDecision, qualityVersion);
			if (view.present() && view.assetAvailable() && "INGESTED".equals(view.mediaStatus())
					&& "QUALIFIED".equals(qualityDecision)) {
				details.put("is_primary", Objects.equals(view.clipAssetId(), task.getPrimaryClipAssetId()));
				qualifiedViews.add(Collections.unmodifiableMap(new LinkedHashMap<>(details)));
			}
			else {
				details.put("rejection_reason", groupViewRejectionReason(view));
				rejectedMissingViews.add(Collections.unmodifiableMap(new LinkedHashMap<>(details)));
			}
		}
		var primaryQualified = qualifiedViews.stream()
			.anyMatch(view -> Objects.equals(view.get("clip_asset_id"), task.getPrimaryClipAssetId().toString()));
		if (!task.hasPrimaryView() || !primaryQualified) {
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
				task.getPrimaryClipAssetId(), task.getPrimaryCameraNumber(), revision.getConfirmedViewSet(),
				revision.getInputVersion(), groupQuality.captureGroupQualityReviewId(), groupQuality.result(),
				groupQuality.revision(), qualifiedViews, rejectedMissingViews, candidateEvidence);
	}

	private void validateSegmentBounds(UUID projectId, UUID clipAssetId, List<AnnotationSegmentCommand> segments) {
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
		validateSegmentBounds(projectId, revision.getPrimaryClipAssetId(), toRevisionSegments(revision));
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

	static boolean viewSetStillCurrent(Map<String, Object> confirmedViewSet, CaptureGroupFacts facts,
			Map<UUID, QualityReviewQueryService.CurrentDecision> decisions) {
		if (confirmedViewSet == null
				|| !Objects.equals(confirmedViewSet.get("schema_version"), "capture-group-view-set.v1")
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
					|| !Objects.equals(storedView.get("asset_available"), view.assetAvailable())) {
				return false;
			}
			var quality = view.clipAssetId() == null ? null : decisions.get(view.clipAssetId());
			var decision = quality == null ? "UNREVIEWED" : quality.decision();
			var version = quality == null ? 0 : quality.version();
			if (!Objects.equals(storedView.get("quality_review_decision"), decision)
					|| !numberEquals(storedView.get("quality_review_version"), version)) {
				return false;
			}
		}
		return true;
	}

	private static Map<String, Object> groupViewDetails(CaptureGroupView view, String qualityDecision,
			long qualityVersion) {
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
		details.put("quality_decision", qualityDecision);
		details.put("quality_review_version", qualityVersion);
		details.put("declared_sync_status", view.declaredSyncStatus());
		details.put("stream_version", view.streamVersion());
		details.put("clip_asset_version", view.clipAssetVersion());
		return details;
	}

	private static String groupViewRejectionReason(CaptureGroupView view) {
		if (!view.present()) {
			return "MISSING_VIEW";
		}
		if (!view.assetAvailable() || !"INGESTED".equals(view.mediaStatus())) {
			return "VIEW_NOT_REVIEWABLE";
		}
		return "VIEW_QUALITY_NOT_QUALIFIED";
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

	private static AnnotationTaskException groupQualityNotAdmitting() {
		return new AnnotationTaskException("ANNOTATION_TASK_GROUP_QUALITY_NOT_QUALIFIED",
				"The CaptureGroup is not currently qualified for annotation submission.", 409);
	}

	private static AnnotationTaskException primaryViewNotEligible() {
		return new AnnotationTaskException("ANNOTATION_PRIMARY_VIEW_NOT_ELIGIBLE",
				"The primary view must be a present, reviewable and qualified participating view.", 409);
	}

	private static AnnotationTaskException primaryViewInputChanged() {
		return new AnnotationTaskException("ANNOTATION_PRIMARY_VIEW_INPUT_CHANGED",
				"The confirmed CaptureGroup View Set is stale; submit a new group-quality decision before annotating.",
				409);
	}

	private static AnnotationTaskException taskNotFound() {
		return new AnnotationTaskException("ANNOTATION_TASK_NOT_FOUND",
				"The annotation task was not found in the current project.", 404);
	}

	private static AnnotationTaskException revisionNotFound() {
		return new AnnotationTaskException("ANNOTATION_REVISION_NOT_FOUND",
				"The annotation revision was not found in the current project.", 404);
	}

}
