package com.medicationvideo.platform.annotationreview;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService;
import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService.CandidateEvidence;
import com.medicationvideo.platform.assetcapture.ClipAssetReviewQueryService;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService.CaptureGroupFacts;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService.CaptureGroupView;
import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;
import com.medicationvideo.platform.iamproject.ProjectAccessService;
import com.medicationvideo.platform.iamproject.ProjectRole;
import com.medicationvideo.platform.mediaprivacy.MediaDurationQueryService;
import com.medicationvideo.platform.qualitycleaning.PostCleaningQueryService;
import com.medicationvideo.platform.qualitycleaning.PostCleaningResultSummary;
import com.medicationvideo.platform.qualitycleaning.PostCleaningService;
import com.medicationvideo.platform.qualitycleaning.CaptureGroupQualityQueryService;
import com.medicationvideo.platform.qualitycleaning.CaptureGroupCleaningQueryService;
import com.medicationvideo.platform.qualitycleaning.CaptureGroupCleaningService;
import com.medicationvideo.platform.qualitycleaning.CaptureGroupCleaningService.SupportIntervalMapping;
import com.medicationvideo.platform.qualitycleaning.CaptureGroupCleaningService.ViewInput;
import com.medicationvideo.platform.qualitycleaning.CaptureGroupCleaningSummary;
import com.medicationvideo.platform.qualitycleaning.QualityReviewQueryService;

@Service
class AnnotationTaskService {

	private final ProjectAccessService projectAccessService;

	private final ClipAssetReviewQueryService assetQueryService;

	private final QualityReviewQueryService qualityReviewQueryService;

	private final CaptureGroupViewQueryService captureGroupViewQueryService;

	private final CaptureGroupQualityQueryService captureGroupQualityQueryService;

	private final MediaDurationQueryService mediaDurationQueryService;

	private final PostCleaningService postCleaningService;

	private final PostCleaningQueryService postCleaningQueryService;

	private final CaptureGroupCleaningService captureGroupCleaningService;

	private final CaptureGroupCleaningQueryService captureGroupCleaningQueryService;

	private final CandidateEvidenceQueryService candidateEvidenceQueryService;

	private final AnnotationLabelSchemaService labelSchemaService;

	private final AnnotationTaskRepository repository;

	private final AnnotationRevisionRepository revisionRepository;

	private final AnnotationReviewRepository reviewRepository;

	private final AuditEventRecorder auditRecorder;

	AnnotationTaskService(ProjectAccessService projectAccessService, ClipAssetReviewQueryService assetQueryService,
			QualityReviewQueryService qualityReviewQueryService,
			CaptureGroupViewQueryService captureGroupViewQueryService,
			CaptureGroupQualityQueryService captureGroupQualityQueryService,
			MediaDurationQueryService mediaDurationQueryService, PostCleaningService postCleaningService,
			PostCleaningQueryService postCleaningQueryService, CaptureGroupCleaningService captureGroupCleaningService,
			CaptureGroupCleaningQueryService captureGroupCleaningQueryService,
			CandidateEvidenceQueryService candidateEvidenceQueryService,
			AnnotationLabelSchemaService labelSchemaService, AnnotationTaskRepository repository,
			AnnotationRevisionRepository revisionRepository, AnnotationReviewRepository reviewRepository,
			AuditEventRecorder auditRecorder) {
		this.projectAccessService = projectAccessService;
		this.assetQueryService = assetQueryService;
		this.qualityReviewQueryService = qualityReviewQueryService;
		this.captureGroupViewQueryService = captureGroupViewQueryService;
		this.captureGroupQualityQueryService = captureGroupQualityQueryService;
		this.mediaDurationQueryService = mediaDurationQueryService;
		this.postCleaningService = postCleaningService;
		this.postCleaningQueryService = postCleaningQueryService;
		this.captureGroupCleaningService = captureGroupCleaningService;
		this.captureGroupCleaningQueryService = captureGroupCleaningQueryService;
		this.candidateEvidenceQueryService = candidateEvidenceQueryService;
		this.labelSchemaService = labelSchemaService;
		this.repository = repository;
		this.revisionRepository = revisionRepository;
		this.reviewRepository = reviewRepository;
		this.auditRecorder = auditRecorder;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	AnnotationTaskListResponse list(UUID projectId, String actorId, boolean platformAdministrator) {
		var role = this.projectAccessService.requireMember(projectId, actorId);
		var projectAdministrator = platformAdministrator && role == ProjectRole.ADMIN;
		List<AnnotationTask> tasks = projectAdministrator
				? this.repository.findAllByProjectIdOrderByCreatedAtDescAnnotationTaskIdDesc(projectId)
				: this.repository
					.findAllByProjectIdAndAssigneeActorIdOrderByCreatedAtDescAnnotationTaskIdDesc(projectId, actorId);
		if (tasks.isEmpty()) {
			var items = List.<AnnotationTaskResponse>of();
			return new AnnotationTaskListResponse(items,
					AnnotationTaskProgressResponse.from(projectAdministrator, items));
		}
		var revisionsByTaskId = this.revisionRepository
			.findAllByAnnotationTaskIdIn(tasks.stream().map(AnnotationTask::getAnnotationTaskId).toList())
			.stream()
			.collect(java.util.stream.Collectors.toMap(AnnotationRevision::getAnnotationTaskId, revision -> revision,
					(left, right) -> left.getRevisionNumber() >= right.getRevisionNumber() ? left : right));
		Map<UUID, AnnotationReview> reviewsByRevisionId = revisionsByTaskId.isEmpty() ? Map.of() : this.reviewRepository
			.findAllByAnnotationRevisionIdIn(
					revisionsByTaskId.values().stream().map(AnnotationRevision::getAnnotationRevisionId).toList())
			.stream()
			.collect(java.util.stream.Collectors.toMap(AnnotationReview::getAnnotationRevisionId, review -> review));
		var postCleaningByRevisionId = revisionsByTaskId.isEmpty() ? Map.<UUID, PostCleaningResultSummary>of()
				: this.postCleaningQueryService.findLatestByAnnotationRevisionIds(
						revisionsByTaskId.values().stream().map(AnnotationRevision::getAnnotationRevisionId).toList());
		var captureGroupCleaningByRevisionId = revisionsByTaskId.isEmpty()
				? Map.<UUID, com.medicationvideo.platform.qualitycleaning.CaptureGroupCleaningSummary>of()
				: this.captureGroupCleaningQueryService.findLatestByAnnotationRevisionIds(revisionsByTaskId.values()
					.stream()
					.filter(revision -> revision.getCaptureGroupId() != null)
					.map(AnnotationRevision::getAnnotationRevisionId)
					.toList());
		var items = tasks.stream().map(task -> {
			var revision = revisionsByTaskId.get(task.getAnnotationTaskId());
			return AnnotationTaskResponse.from(task, actorId, projectAdministrator, revision,
					revision == null ? null : reviewsByRevisionId.get(revision.getAnnotationRevisionId()),
					revision == null ? null : postCleaningByRevisionId.get(revision.getAnnotationRevisionId()),
					revision == null ? null : captureGroupCleaningByRevisionId.get(revision.getAnnotationRevisionId()));
		}).toList();
		return new AnnotationTaskListResponse(items, AnnotationTaskProgressResponse.from(projectAdministrator, items));
	}

	@Transactional
	AnnotationTaskResponse create(UUID projectId, UUID clipAssetId, String actorId, String idempotencyKey,
			String requestId, String traceId, AnnotationTaskCreateRequest request) {
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		var replay = this.repository.findByProjectIdAndCreatedByAndIdempotencyKey(projectId, actorId, idempotencyKey);
		if (replay.isPresent()) {
			if (!replay.get().matchesCreationRequest(clipAssetId, request.assigneeActorId())) {
				throw new AnnotationTaskException("ANNOTATION_TASK_IDEMPOTENCY_KEY_REUSED",
						"The idempotency key was already used for a different annotation task.", 409);
			}
			return AnnotationTaskResponse.from(replay.get(), actorId, true, null, null, null);
		}

		var assetFacts = this.assetQueryService.lockAndGetReviewFacts(projectId, clipAssetId);
		if (!assetFacts.reviewable()) {
			throw new AnnotationTaskException("ANNOTATION_TASK_NOT_READY",
					"The clip asset must complete media ingest before an annotation task can be created.", 409);
		}
		if (!this.qualityReviewQueryService.lockAndGetCurrentDecision(projectId, clipAssetId).isQualified()) {
			throw new AnnotationTaskException("ANNOTATION_TASK_QUALITY_NOT_QUALIFIED",
					"Only a human-qualified clip asset can enter annotation.", 409);
		}
		this.projectAccessService.requireMember(projectId, request.assigneeActorId());
		if (this.repository.existsByProjectIdAndClipAssetId(projectId, clipAssetId)) {
			throw new AnnotationTaskException("ANNOTATION_TASK_ALREADY_EXISTS",
					"The clip asset already has an annotation task in this project.", 409);
		}

		var labelSchema = this.labelSchemaService.currentBinding();
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var task = this.repository.saveAndFlush(AnnotationTask.create(projectId, clipAssetId, request.assigneeActorId(),
				actorId, idempotencyKey, labelSchema, now));
		this.auditRecorder
			.record(new AuditEventCommand(now, AuditActorType.USER, actorId, projectId, "ANNOTATION_TASK_CREATED",
					"AnnotationTask", task.getAnnotationTaskId().toString(), Long.toString(task.getVersion()),
					AuditOutcome.SUCCEEDED, "QUALIFIED_CLIP_ASSET", requestId, traceId, "PROJECT_MEMBERSHIP_ADMIN",
					Map.of("clip_asset_id", clipAssetId.toString(), "assignee_actor_id", task.getAssigneeActorId(),
							"label_schema_id", task.getLabelSchemaId().toString(), "label_schema_digest",
							task.getLabelSchemaDigest())));
		return AnnotationTaskResponse.from(task, actorId, true, null, null, null);
	}

	@Transactional
	AnnotationRevisionResponse submitRevision(UUID projectId, UUID annotationTaskId, String actorId,
			String idempotencyKey, String requestId, String traceId, AnnotationRevisionSubmitCommand request) {
		this.projectAccessService.requireMember(projectId, actorId);
		var task = this.repository.findForUpdate(projectId, annotationTaskId)
			.orElseThrow(AnnotationTaskService::taskNotFound);
		if (!task.getAssigneeActorId().equals(actorId)) {
			throw new AnnotationTaskException("ANNOTATION_TASK_NOT_ASSIGNEE",
					"Only the assigned project member can submit this annotation task.", 403);
		}
		var document = toAnnotationDocument(request);
		var documentSha256 = digest(document);
		var replay = this.revisionRepository
			.findByAnnotationTaskIdAndSubmittedByAndIdempotencyKey(task.getAnnotationTaskId(), actorId, idempotencyKey);
		if (replay.isPresent()) {
			if (!replay.get().matchesSubmission(documentSha256)) {
				throw new AnnotationTaskException("ANNOTATION_REVISION_IDEMPOTENCY_KEY_REUSED",
						"The idempotency key was already used for a different annotation revision.", 409);
			}
			return AnnotationRevisionResponse.from(replay.get());
		}
		this.labelSchemaService.requireAllowedPrimaryStepLabels(task.getLabelSchemaId(), task.getLabelSchemaDigest(),
				request.segments().stream().map(AnnotationSegmentCommand::label).toList());
		validatePrimaryStepOverlap(request.segments());
		var groupContext = task.isCaptureGroupTask() ? requireGroupSubmissionContext(projectId, task) : null;
		var annotationClipAssetId = groupContext == null ? task.getClipAssetId() : groupContext.primaryClipAssetId();
		if (groupContext == null
				&& (!this.assetQueryService.lockAndGetReviewFacts(projectId, annotationClipAssetId).reviewable()
						|| !this.qualityReviewQueryService.lockAndGetCurrentDecision(projectId, annotationClipAssetId)
							.isQualified())) {
			throw new AnnotationTaskException("ANNOTATION_TASK_NO_LONGER_QUALIFIED",
					"The clip asset is no longer eligible for annotation submission.", 409);
		}
		validateSegmentBounds(projectId, annotationClipAssetId, request.segments());
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		task.submit(now);
		var revisionNumber = this.revisionRepository
			.findTopByAnnotationTaskIdOrderByRevisionNumberDesc(task.getAnnotationTaskId())
			.map(revision -> revision.getRevisionNumber() + 1)
			.orElse(1);
		var revision = this.revisionRepository.saveAndFlush(groupContext == null
				? AnnotationRevision.submit(task, revisionNumber, document, documentSha256, actorId, idempotencyKey,
						now)
				: AnnotationRevision.submitForCaptureGroup(task, revisionNumber, document, documentSha256, actorId,
						idempotencyKey, now, groupContext.confirmedViewSet(), groupContext.inputVersion()));
		this.repository.saveAndFlush(task);
		var auditDetails = new java.util.LinkedHashMap<String, String>();
		auditDetails.put("annotation_task_id", task.getAnnotationTaskId().toString());
		auditDetails.put("clip_asset_id", annotationClipAssetId.toString());
		auditDetails.put("label_schema_id", revision.getLabelSchemaId().toString());
		auditDetails.put("label_schema_digest", revision.getLabelSchemaDigest());
		auditDetails.put("document_sha256", documentSha256);
		auditDetails.put("segment_count", Integer.toString(request.segments().size()));
		if (groupContext != null) {
			auditDetails.put("capture_group_id", task.getCaptureGroupId().toString());
			auditDetails.put("primary_clip_asset_id", task.getPrimaryClipAssetId().toString());
			auditDetails.put("primary_camera_number", task.getPrimaryCameraNumber().toString());
			auditDetails.put("group_input_version", groupContext.inputVersion());
		}
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
		var task = this.repository.findForUpdate(projectId, annotationTaskId)
			.orElseThrow(AnnotationTaskService::taskNotFound);
		if (!task.isCaptureGroupTask()) {
			throw new AnnotationTaskException("ANNOTATION_PRIMARY_VIEW_NOT_SUPPORTED",
					"A primary view can only be selected for a CaptureGroup annotation task.", 409);
		}
		if (!task.getAssigneeActorId().equals(actorId)) {
			throw new AnnotationTaskException("ANNOTATION_TASK_NOT_ASSIGNEE",
					"Only the assigned project member can select the primary view.", 403);
		}
		var existingRevision = this.revisionRepository
			.findTopByAnnotationTaskIdOrderByRevisionNumberDesc(task.getAnnotationTaskId());
		if (existingRevision.isPresent() && !Objects.equals(task.getPrimaryClipAssetId(), clipAssetId)) {
			throw new AnnotationTaskException("ANNOTATION_PRIMARY_VIEW_FROZEN",
					"The primary view is frozen after the first formal annotation revision.", 409);
		}
		if (Objects.equals(task.getPrimaryClipAssetId(), clipAssetId)) {
			return AnnotationTaskResponse.from(task, actorId, false, existingRevision.orElse(null), null, null);
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
			.orElseThrow(AnnotationTaskService::primaryViewNotEligible);
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
		this.repository.saveAndFlush(task);
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

	private GroupSubmissionContext requireGroupSubmissionContext(UUID projectId, AnnotationTask task) {
		var groupQuality = this.captureGroupQualityQueryService.lockAndGetCurrentDecision(projectId,
				task.getCaptureGroupId());
		if (!groupQuality.isAdmitting()) {
			throw groupQualityNotAdmitting();
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
			.orElseThrow(AnnotationTaskService::primaryViewNotEligible);
		var viewAssetFacts = this.assetQueryService.lockAndGetReviewFacts(projectId, task.getPrimaryClipAssetId());
		var primaryQuality = this.qualityReviewQueryService.lockAndGetCurrentDecision(projectId,
				task.getPrimaryClipAssetId());
		var decisions = this.qualityReviewQueryService.findCurrentDecisions(projectId,
				facts.views().stream().map(CaptureGroupView::clipAssetId).filter(Objects::nonNull).toList());
		decisions = new java.util.HashMap<>(decisions);
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

	private static boolean viewSetStillCurrent(Map<String, Object> confirmedViewSet, CaptureGroupFacts facts,
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

	@Transactional
	AnnotationReviewResponse reviewRevision(UUID projectId, UUID annotationRevisionId, String actorId,
			boolean selfAdminApprovalAllowed, String idempotencyKey, String requestId, String traceId,
			AnnotationReviewCommand request) {
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		var revision = this.revisionRepository.findForUpdate(projectId, annotationRevisionId)
			.orElseThrow(AnnotationTaskService::revisionNotFound);
		var selfAdminApproval = revision.getSubmittedBy().equals(actorId);
		if (selfAdminApproval && !selfAdminApprovalAllowed) {
			throw new AnnotationTaskException("ANNOTATION_REVIEW_SELF_REVIEW_FORBIDDEN",
					"The submitting member cannot independently review this annotation revision.", 403);
		}
		var existing = this.reviewRepository.findByAnnotationRevisionId(annotationRevisionId);
		if (existing.isPresent()) {
			var review = existing.get();
			if (review.getReviewedBy().equals(actorId) && review.matches(request.decision(), request.reviewNote())) {
				return AnnotationReviewResponse.from(review);
			}
			throw new AnnotationTaskException("ANNOTATION_REVISION_ALREADY_REVIEWED",
					"The annotation revision already has an immutable review decision.", 409);
		}
		var task = this.repository.findForUpdate(projectId, revision.getAnnotationTaskId())
			.orElseThrow(AnnotationTaskService::taskNotFound);
		if (task.getStatus() != AnnotationTaskStatus.SUBMITTED) {
			throw new AnnotationTaskException("ANNOTATION_REVISION_NOT_CURRENT",
					"Only the current submitted annotation revision can be reviewed.", 409);
		}
		var groupContext = revision.getCaptureGroupId() == null ? null
				: requireGroupReviewContext(projectId, revision, task);
		var assetFacts = groupContext == null
				? this.assetQueryService.lockAndGetReviewFacts(projectId, revision.getClipAssetId()) : null;
		var qualityDecision = groupContext == null
				? this.qualityReviewQueryService.lockAndGetCurrentDecision(projectId, revision.getClipAssetId()) : null;
		if (groupContext == null && (assetFacts == null || !assetFacts.reviewable() || qualityDecision == null
				|| !qualityDecision.isQualified())) {
			throw new AnnotationTaskException("ANNOTATION_REVISION_NO_LONGER_QUALIFIED",
					"The clip asset is no longer eligible for annotation review.", 409);
		}
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var review = this.reviewRepository.saveAndFlush(AnnotationReview.record(revision, request.decision(),
				request.reviewNote(), actorId, idempotencyKey, now, groupContext));
		if (review.getDecision() == AnnotationReviewDecision.REJECTED) {
			task.returnForRework(now);
			this.repository.saveAndFlush(task);
		}
		else {
			if (groupContext == null) {
				this.evaluatePostCleaning(projectId, revision, review, task, assetFacts.assetSha256(), qualityDecision,
						actorId, requestId, traceId,
						selfAdminApproval ? "SELF_ADMIN_APPROVAL" : "INDEPENDENT_ANNOTATION_REVIEW");
			}
			else {
				this.evaluateCaptureGroupCleaning(projectId, revision, task, review, Map.of(), actorId, requestId,
						traceId, selfAdminApproval ? "SELF_ADMIN_APPROVAL" : "INDEPENDENT_ANNOTATION_REVIEW");
			}
			task.complete(now);
			this.repository.saveAndFlush(task);
		}
		this.auditRecorder.record(new AuditEventCommand(now, AuditActorType.USER, actorId, projectId,
				"ANNOTATION_REVISION_REVIEWED", "AnnotationReview", review.getAnnotationReviewId().toString(),
				review.getDecision().name(), AuditOutcome.SUCCEEDED,
				selfAdminApproval ? "SELF_ADMIN_APPROVAL" : "INDEPENDENT_ANNOTATION_REVIEW", requestId, traceId,
				"PROJECT_MEMBERSHIP_ADMIN",
				Map.of("annotation_revision_id", revision.getAnnotationRevisionId().toString(), "annotation_task_id",
						revision.getAnnotationTaskId().toString(), "decision", review.getDecision().name(),
						"reopened_for_rework",
						Boolean.toString(review.getDecision() == AnnotationReviewDecision.REJECTED), "task_completed",
						Boolean.toString(review.getDecision() == AnnotationReviewDecision.APPROVED))));
		return AnnotationReviewResponse.from(review);
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
		var decisions = new java.util.HashMap<>(this.qualityReviewQueryService.findCurrentDecisions(projectId,
				facts.views().stream().map(CaptureGroupView::clipAssetId).filter(Objects::nonNull).toList()));
		if (!viewSetStillCurrent(revision.getConfirmedViewSet(), facts, decisions)) {
			throw new AnnotationTaskException("ANNOTATION_REVISION_NO_LONGER_QUALIFIED",
					"The CaptureGroup View Set is stale and cannot be reviewed.", 409);
		}
		var qualifiedViews = new java.util.ArrayList<Map<String, Object>>();
		var rejectedMissingViews = new java.util.ArrayList<Map<String, Object>>();
		for (var view : facts.views()) {
			var quality = view.clipAssetId() == null ? null : decisions.get(view.clipAssetId());
			var qualityDecision = quality == null ? "UNREVIEWED" : quality.decision();
			var qualityVersion = quality == null ? 0 : quality.version();
			var details = groupViewDetails(view, qualityDecision, qualityVersion);
			if (view.present() && view.assetAvailable() && "INGESTED".equals(view.mediaStatus())
					&& "QUALIFIED".equals(qualityDecision)) {
				details.put("is_primary", Objects.equals(view.clipAssetId(), task.getPrimaryClipAssetId()));
				qualifiedViews.add(java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(details)));
			}
			else {
				details.put("rejection_reason", groupViewRejectionReason(view, qualityDecision));
				rejectedMissingViews.add(java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(details)));
			}
		}
		var primaryQualified = qualifiedViews.stream()
			.anyMatch(view -> Objects.equals(view.get("clip_asset_id"), task.getPrimaryClipAssetId().toString()));
		if (!task.hasPrimaryView() || !primaryQualified) {
			throw new AnnotationTaskException("ANNOTATION_PRIMARY_VIEW_NOT_ELIGIBLE",
					"The primary view must remain present, reviewable and qualified for Review.", 409);
		}
		var candidateEvidence = this.candidateEvidenceQueryService
			.findLatestSuccessfulByClipAssets(projectId,
					facts.views().stream().map(CaptureGroupView::clipAssetId).filter(Objects::nonNull).toList())
			.stream()
			.map(AnnotationTaskService::candidateEvidenceDetails)
			.toList();
		return new GroupReviewContext(revision.getCaptureGroupId(), revision.getRevisionNumber(),
				task.getPrimaryClipAssetId(), task.getPrimaryCameraNumber(), revision.getConfirmedViewSet(),
				revision.getInputVersion(), groupQuality.captureGroupQualityReviewId(), groupQuality.result(),
				groupQuality.revision(), qualifiedViews, rejectedMissingViews, candidateEvidence);
	}

	private CaptureGroupCleaningSummary evaluateCaptureGroupCleaning(UUID projectId, AnnotationRevision revision,
			AnnotationTask task, AnnotationReview review, Map<UUID, SupportIntervalMapping> supportMappings,
			String actorId, String requestId, String traceId, String authorizationBasis) {
		var groupQuality = this.captureGroupQualityQueryService.lockAndGetCurrentDecision(projectId,
				revision.getCaptureGroupId());
		var facts = this.captureGroupViewQueryService.lockAndGet(projectId, revision.getCaptureGroupId());
		var decisions = this.qualityReviewQueryService.findCurrentDecisions(projectId,
				facts.views().stream().map(CaptureGroupView::clipAssetId).filter(Objects::nonNull).toList());
		var command = new CaptureGroupCleaningService.EvaluationCommand(projectId, revision.getCaptureGroupId(),
				revision.getAnnotationRevisionId(), review.getAnnotationReviewId(),
				review.getDecision() == AnnotationReviewDecision.APPROVED, review.getReviewedBy(),
				review.getReviewedAt(), groupQuality.captureGroupQualityReviewId(), groupQuality.result(),
				groupQuality.revision(), revision.getPrimaryClipAssetId(), revision.getPrimaryCameraNumber(),
				toGroupCleaningSegments(revision), toGroupCleaningViews(facts, decisions),
				supportMappings == null ? Map.of() : supportMappings,
				review.getCandidateEvidence() == null ? List.of() : review.getCandidateEvidence(), actorId,
				authorizationBasis);
		return this.captureGroupCleaningService.evaluate(command, requestId, traceId);
	}

	private static List<CaptureGroupCleaningService.SegmentSnapshot> toGroupCleaningSegments(
			AnnotationRevision revision) {
		var value = revision.getAnnotationDocument().get("segments");
		if (!(value instanceof List<?> storedSegments)) {
			return List.of();
		}
		return storedSegments.stream().map(AnnotationTaskService::toGroupCleaningSegment).toList();
	}

	private static CaptureGroupCleaningService.SegmentSnapshot toGroupCleaningSegment(Object value) {
		if (!(value instanceof Map<?, ?> segment) || !(segment.get("start_ms") instanceof Number start)
				|| !(segment.get("end_ms") instanceof Number end) || !(segment.get("label") instanceof String label)) {
			return new CaptureGroupCleaningService.SegmentSnapshot(0, 0, "");
		}
		return new CaptureGroupCleaningService.SegmentSnapshot(start.longValue(), end.longValue(), label);
	}

	private static List<ViewInput> toGroupCleaningViews(CaptureGroupFacts facts,
			Map<UUID, QualityReviewQueryService.CurrentDecision> decisions) {
		return facts.views().stream().map(view -> {
			var quality = view.clipAssetId() == null ? null : decisions.get(view.clipAssetId());
			return new ViewInput(view.cameraStreamId(), view.clipAssetId(), view.cameraNumber(), view.present(),
					view.mediaStatus(), view.technicalQualityStatus(), view.streamVersion(), view.clipAssetVersion(),
					view.assetAvailable(), quality == null ? "UNREVIEWED" : quality.decision(),
					quality == null ? 0 : quality.version(), view.declaredSyncStatus());
		}).toList();
	}

	private static Map<String, Object> groupViewDetails(CaptureGroupView view, String qualityDecision,
			long qualityVersion) {
		var details = new java.util.LinkedHashMap<String, Object>();
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

	private static String groupViewRejectionReason(CaptureGroupView view, String qualityDecision) {
		if (!view.present()) {
			return "MISSING_VIEW";
		}
		if (!view.assetAvailable() || !"INGESTED".equals(view.mediaStatus())) {
			return "VIEW_NOT_REVIEWABLE";
		}
		return "VIEW_QUALITY_NOT_QUALIFIED";
	}

	private static Map<String, Object> candidateEvidenceDetails(CandidateEvidence evidence) {
		var details = new java.util.LinkedHashMap<String, Object>();
		details.put("clip_asset_id", evidence.clipAssetId().toString());
		details.put("ai_job_id", evidence.aiJobId().toString());
		details.put("algorithm_key", evidence.algorithmKey());
		details.put("algorithm_version", evidence.algorithmVersion());
		details.put("model_sha256", evidence.modelSha256());
		details.put("profile_sha256", evidence.profileSha256());
		details.put("candidate_count", evidence.candidateCount());
		if (evidence.firstTimestampMs() != null) {
			details.put("first_timestamp_ms", evidence.firstTimestampMs());
		}
		if (evidence.lastTimestampMs() != null) {
			details.put("last_timestamp_ms", evidence.lastTimestampMs());
		}
		return Map.copyOf(details);
	}

	@Transactional
	PostCleaningResultResponse recheckPostCleaning(UUID projectId, UUID annotationRevisionId, String actorId,
			String requestId, String traceId) {
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		var revision = this.revisionRepository.findForUpdate(projectId, annotationRevisionId)
			.orElseThrow(AnnotationTaskService::revisionNotFound);
		var review = this.reviewRepository.findByAnnotationRevisionId(annotationRevisionId)
			.orElseThrow(AnnotationTaskService::postCleaningRecheckNotReady);
		if (review.getDecision() != AnnotationReviewDecision.APPROVED) {
			throw postCleaningRecheckNotReady();
		}
		var assetFacts = this.assetQueryService.lockAndGetReviewFacts(projectId, revision.getClipAssetId());
		if (!assetFacts.reviewable()) {
			throw postCleaningRecheckNotReady();
		}
		var qualityDecision = this.qualityReviewQueryService.lockAndGetCurrentDecision(projectId,
				revision.getClipAssetId());
		var task = this.repository.findForUpdate(projectId, revision.getAnnotationTaskId())
			.orElseThrow(AnnotationTaskService::taskNotFound);
		if (task.getStatus() != AnnotationTaskStatus.COMPLETED) {
			throw postCleaningRecheckNotReady();
		}
		return PostCleaningResultResponse.from(this.evaluatePostCleaning(projectId, revision, review, task,
				assetFacts.assetSha256(), qualityDecision, actorId, requestId, traceId, "PROJECT_MEMBERSHIP_ADMIN"));
	}

	@Transactional
	CaptureGroupCleaningResponse recheckCaptureGroupCleaning(UUID projectId, UUID annotationRevisionId, String actorId,
			String requestId, String traceId, Map<UUID, SupportIntervalMapping> supportMappings) {
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		var revision = this.revisionRepository.findForUpdate(projectId, annotationRevisionId)
			.orElseThrow(AnnotationTaskService::revisionNotFound);
		if (revision.getCaptureGroupId() == null) {
			throw new AnnotationTaskException("ANNOTATION_GROUP_CLEANING_NOT_SUPPORTED",
					"CaptureGroup event cleaning is only available for group AnnotationRevisions.", 409);
		}
		var review = this.reviewRepository.findByAnnotationRevisionId(annotationRevisionId)
			.orElseThrow(AnnotationTaskService::groupCleaningRecheckNotReady);
		if (review.getDecision() != AnnotationReviewDecision.APPROVED) {
			throw groupCleaningRecheckNotReady();
		}
		var task = this.repository.findForUpdate(projectId, revision.getAnnotationTaskId())
			.orElseThrow(AnnotationTaskService::taskNotFound);
		if (task.getStatus() != AnnotationTaskStatus.COMPLETED) {
			throw groupCleaningRecheckNotReady();
		}
		this.requireGroupReviewContext(projectId, revision, task);
		return CaptureGroupCleaningResponse.from(this.evaluateCaptureGroupCleaning(projectId, revision, task, review,
				supportMappings == null ? Map.of() : supportMappings, actorId, requestId, traceId,
				"PROJECT_MEMBERSHIP_ADMIN"));
	}

	private PostCleaningResultSummary evaluatePostCleaning(UUID projectId, AnnotationRevision revision,
			AnnotationReview review, AnnotationTask task, String assetSha256,
			QualityReviewQueryService.CurrentDecision qualityDecision, String actorId, String requestId, String traceId,
			String authorizationBasis) {
		var mediaFact = this.mediaDurationQueryService.findLatestVerifiedDurationFact(projectId,
				revision.getClipAssetId());
		return this.postCleaningService.evaluate(
				new PostCleaningService.EvaluationCommand(projectId, revision.getClipAssetId(), assetSha256,
						qualityDecision.version(), qualityDecision.isQualified(), revision.getAnnotationRevisionId(),
						review.getAnnotationReviewId(), true, revision.getDocumentSha256(),
						digest(revision.getAnnotationDocument()), task.getLabelSchemaId(), task.getLabelSchemaDigest(),
						revision.getLabelSchemaId(), revision.getLabelSchemaDigest(),
						this.labelSchemaService.allowedPrimaryStepLabels(revision.getLabelSchemaId(),
								revision.getLabelSchemaDigest()),
						toPostCleaningSegments(revision), mediaFact.map(fact -> fact.mediaReportId()).orElse(null),
						mediaFact.map(fact -> fact.inputDigest()).orElse(null),
						mediaFact.map(fact -> fact.recipeDigest()).orElse(null),
						mediaFact.map(fact -> fact.durationMicroseconds()).orElse(null), actorId, authorizationBasis),
				requestId, traceId);
	}

	private static List<PostCleaningService.SegmentSnapshot> toPostCleaningSegments(AnnotationRevision revision) {
		var value = revision.getAnnotationDocument().get("segments");
		if (!(value instanceof List<?> storedSegments)) {
			return List.of();
		}
		return storedSegments.stream().map(AnnotationTaskService::toPostCleaningSegment).toList();
	}

	private static PostCleaningService.SegmentSnapshot toPostCleaningSegment(Object value) {
		if (!(value instanceof Map<?, ?> segment) || !(segment.get("start_ms") instanceof Number start)
				|| !(segment.get("end_ms") instanceof Number end) || !(segment.get("label") instanceof String label)) {
			return new PostCleaningService.SegmentSnapshot(0, 0, "");
		}
		return new PostCleaningService.SegmentSnapshot(start.longValue(), end.longValue(), label);
	}

	private Map<String, Object> toAnnotationDocument(AnnotationRevisionSubmitCommand request) {
		var segments = request.segments().stream().map(segment -> {
			if (segment.endMs() <= segment.startMs()) {
				throw new AnnotationTaskException("ANNOTATION_SEGMENT_TIME_RANGE_INVALID",
						"Each annotation segment must end after it starts.", 400);
			}
			return Map.<String, Object>of("start_ms", segment.startMs(), "end_ms", segment.endMs(), "label",
					segment.label().trim());
		}).toList();
		var document = new java.util.LinkedHashMap<String, Object>();
		document.put("schema_version", "annotation-document.v1");
		document.put("segments", segments);
		if (request.note() != null && !request.note().isBlank()) {
			document.put("note", request.note().trim());
		}
		return Map.copyOf(document);
	}

	private void validateSegmentBounds(UUID projectId, UUID clipAssetId, List<AnnotationSegmentCommand> segments) {
		var durationMicroseconds = this.mediaDurationQueryService.findVerifiedDurationMicroseconds(projectId,
				clipAssetId);
		if (durationMicroseconds.isEmpty()) {
			return;
		}
		var maximumEndMilliseconds = durationMicroseconds.getAsLong() / 1_000;
		for (var segment : segments) {
			if (segment.endMs() > maximumEndMilliseconds) {
				throw new AnnotationTaskException("ANNOTATION_SEGMENT_EXCEEDS_MEDIA_DURATION",
						"An annotation segment exceeds the verified media duration.", 400);
			}
		}
	}

	private static void validatePrimaryStepOverlap(List<AnnotationSegmentCommand> segments) {
		var orderedSegments = segments.stream()
			.sorted(java.util.Comparator.comparingLong(AnnotationSegmentCommand::startMs)
				.thenComparingLong(AnnotationSegmentCommand::endMs))
			.toList();
		var maximumPreviousEnd = orderedSegments.getFirst().endMs();
		for (var index = 1; index < orderedSegments.size(); index += 1) {
			var segment = orderedSegments.get(index);
			if (segment.startMs() < maximumPreviousEnd) {
				throw new AnnotationTaskException("ANNOTATION_PRIMARY_STEP_OVERLAP",
						"Primary Step annotation segments must not overlap.", 400);
			}
			maximumPreviousEnd = Math.max(maximumPreviousEnd, segment.endMs());
		}
	}

	private static String digest(Map<String, Object> document) {
		if (!hasOnlyDocumentFields(document) || !(document.get("schema_version") instanceof String schemaVersion)
				|| !(document.get("segments") instanceof List<?> segments)
				|| (document.containsKey("note") && !(document.get("note") instanceof String))) {
			return null;
		}
		try {
			var canonical = new StringBuilder(schemaVersion).append('\n');
			for (var value : segments) {
				if (!(value instanceof Map<?, ?> segment) || segment.size() != 3 || !segment.containsKey("start_ms")
						|| !segment.containsKey("end_ms") || !segment.containsKey("label")
						|| !(segment.get("label") instanceof String label)) {
					return null;
				}
				var startMs = exactLong(segment.get("start_ms"));
				var endMs = exactLong(segment.get("end_ms"));
				if (startMs == null || endMs == null) {
					return null;
				}
				canonical.append(startMs).append(':').append(endMs).append(':');
				appendLengthPrefixed(canonical, label);
				canonical.append('\n');
			}
			canonical.append("note:");
			appendLengthPrefixed(canonical, (String) document.getOrDefault("note", ""));
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

	private static boolean hasOnlyDocumentFields(Map<String, Object> document) {
		if (!document.containsKey("schema_version") || !document.containsKey("segments")) {
			return false;
		}
		return document.size() == 2 || (document.size() == 3 && document.containsKey("note"));
	}

	private static Long exactLong(Object value) {
		if (!(value instanceof Number)) {
			return null;
		}
		try {
			return Long.parseLong(value.toString());
		}
		catch (NumberFormatException exception) {
			return null;
		}
	}

	private static void appendLengthPrefixed(StringBuilder target, String value) {
		target.append(value.length()).append(':').append(value);
	}

	private static AnnotationTaskException taskNotFound() {
		return new AnnotationTaskException("ANNOTATION_TASK_NOT_FOUND",
				"The annotation task was not found in the current project.", 404);
	}

	private static AnnotationTaskException revisionNotFound() {
		return new AnnotationTaskException("ANNOTATION_REVISION_NOT_FOUND",
				"The annotation revision was not found in the current project.", 404);
	}

	private static AnnotationTaskException postCleaningRecheckNotReady() {
		return new AnnotationTaskException("ANNOTATION_POST_CLEANING_RECHECK_NOT_READY",
				"Only an approved revision for a completed, reviewable video can be rechecked.", 409);
	}

	private static AnnotationTaskException groupCleaningRecheckNotReady() {
		return new AnnotationTaskException("ANNOTATION_GROUP_CLEANING_RECHECK_NOT_READY",
				"Only an approved CaptureGroup revision for a completed annotation task can be cleaned.", 409);
	}

}

record AnnotationTaskCreateRequest(String assigneeActorId) {
}

record GroupSubmissionContext(UUID primaryClipAssetId, Map<String, Object> confirmedViewSet, String inputVersion) {
}

record GroupReviewContext(UUID captureGroupId, int revisionNumber, UUID primaryClipAssetId, int primaryCameraNumber,
		Map<String, Object> confirmedViewSet, String inputVersion, UUID groupQualityReviewId, String groupQualityResult,
		long groupQualityRevision, List<Map<String, Object>> qualifiedViews,
		List<Map<String, Object>> rejectedMissingViews, List<Map<String, Object>> candidateEvidence) {

	GroupReviewContext {
		confirmedViewSet = Map.copyOf(confirmedViewSet);
		qualifiedViews = qualifiedViews.stream()
			.map(value -> java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(value)))
			.toList();
		rejectedMissingViews = rejectedMissingViews.stream()
			.map(value -> java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(value)))
			.toList();
		candidateEvidence = candidateEvidence.stream()
			.map(value -> java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(value)))
			.toList();
	}
}

record AnnotationRevisionSubmitCommand(List<AnnotationSegmentCommand> segments, String note) {
}

record AnnotationSegmentCommand(long startMs, long endMs, String label) {
}

record AnnotationReviewCommand(AnnotationReviewDecision decision, String reviewNote) {
}

record AnnotationTaskResponse(UUID annotationTaskId, UUID projectId, UUID clipAssetId, UUID captureGroupId,
		UUID primaryClipAssetId, Integer primaryCameraNumber, UUID captureGroupQualityReviewId,
		Long groupQualityRevision, Map<String, Object> confirmedViewSet, String groupInputVersion, UUID labelSchemaId,
		String labelSchemaDigest, String assigneeActorId, String createdBy, String status, boolean canSubmit,
		boolean canReview, boolean canRecheckPostCleaning, AnnotationRevisionSummary annotationRevision,
		Instant createdAt, Instant updatedAt, long version) {

	static AnnotationTaskResponse from(AnnotationTask task, String actorId, boolean projectAdministrator,
			AnnotationRevision revision, AnnotationReview review, PostCleaningResultSummary postCleaningResult) {
		return from(task, actorId, projectAdministrator, revision, review, postCleaningResult, null);
	}

	static AnnotationTaskResponse from(AnnotationTask task, String actorId, boolean projectAdministrator,
			AnnotationRevision revision, AnnotationReview review, PostCleaningResultSummary postCleaningResult,
			com.medicationvideo.platform.qualitycleaning.CaptureGroupCleaningSummary captureGroupCleaning) {
		return new AnnotationTaskResponse(task.getAnnotationTaskId(), task.getProjectId(), task.getClipAssetId(),
				task.getCaptureGroupId(), task.getPrimaryClipAssetId(), task.getPrimaryCameraNumber(),
				task.getCaptureGroupQualityReviewId(), task.getGroupQualityRevision(), task.getConfirmedViewSet(),
				task.getGroupInputVersion(), task.getLabelSchemaId(), task.getLabelSchemaDigest(),
				task.getAssigneeActorId(), task.getCreatedBy(), task.getStatus().name(),
				task.getStatus() == AnnotationTaskStatus.ASSIGNED && task.getAssigneeActorId().equals(actorId)
						&& (!task.isCaptureGroupTask() || task.hasPrimaryView()),
				task.getStatus() == AnnotationTaskStatus.SUBMITTED && projectAdministrator && revision != null
						&& review == null,
				task.getStatus() == AnnotationTaskStatus.COMPLETED && projectAdministrator && revision != null
						&& review != null && review.getDecision() == AnnotationReviewDecision.APPROVED,
				AnnotationRevisionSummary.from(revision, review, postCleaningResult, captureGroupCleaning),
				task.getCreatedAt(), task.getUpdatedAt(), task.getVersion());
	}
}

record AnnotationTaskListResponse(List<AnnotationTaskResponse> items, AnnotationTaskProgressResponse progress) {
}

record AnnotationTaskProgressResponse(String scope, long total, long awaitingSubmission, long reworkRequired,
		long awaitingReview, long completed) {

	static AnnotationTaskProgressResponse from(boolean projectAdministrator, List<AnnotationTaskResponse> items) {
		long awaitingSubmission = 0;
		long reworkRequired = 0;
		long awaitingReview = 0;
		long completed = 0;
		for (var item : items) {
			switch (item.status()) {
				case "ASSIGNED" -> {
					var revision = item.annotationRevision();
					if (revision != null && revision.review() != null
							&& "REJECTED".equals(revision.review().decision())) {
						reworkRequired++;
					}
					else {
						awaitingSubmission++;
					}
				}
				case "SUBMITTED" -> awaitingReview++;
				case "COMPLETED" -> completed++;
				default -> throw new IllegalStateException("Unsupported annotation task status: " + item.status());
			}
		}
		return new AnnotationTaskProgressResponse(projectAdministrator ? "PROJECT" : "ASSIGNEE", items.size(),
				awaitingSubmission, reworkRequired, awaitingReview, completed);
	}
}

record AnnotationRevisionSummary(UUID annotationRevisionId, int revisionNumber, UUID labelSchemaId,
		String labelSchemaDigest, Map<String, Object> annotationDocument, String submittedBy, Instant submittedAt,
		UUID captureGroupId, UUID primaryClipAssetId, Integer primaryCameraNumber, Map<String, Object> confirmedViewSet,
		String inputVersion, AnnotationReviewResponse review, PostCleaningResultResponse postCleaningResult,
		CaptureGroupCleaningResponse captureGroupCleaning) {

	static AnnotationRevisionSummary from(AnnotationRevision revision, AnnotationReview review,
			PostCleaningResultSummary postCleaningResult,
			com.medicationvideo.platform.qualitycleaning.CaptureGroupCleaningSummary captureGroupCleaning) {
		if (revision == null) {
			return null;
		}
		return new AnnotationRevisionSummary(revision.getAnnotationRevisionId(), revision.getRevisionNumber(),
				revision.getLabelSchemaId(), revision.getLabelSchemaDigest(), revision.getAnnotationDocument(),
				revision.getSubmittedBy(), revision.getSubmittedAt(), revision.getCaptureGroupId(),
				revision.getPrimaryClipAssetId(), revision.getPrimaryCameraNumber(), revision.getConfirmedViewSet(),
				revision.getInputVersion(), AnnotationReviewResponse.from(review),
				PostCleaningResultResponse.from(postCleaningResult),
				CaptureGroupCleaningResponse.from(captureGroupCleaning));
	}
}

record PostCleaningResultResponse(UUID cleaningResultId, UUID cleaningRunId, String ruleSetKey, String ruleSetVersion,
		String ruleSetDigest, String outcome, List<String> reasonCodes, List<String> executedChecks,
		Instant evaluatedAt) {

	static PostCleaningResultResponse from(PostCleaningResultSummary result) {
		if (result == null) {
			return null;
		}
		return new PostCleaningResultResponse(result.cleaningResultId(), result.cleaningRunId(), result.ruleSetKey(),
				result.ruleSetVersion(), result.ruleSetDigest(), result.outcome(), result.reasonCodes(),
				result.executedChecks(), result.evaluatedAt());
	}
}

record AnnotationRevisionResponse(UUID annotationRevisionId, UUID projectId, UUID annotationTaskId, UUID clipAssetId,
		UUID captureGroupId, UUID primaryClipAssetId, Integer primaryCameraNumber, Map<String, Object> confirmedViewSet,
		String inputVersion, int revisionNumber, String annotationSchemaVersion, UUID labelSchemaId,
		String labelSchemaDigest, String documentSha256, String submittedBy, Instant submittedAt) {

	static AnnotationRevisionResponse from(AnnotationRevision revision) {
		return new AnnotationRevisionResponse(revision.getAnnotationRevisionId(), revision.getProjectId(),
				revision.getAnnotationTaskId(), revision.getClipAssetId(), revision.getCaptureGroupId(),
				revision.getPrimaryClipAssetId(), revision.getPrimaryCameraNumber(), revision.getConfirmedViewSet(),
				revision.getInputVersion(), revision.getRevisionNumber(), revision.getAnnotationSchemaVersion(),
				revision.getLabelSchemaId(), revision.getLabelSchemaDigest(), revision.getDocumentSha256(),
				revision.getSubmittedBy(), revision.getSubmittedAt());
	}
}

record AnnotationReviewResponse(UUID annotationReviewId, UUID projectId, UUID annotationRevisionId, String decision,
		String reviewNote, String reviewedBy, Instant reviewedAt, UUID captureGroupId, Integer revision,
		UUID primaryClipAssetId, Integer primaryCameraNumber, Map<String, Object> confirmedViewSet, String inputVersion,
		List<Map<String, Object>> qualifiedViews, List<Map<String, Object>> rejectedMissingViews,
		List<Map<String, Object>> candidateEvidence) {

	static AnnotationReviewResponse from(AnnotationReview review) {
		if (review == null) {
			return null;
		}
		return new AnnotationReviewResponse(review.getAnnotationReviewId(), review.getProjectId(),
				review.getAnnotationRevisionId(), review.getDecision().name(), review.getReviewNote(),
				review.getReviewedBy(), review.getReviewedAt(), review.getCaptureGroupId(), review.getRevisionNumber(),
				review.getPrimaryClipAssetId(), review.getPrimaryCameraNumber(), review.getConfirmedViewSet(),
				review.getInputVersion(), review.getQualifiedViews(), review.getRejectedMissingViews(),
				review.getCandidateEvidence());
	}
}

record CaptureGroupCleaningResponse(UUID captureGroupCleaningId, UUID projectId, UUID captureGroupId,
		UUID annotationRevisionId, UUID annotationReviewId, String annotationReviewDecision, String groupQualityResult,
		long groupQualityRevision, UUID primaryClipAssetId, int primaryCameraNumber,
		List<Map<String, Object>> primaryInterval, List<Map<String, Object>> viewEvidence,
		List<Map<String, Object>> participatingViews, List<Map<String, Object>> excludedViews,
		List<Map<String, Object>> mappingProvenance, List<Map<String, Object>> candidateProvenance, String ruleSetKey,
		String ruleSetVersion, String ruleSetDigest, String outcome, List<String> reasonCodes,
		List<String> executedChecks, String inputFingerprint, String evaluatedBy, Instant evaluatedAt) {

	static CaptureGroupCleaningResponse from(
			com.medicationvideo.platform.qualitycleaning.CaptureGroupCleaningSummary summary) {
		if (summary == null) {
			return null;
		}
		return new CaptureGroupCleaningResponse(summary.captureGroupCleaningId(), summary.projectId(),
				summary.captureGroupId(), summary.annotationRevisionId(), summary.annotationReviewId(),
				summary.annotationReviewDecision(), summary.groupQualityResult(), summary.groupQualityRevision(),
				summary.primaryClipAssetId(), summary.primaryCameraNumber(), summary.primaryInterval(),
				summary.viewEvidence(), summary.participatingViews(), summary.excludedViews(),
				summary.mappingProvenance(), summary.candidateProvenance(), summary.ruleSetKey(),
				summary.ruleSetVersion(), summary.ruleSetDigest(), summary.outcome(), summary.reasonCodes(),
				summary.executedChecks(), summary.inputFingerprint(), summary.evaluatedBy(), summary.evaluatedAt());
	}
}
