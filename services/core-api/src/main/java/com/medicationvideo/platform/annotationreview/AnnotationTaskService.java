package com.medicationvideo.platform.annotationreview;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.iamproject.ProjectAccessService;
import com.medicationvideo.platform.iamproject.ProjectRole;
import com.medicationvideo.platform.qualitycleaning.CaptureGroupCleaningQueryService;
import com.medicationvideo.platform.qualitycleaning.CaptureGroupCleaningSummary;
import com.medicationvideo.platform.qualitycleaning.CaptureGroupQualityQueryService;

@Service
class AnnotationTaskService {

	private final ProjectAccessService projectAccessService;

	private final CaptureGroupCleaningQueryService captureGroupCleaningQueryService;

	private final CaptureGroupQualityQueryService captureGroupQualityQueryService;

	private final AnnotationLabelSchemaService labelSchemaService;

	private final CaptureGroupAnnotationWorkflow captureGroupAnnotationWorkflow;

	private final AnnotationTaskRepository repository;

	private final AnnotationRevisionRepository revisionRepository;

	private final AnnotationReviewRepository reviewRepository;

	AnnotationTaskService(ProjectAccessService projectAccessService,
			CaptureGroupCleaningQueryService captureGroupCleaningQueryService,
			CaptureGroupQualityQueryService captureGroupQualityQueryService,
			AnnotationLabelSchemaService labelSchemaService,
			CaptureGroupAnnotationWorkflow captureGroupAnnotationWorkflow, AnnotationTaskRepository repository,
			AnnotationRevisionRepository revisionRepository, AnnotationReviewRepository reviewRepository) {
		this.projectAccessService = projectAccessService;
		this.captureGroupCleaningQueryService = captureGroupCleaningQueryService;
		this.captureGroupQualityQueryService = captureGroupQualityQueryService;
		this.labelSchemaService = labelSchemaService;
		this.captureGroupAnnotationWorkflow = captureGroupAnnotationWorkflow;
		this.repository = repository;
		this.revisionRepository = revisionRepository;
		this.reviewRepository = reviewRepository;
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
		var tasksById = tasks.stream()
			.collect(java.util.stream.Collectors.toMap(AnnotationTask::getAnnotationTaskId, task -> task));
		var revisionsByTaskId = this.revisionRepository.findAllByAnnotationTaskIdIn(tasksById.keySet())
			.stream()
			.filter(revision -> tasksById.get(revision.getAnnotationTaskId()).isRevisionForCurrentGroupInput(revision))
			.collect(java.util.stream.Collectors.toMap(AnnotationRevision::getAnnotationTaskId, revision -> revision,
					(left, right) -> left.getRevisionNumber() >= right.getRevisionNumber() ? left : right));
		var captureGroupIds = tasks.stream().map(AnnotationTask::getCaptureGroupId).distinct().toList();
		var currentGroupQualityById = this.captureGroupQualityQueryService
			.getCurrentDecisions(projectId, captureGroupIds)
			.stream()
			.collect(java.util.stream.Collectors.toUnmodifiableMap(
					CaptureGroupQualityQueryService.CurrentDecision::captureGroupId,
					CaptureGroupQualityQueryService.CurrentDecision::isAdmitting));
		Map<UUID, AnnotationReview> reviewsByRevisionId = revisionsByTaskId.isEmpty() ? Map.of() : this.reviewRepository
			.findAllByAnnotationRevisionIdIn(
					revisionsByTaskId.values().stream().map(AnnotationRevision::getAnnotationRevisionId).toList())
			.stream()
			.collect(java.util.stream.Collectors.toMap(AnnotationReview::getAnnotationRevisionId, review -> review));
		var captureGroupCleaningByRevisionId = revisionsByTaskId.isEmpty() ? Map.<UUID, CaptureGroupCleaningSummary>of()
				: this.captureGroupCleaningQueryService.findLatestByAnnotationRevisionIds(
						revisionsByTaskId.values().stream().map(AnnotationRevision::getAnnotationRevisionId).toList());
		var items = tasks.stream().map(task -> {
			var revision = revisionsByTaskId.get(task.getAnnotationTaskId());
			return AnnotationTaskResponse.from(task, actorId, projectAdministrator, revision,
					revision == null ? null : reviewsByRevisionId.get(revision.getAnnotationRevisionId()),
					revision == null ? null : captureGroupCleaningByRevisionId.get(revision.getAnnotationRevisionId()),
					currentGroupQualityById.getOrDefault(task.getCaptureGroupId(), false));
		}).toList();
		return new AnnotationTaskListResponse(items, AnnotationTaskProgressResponse.from(projectAdministrator, items));
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
		return this.captureGroupAnnotationWorkflow.submitRevision(projectId, task, actorId, idempotencyKey, requestId,
				traceId, request, document, documentSha256);
	}

	@Transactional
	AnnotationTaskResponse selectPrimaryView(UUID projectId, UUID annotationTaskId, String actorId, UUID clipAssetId,
			String requestId, String traceId) {
		return this.captureGroupAnnotationWorkflow.selectPrimaryView(projectId, annotationTaskId, actorId, clipAssetId,
				requestId, traceId);
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
		return this.captureGroupAnnotationWorkflow.reviewRevision(projectId, revision, actorId, selfAdminApproval,
				idempotencyKey, requestId, traceId, request);
	}

	@Transactional
	CaptureGroupCleaningResponse recheckCaptureGroupCleaning(UUID projectId, UUID annotationRevisionId, String actorId,
			String requestId, String traceId) {
		return this.captureGroupAnnotationWorkflow.recheckCleaning(projectId, annotationRevisionId, actorId, requestId,
				traceId);
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

}

record GroupSubmissionContext(UUID primaryClipAssetId, Map<String, Object> confirmedViewSet, String inputVersion) {
}

record GroupReviewContext(UUID captureGroupId, int revisionNumber, UUID primaryClipAssetId, int primaryCameraNumber,
		Map<String, Object> confirmedViewSet, String inputVersion, UUID groupQualityReviewId, String groupQualityResult,
		long groupQualityRevision, List<Map<String, Object>> qualifiedViews,
		List<Map<String, Object>> rejectedMissingViews, List<Map<String, Object>> candidateEvidence) {

	GroupReviewContext {
		captureGroupId = Objects.requireNonNull(captureGroupId, "captureGroupId");
		primaryClipAssetId = Objects.requireNonNull(primaryClipAssetId, "primaryClipAssetId");
		confirmedViewSet = Map.copyOf(Objects.requireNonNull(confirmedViewSet, "confirmedViewSet"));
		inputVersion = Objects.requireNonNull(inputVersion, "inputVersion");
		qualifiedViews = Objects.requireNonNull(qualifiedViews, "qualifiedViews")
			.stream()
			.map(value -> java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(value)))
			.toList();
		rejectedMissingViews = Objects.requireNonNull(rejectedMissingViews, "rejectedMissingViews")
			.stream()
			.map(value -> java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(value)))
			.toList();
		candidateEvidence = Objects.requireNonNull(candidateEvidence, "candidateEvidence")
			.stream()
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

record AnnotationTaskResponse(UUID annotationTaskId, UUID projectId, UUID captureGroupId, UUID primaryClipAssetId,
		Integer primaryCameraNumber, UUID captureGroupQualityReviewId, long groupQualityRevision,
		Map<String, Object> confirmedViewSet, String groupInputVersion, UUID labelSchemaId, String labelSchemaDigest,
		String assigneeActorId, String createdBy, String status, boolean canSubmit, boolean canSelectPrimary,
		boolean canReview, boolean canRecheckCaptureGroupCleaning, AnnotationRevisionSummary annotationRevision,
		Instant createdAt, Instant updatedAt, long version) {

	AnnotationTaskResponse {
		Objects.requireNonNull(captureGroupId, "captureGroupId");
		Objects.requireNonNull(captureGroupQualityReviewId, "captureGroupQualityReviewId");
		Objects.requireNonNull(confirmedViewSet, "confirmedViewSet");
		Objects.requireNonNull(groupInputVersion, "groupInputVersion");
	}

	static AnnotationTaskResponse from(AnnotationTask task, String actorId, boolean projectAdministrator,
			AnnotationRevision revision, AnnotationReview review, CaptureGroupCleaningSummary captureGroupCleaning) {
		return from(task, actorId, projectAdministrator, revision, review, captureGroupCleaning, true);
	}

	static AnnotationTaskResponse from(AnnotationTask task, String actorId, boolean projectAdministrator,
			AnnotationRevision revision, AnnotationReview review, CaptureGroupCleaningSummary captureGroupCleaning,
			boolean captureGroupQualityAdmitting) {
		return new AnnotationTaskResponse(task.getAnnotationTaskId(), task.getProjectId(), task.getCaptureGroupId(),
				task.getPrimaryClipAssetId(), task.getPrimaryCameraNumber(), task.getCaptureGroupQualityReviewId(),
				task.getGroupQualityRevision(), task.getConfirmedViewSet(), task.getGroupInputVersion(),
				task.getLabelSchemaId(), task.getLabelSchemaDigest(), task.getAssigneeActorId(), task.getCreatedBy(),
				task.getStatus().name(), canSubmit(task, actorId, captureGroupQualityAdmitting),
				canSelectPrimary(task, actorId, revision, captureGroupQualityAdmitting),
				canReview(task, projectAdministrator, revision, review, captureGroupQualityAdmitting),
				canRecheckCaptureGroupCleaning(task, projectAdministrator, revision, review,
						captureGroupQualityAdmitting),
				AnnotationRevisionSummary.from(revision, review, captureGroupCleaning), task.getCreatedAt(),
				task.getUpdatedAt(), task.getVersion());
	}

	private static boolean canSubmit(AnnotationTask task, String actorId, boolean captureGroupQualityAdmitting) {
		return task.getStatus() == AnnotationTaskStatus.ASSIGNED && task.getAssigneeActorId().equals(actorId)
				&& task.hasPrimaryView() && captureGroupQualityAdmitting;
	}

	private static boolean canSelectPrimary(AnnotationTask task, String actorId, AnnotationRevision revision,
			boolean captureGroupQualityAdmitting) {
		return task.getStatus() == AnnotationTaskStatus.ASSIGNED && task.getAssigneeActorId().equals(actorId)
				&& revision == null && captureGroupQualityAdmitting;
	}

	private static boolean canReview(AnnotationTask task, boolean projectAdministrator, AnnotationRevision revision,
			AnnotationReview review, boolean captureGroupQualityAdmitting) {
		return task.getStatus() == AnnotationTaskStatus.SUBMITTED && projectAdministrator && revision != null
				&& review == null && captureGroupQualityAdmitting;
	}

	private static boolean canRecheckCaptureGroupCleaning(AnnotationTask task, boolean projectAdministrator,
			AnnotationRevision revision, AnnotationReview review, boolean captureGroupQualityAdmitting) {
		return task.getStatus() == AnnotationTaskStatus.COMPLETED && projectAdministrator && revision != null
				&& review != null && review.getDecision() == AnnotationReviewDecision.APPROVED
				&& captureGroupQualityAdmitting;
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
		UUID captureGroupId, UUID primaryClipAssetId, int primaryCameraNumber, Map<String, Object> confirmedViewSet,
		String inputVersion, AnnotationReviewResponse review, CaptureGroupCleaningResponse captureGroupCleaning) {

	AnnotationRevisionSummary {
		Objects.requireNonNull(captureGroupId, "captureGroupId");
		Objects.requireNonNull(primaryClipAssetId, "primaryClipAssetId");
		Objects.requireNonNull(confirmedViewSet, "confirmedViewSet");
		Objects.requireNonNull(inputVersion, "inputVersion");
	}

	static AnnotationRevisionSummary from(AnnotationRevision revision, AnnotationReview review,
			CaptureGroupCleaningSummary captureGroupCleaning) {
		if (revision == null) {
			return null;
		}
		return new AnnotationRevisionSummary(revision.getAnnotationRevisionId(), revision.getRevisionNumber(),
				revision.getLabelSchemaId(), revision.getLabelSchemaDigest(), revision.getAnnotationDocument(),
				revision.getSubmittedBy(), revision.getSubmittedAt(), revision.getCaptureGroupId(),
				revision.getPrimaryClipAssetId(), revision.getPrimaryCameraNumber(), revision.getConfirmedViewSet(),
				revision.getInputVersion(), AnnotationReviewResponse.from(review),
				CaptureGroupCleaningResponse.from(captureGroupCleaning));
	}
}

record AnnotationRevisionResponse(UUID annotationRevisionId, UUID projectId, UUID annotationTaskId, UUID clipAssetId,
		UUID captureGroupId, UUID primaryClipAssetId, int primaryCameraNumber, Map<String, Object> confirmedViewSet,
		String inputVersion, int revisionNumber, String annotationSchemaVersion, UUID labelSchemaId,
		String labelSchemaDigest, String documentSha256, String submittedBy, Instant submittedAt) {

	AnnotationRevisionResponse {
		Objects.requireNonNull(captureGroupId, "captureGroupId");
		Objects.requireNonNull(primaryClipAssetId, "primaryClipAssetId");
		Objects.requireNonNull(confirmedViewSet, "confirmedViewSet");
		Objects.requireNonNull(inputVersion, "inputVersion");
	}

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
		String reviewNote, String reviewedBy, Instant reviewedAt, UUID captureGroupId, int revision,
		UUID primaryClipAssetId, int primaryCameraNumber, Map<String, Object> confirmedViewSet, String inputVersion,
		List<Map<String, Object>> qualifiedViews, List<Map<String, Object>> rejectedMissingViews,
		List<Map<String, Object>> candidateEvidence) {

	AnnotationReviewResponse {
		Objects.requireNonNull(captureGroupId, "captureGroupId");
		Objects.requireNonNull(primaryClipAssetId, "primaryClipAssetId");
		Objects.requireNonNull(confirmedViewSet, "confirmedViewSet");
		Objects.requireNonNull(inputVersion, "inputVersion");
		Objects.requireNonNull(qualifiedViews, "qualifiedViews");
		Objects.requireNonNull(rejectedMissingViews, "rejectedMissingViews");
		Objects.requireNonNull(candidateEvidence, "candidateEvidence");
	}

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
