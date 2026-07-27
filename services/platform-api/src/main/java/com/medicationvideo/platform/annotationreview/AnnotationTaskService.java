package com.medicationvideo.platform.annotationreview;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
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
import com.medicationvideo.platform.iamproject.ProjectRole;
import com.medicationvideo.platform.mediaprivacy.MediaDurationQueryService;
import com.medicationvideo.platform.qualitycleaning.PostCleaningQueryService;
import com.medicationvideo.platform.qualitycleaning.PostCleaningResultSummary;
import com.medicationvideo.platform.qualitycleaning.PostCleaningService;
import com.medicationvideo.platform.qualitycleaning.QualityReviewQueryService;

@Service
class AnnotationTaskService {

	private final ProjectAccessService projectAccessService;
	private final ClipAssetReviewQueryService assetQueryService;
	private final QualityReviewQueryService qualityReviewQueryService;
	private final MediaDurationQueryService mediaDurationQueryService;
	private final PostCleaningService postCleaningService;
	private final PostCleaningQueryService postCleaningQueryService;
	private final AnnotationLabelSchemaService labelSchemaService;
	private final AnnotationTaskRepository repository;
	private final AnnotationRevisionRepository revisionRepository;
	private final AnnotationReviewRepository reviewRepository;
	private final AuditEventRecorder auditRecorder;

	AnnotationTaskService(
			ProjectAccessService projectAccessService,
			ClipAssetReviewQueryService assetQueryService,
			QualityReviewQueryService qualityReviewQueryService,
			MediaDurationQueryService mediaDurationQueryService,
			PostCleaningService postCleaningService,
			PostCleaningQueryService postCleaningQueryService,
			AnnotationLabelSchemaService labelSchemaService,
			AnnotationTaskRepository repository,
			AnnotationRevisionRepository revisionRepository,
			AnnotationReviewRepository reviewRepository,
			AuditEventRecorder auditRecorder) {
		this.projectAccessService = projectAccessService;
		this.assetQueryService = assetQueryService;
		this.qualityReviewQueryService = qualityReviewQueryService;
		this.mediaDurationQueryService = mediaDurationQueryService;
		this.postCleaningService = postCleaningService;
		this.postCleaningQueryService = postCleaningQueryService;
		this.labelSchemaService = labelSchemaService;
		this.repository = repository;
		this.revisionRepository = revisionRepository;
		this.reviewRepository = reviewRepository;
		this.auditRecorder = auditRecorder;
	}

	@Transactional(readOnly = true)
	AnnotationTaskListResponse list(UUID projectId, String actorId) {
		var role = this.projectAccessService.requireMember(projectId, actorId);
		List<AnnotationTask> tasks = role == ProjectRole.ADMIN
				? this.repository.findAllByProjectIdOrderByCreatedAtDescAnnotationTaskIdDesc(projectId)
				: this.repository.findAllByProjectIdAndAssigneeActorIdOrderByCreatedAtDescAnnotationTaskIdDesc(
						projectId, actorId);
		if (tasks.isEmpty()) {
			return new AnnotationTaskListResponse(List.of());
		}
		var revisionsByTaskId = this.revisionRepository.findAllByAnnotationTaskIdIn(tasks.stream()
				.map(AnnotationTask::getAnnotationTaskId)
				.toList())
				.stream().collect(java.util.stream.Collectors.toMap(
						AnnotationRevision::getAnnotationTaskId,
						revision -> revision,
						(left, right) -> left.getRevisionNumber() >= right.getRevisionNumber() ? left : right));
		Map<UUID, AnnotationReview> reviewsByRevisionId = revisionsByTaskId.isEmpty()
				? Map.of()
				: this.reviewRepository.findAllByAnnotationRevisionIdIn(revisionsByTaskId.values()
						.stream().map(AnnotationRevision::getAnnotationRevisionId).toList())
						.stream().collect(java.util.stream.Collectors.toMap(
								AnnotationReview::getAnnotationRevisionId,
								review -> review));
		var postCleaningByRevisionId = revisionsByTaskId.isEmpty()
				? Map.<UUID, PostCleaningResultSummary>of()
				: this.postCleaningQueryService.findLatestByAnnotationRevisionIds(
						revisionsByTaskId.values()
								.stream().map(AnnotationRevision::getAnnotationRevisionId).toList());
		return new AnnotationTaskListResponse(tasks.stream()
				.map(task -> {
					var revision = revisionsByTaskId.get(task.getAnnotationTaskId());
					return AnnotationTaskResponse.from(
							task,
							actorId,
							role,
							revision,
							revision == null ? null : reviewsByRevisionId.get(revision.getAnnotationRevisionId()),
							revision == null
									? null
									: postCleaningByRevisionId.get(revision.getAnnotationRevisionId()));
				})
				.toList());
	}

	@Transactional
	AnnotationTaskResponse create(
			UUID projectId,
			UUID clipAssetId,
			String actorId,
			String idempotencyKey,
			String requestId,
			String traceId,
			AnnotationTaskCreateRequest request) {
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		var replay = this.repository.findByProjectIdAndCreatedByAndIdempotencyKey(
				projectId, actorId, idempotencyKey);
		if (replay.isPresent()) {
			if (!replay.get().matchesCreationRequest(clipAssetId, request.assigneeActorId())) {
				throw new AnnotationTaskException(
						"ANNOTATION_TASK_IDEMPOTENCY_KEY_REUSED",
						"The idempotency key was already used for a different annotation task.",
						409);
			}
			return AnnotationTaskResponse.from(replay.get(), actorId, ProjectRole.ADMIN, null, null, null);
		}

		var assetFacts = this.assetQueryService.lockAndGetReviewFacts(projectId, clipAssetId);
		if (!assetFacts.reviewable()) {
			throw new AnnotationTaskException(
					"ANNOTATION_TASK_NOT_READY",
					"The clip asset must complete media ingest before an annotation task can be created.",
					409);
		}
		if (!this.qualityReviewQueryService.lockAndGetCurrentDecision(projectId, clipAssetId).isQualified()) {
			throw new AnnotationTaskException(
					"ANNOTATION_TASK_QUALITY_NOT_QUALIFIED",
					"Only a human-qualified clip asset can enter annotation.",
					409);
		}
		this.projectAccessService.requireMember(projectId, request.assigneeActorId());
		if (this.repository.existsByProjectIdAndClipAssetId(projectId, clipAssetId)) {
			throw new AnnotationTaskException(
					"ANNOTATION_TASK_ALREADY_EXISTS",
					"The clip asset already has an annotation task in this project.",
					409);
		}

		var labelSchema = this.labelSchemaService.currentBinding();
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var task = this.repository.saveAndFlush(AnnotationTask.create(
				projectId,
				clipAssetId,
				request.assigneeActorId(),
				actorId,
				idempotencyKey,
				labelSchema,
				now));
		this.auditRecorder.record(new AuditEventCommand(
				now,
				AuditActorType.USER,
				actorId,
				projectId,
				"ANNOTATION_TASK_CREATED",
				"AnnotationTask",
				task.getAnnotationTaskId().toString(),
				Long.toString(task.getVersion()),
				AuditOutcome.SUCCEEDED,
				"QUALIFIED_CLIP_ASSET",
				requestId,
				traceId,
				"PROJECT_MEMBERSHIP_ADMIN",
				Map.of(
						"clip_asset_id", clipAssetId.toString(),
						"assignee_actor_id", task.getAssigneeActorId(),
						"label_schema_id", task.getLabelSchemaId().toString(),
						"label_schema_digest", task.getLabelSchemaDigest())));
		return AnnotationTaskResponse.from(task, actorId, ProjectRole.ADMIN, null, null, null);
	}

	@Transactional
	AnnotationRevisionResponse submitRevision(
			UUID projectId,
			UUID annotationTaskId,
			String actorId,
			String idempotencyKey,
			String requestId,
			String traceId,
			AnnotationRevisionSubmitCommand request) {
		this.projectAccessService.requireMember(projectId, actorId);
		var task = this.repository.findForUpdate(projectId, annotationTaskId)
				.orElseThrow(AnnotationTaskService::taskNotFound);
		if (!task.getAssigneeActorId().equals(actorId)) {
			throw new AnnotationTaskException(
					"ANNOTATION_TASK_NOT_ASSIGNEE",
					"Only the assigned project member can submit this annotation task.",
					403);
		}
		var document = toAnnotationDocument(request);
		var documentSha256 = digest(document);
		var replay = this.revisionRepository.findByAnnotationTaskIdAndSubmittedByAndIdempotencyKey(
				task.getAnnotationTaskId(), actorId, idempotencyKey);
		if (replay.isPresent()) {
			if (!replay.get().matchesSubmission(documentSha256)) {
				throw new AnnotationTaskException(
						"ANNOTATION_REVISION_IDEMPOTENCY_KEY_REUSED",
						"The idempotency key was already used for a different annotation revision.",
						409);
			}
			return AnnotationRevisionResponse.from(replay.get());
		}
		this.labelSchemaService.requireAllowedPrimaryStepLabels(
				task.getLabelSchemaId(),
				task.getLabelSchemaDigest(),
				request.segments().stream().map(AnnotationSegmentCommand::label).toList());
		validatePrimaryStepOverlap(request.segments());
		if (!this.assetQueryService.lockAndGetReviewFacts(projectId, task.getClipAssetId()).reviewable()
				|| !this.qualityReviewQueryService.lockAndGetCurrentDecision(projectId, task.getClipAssetId())
						.isQualified()) {
			throw new AnnotationTaskException(
					"ANNOTATION_TASK_NO_LONGER_QUALIFIED",
					"The clip asset is no longer eligible for annotation submission.",
					409);
		}
		validateSegmentBounds(projectId, task.getClipAssetId(), request.segments());
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		task.submit(now);
		var revisionNumber = this.revisionRepository.findTopByAnnotationTaskIdOrderByRevisionNumberDesc(
				task.getAnnotationTaskId())
				.map(revision -> revision.getRevisionNumber() + 1)
				.orElse(1);
		var revision = this.revisionRepository.saveAndFlush(AnnotationRevision.submit(
				task, revisionNumber, document, documentSha256, actorId, idempotencyKey, now));
		this.repository.saveAndFlush(task);
		this.auditRecorder.record(new AuditEventCommand(
				now,
				AuditActorType.USER,
				actorId,
				projectId,
				"ANNOTATION_REVISION_SUBMITTED",
				"AnnotationRevision",
				revision.getAnnotationRevisionId().toString(),
				Integer.toString(revision.getRevisionNumber()),
				AuditOutcome.SUCCEEDED,
				"MANUAL_ANNOTATION_SUBMITTED",
				requestId,
				traceId,
				"ANNOTATION_TASK_ASSIGNEE",
				Map.of(
						"annotation_task_id", task.getAnnotationTaskId().toString(),
						"clip_asset_id", task.getClipAssetId().toString(),
						"label_schema_id", revision.getLabelSchemaId().toString(),
						"label_schema_digest", revision.getLabelSchemaDigest(),
						"document_sha256", documentSha256,
						"segment_count", Integer.toString(request.segments().size()))));
		return AnnotationRevisionResponse.from(revision);
	}

	@Transactional
	AnnotationReviewResponse reviewRevision(
			UUID projectId,
			UUID annotationRevisionId,
			String actorId,
			String idempotencyKey,
			String requestId,
			String traceId,
			AnnotationReviewCommand request) {
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		var revision = this.revisionRepository.findForUpdate(projectId, annotationRevisionId)
				.orElseThrow(AnnotationTaskService::revisionNotFound);
		if (revision.getSubmittedBy().equals(actorId)) {
			throw new AnnotationTaskException(
					"ANNOTATION_REVIEW_SELF_REVIEW_FORBIDDEN",
					"The submitting member cannot independently review this annotation revision.",
					403);
		}
		var existing = this.reviewRepository.findByAnnotationRevisionId(annotationRevisionId);
		if (existing.isPresent()) {
			var review = existing.get();
			if (review.getReviewedBy().equals(actorId)
					&& review.matches(request.decision(), request.reviewNote())) {
				return AnnotationReviewResponse.from(review);
			}
			throw new AnnotationTaskException(
					"ANNOTATION_REVISION_ALREADY_REVIEWED",
					"The annotation revision already has an immutable review decision.",
					409);
		}
		var assetFacts = this.assetQueryService.lockAndGetReviewFacts(projectId, revision.getClipAssetId());
		var qualityDecision = this.qualityReviewQueryService.lockAndGetCurrentDecision(
				projectId, revision.getClipAssetId());
		if (!assetFacts.reviewable() || !qualityDecision.isQualified()) {
			throw new AnnotationTaskException(
					"ANNOTATION_REVISION_NO_LONGER_QUALIFIED",
					"The clip asset is no longer eligible for annotation review.",
					409);
		}
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var task = this.repository.findForUpdate(projectId, revision.getAnnotationTaskId())
				.orElseThrow(AnnotationTaskService::taskNotFound);
		if (task.getStatus() != AnnotationTaskStatus.SUBMITTED) {
			throw new AnnotationTaskException(
					"ANNOTATION_REVISION_NOT_CURRENT",
					"Only the current submitted annotation revision can be reviewed.",
					409);
		}
		var review = this.reviewRepository.saveAndFlush(AnnotationReview.record(
				revision,
				request.decision(),
				request.reviewNote(),
				actorId,
				idempotencyKey,
				now));
		if (review.getDecision() == AnnotationReviewDecision.REJECTED) {
			task.returnForRework(now);
			this.repository.saveAndFlush(task);
		}
		else {
			this.evaluatePostCleaning(
					projectId,
					revision,
					review,
					task,
					assetFacts.assetSha256(),
					qualityDecision,
					actorId,
					requestId,
					traceId,
					"INDEPENDENT_ANNOTATION_REVIEW");
			task.complete(now);
			this.repository.saveAndFlush(task);
		}
		this.auditRecorder.record(new AuditEventCommand(
				now,
				AuditActorType.USER,
				actorId,
				projectId,
				"ANNOTATION_REVISION_REVIEWED",
				"AnnotationReview",
				review.getAnnotationReviewId().toString(),
				review.getDecision().name(),
				AuditOutcome.SUCCEEDED,
				"INDEPENDENT_ANNOTATION_REVIEW",
				requestId,
				traceId,
				"PROJECT_MEMBERSHIP_ADMIN",
				Map.of(
						"annotation_revision_id", revision.getAnnotationRevisionId().toString(),
						"annotation_task_id", revision.getAnnotationTaskId().toString(),
						"decision", review.getDecision().name(),
						"reopened_for_rework", Boolean.toString(review.getDecision() == AnnotationReviewDecision.REJECTED),
						"task_completed", Boolean.toString(review.getDecision() == AnnotationReviewDecision.APPROVED))));
		return AnnotationReviewResponse.from(review);
	}

	@Transactional
	PostCleaningResultResponse recheckPostCleaning(
			UUID projectId,
			UUID annotationRevisionId,
			String actorId,
			String requestId,
			String traceId) {
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
		var qualityDecision = this.qualityReviewQueryService.lockAndGetCurrentDecision(
				projectId, revision.getClipAssetId());
		var task = this.repository.findForUpdate(projectId, revision.getAnnotationTaskId())
				.orElseThrow(AnnotationTaskService::taskNotFound);
		if (task.getStatus() != AnnotationTaskStatus.COMPLETED) {
			throw postCleaningRecheckNotReady();
		}
		return PostCleaningResultResponse.from(this.evaluatePostCleaning(
				projectId,
				revision,
				review,
				task,
				assetFacts.assetSha256(),
				qualityDecision,
				actorId,
				requestId,
				traceId,
				"PROJECT_MEMBERSHIP_ADMIN"));
	}

	private PostCleaningResultSummary evaluatePostCleaning(
			UUID projectId,
			AnnotationRevision revision,
			AnnotationReview review,
			AnnotationTask task,
			String assetSha256,
			QualityReviewQueryService.CurrentDecision qualityDecision,
			String actorId,
			String requestId,
			String traceId,
			String authorizationBasis) {
		var mediaFact = this.mediaDurationQueryService.findLatestVerifiedDurationFact(
				projectId, revision.getClipAssetId());
		return this.postCleaningService.evaluate(
				new PostCleaningService.EvaluationCommand(
						projectId,
						revision.getClipAssetId(),
						assetSha256,
						qualityDecision.version(),
						qualityDecision.isQualified(),
						revision.getAnnotationRevisionId(),
						review.getAnnotationReviewId(),
						true,
						revision.getDocumentSha256(),
						digest(revision.getAnnotationDocument()),
						task.getLabelSchemaId(),
						task.getLabelSchemaDigest(),
						revision.getLabelSchemaId(),
						revision.getLabelSchemaDigest(),
						this.labelSchemaService.allowedPrimaryStepLabels(
								revision.getLabelSchemaId(), revision.getLabelSchemaDigest()),
						toPostCleaningSegments(revision),
						mediaFact.map(fact -> fact.mediaReportId()).orElse(null),
						mediaFact.map(fact -> fact.inputDigest()).orElse(null),
						mediaFact.map(fact -> fact.recipeDigest()).orElse(null),
						mediaFact.map(fact -> fact.durationMicroseconds()).orElse(null),
						actorId,
						authorizationBasis),
				requestId,
				traceId);
	}

	private static List<PostCleaningService.SegmentSnapshot> toPostCleaningSegments(
			AnnotationRevision revision) {
		var value = revision.getAnnotationDocument().get("segments");
		if (!(value instanceof List<?> storedSegments)) {
			return List.of();
		}
		return storedSegments.stream().map(AnnotationTaskService::toPostCleaningSegment).toList();
	}

	private static PostCleaningService.SegmentSnapshot toPostCleaningSegment(Object value) {
		if (!(value instanceof Map<?, ?> segment)
				|| !(segment.get("start_ms") instanceof Number start)
				|| !(segment.get("end_ms") instanceof Number end)
				|| !(segment.get("label") instanceof String label)) {
			return new PostCleaningService.SegmentSnapshot(0, 0, "");
		}
		return new PostCleaningService.SegmentSnapshot(start.longValue(), end.longValue(), label);
	}

	private Map<String, Object> toAnnotationDocument(AnnotationRevisionSubmitCommand request) {
		var segments = request.segments().stream().map(segment -> {
			if (segment.endMs() <= segment.startMs()) {
				throw new AnnotationTaskException(
						"ANNOTATION_SEGMENT_TIME_RANGE_INVALID",
						"Each annotation segment must end after it starts.",
						400);
			}
			return Map.<String, Object>of(
					"start_ms", segment.startMs(),
					"end_ms", segment.endMs(),
					"label", segment.label().trim());
		}).toList();
		var document = new java.util.LinkedHashMap<String, Object>();
		document.put("schema_version", "annotation-document.v1");
		document.put("segments", segments);
		if (request.note() != null && !request.note().isBlank()) {
			document.put("note", request.note().trim());
		}
		return Map.copyOf(document);
	}

	private void validateSegmentBounds(
			UUID projectId,
			UUID clipAssetId,
			List<AnnotationSegmentCommand> segments) {
		var durationMicroseconds = this.mediaDurationQueryService
				.findVerifiedDurationMicroseconds(projectId, clipAssetId);
		if (durationMicroseconds.isEmpty()) {
			return;
		}
		var maximumEndMilliseconds = durationMicroseconds.getAsLong() / 1_000;
		for (var segment : segments) {
			if (segment.endMs() > maximumEndMilliseconds) {
				throw new AnnotationTaskException(
						"ANNOTATION_SEGMENT_EXCEEDS_MEDIA_DURATION",
						"An annotation segment exceeds the verified media duration.",
						400);
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
				throw new AnnotationTaskException(
						"ANNOTATION_PRIMARY_STEP_OVERLAP",
						"Primary Step annotation segments must not overlap.",
						400);
			}
			maximumPreviousEnd = Math.max(maximumPreviousEnd, segment.endMs());
		}
	}

	private static String digest(Map<String, Object> document) {
		if (!hasOnlyDocumentFields(document)
				|| !(document.get("schema_version") instanceof String schemaVersion)
				|| !(document.get("segments") instanceof List<?> segments)
				|| (document.containsKey("note") && !(document.get("note") instanceof String))) {
			return null;
		}
		try {
			var canonical = new StringBuilder(schemaVersion).append('\n');
			for (var value : segments) {
				if (!(value instanceof Map<?, ?> segment)
						|| segment.size() != 3
						|| !segment.containsKey("start_ms")
						|| !segment.containsKey("end_ms")
						|| !segment.containsKey("label")
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
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
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
		return document.size() == 2
				|| (document.size() == 3 && document.containsKey("note"));
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
		return new AnnotationTaskException(
				"ANNOTATION_TASK_NOT_FOUND",
				"The annotation task was not found in the current project.",
				404);
	}

	private static AnnotationTaskException revisionNotFound() {
		return new AnnotationTaskException(
				"ANNOTATION_REVISION_NOT_FOUND",
				"The annotation revision was not found in the current project.",
				404);
	}

	private static AnnotationTaskException postCleaningRecheckNotReady() {
		return new AnnotationTaskException(
				"ANNOTATION_POST_CLEANING_RECHECK_NOT_READY",
				"Only an approved revision for a completed, reviewable video can be rechecked.",
				409);
	}
}

record AnnotationTaskCreateRequest(String assigneeActorId) {
}

record AnnotationRevisionSubmitCommand(List<AnnotationSegmentCommand> segments, String note) {
}

record AnnotationSegmentCommand(long startMs, long endMs, String label) {
}

record AnnotationReviewCommand(AnnotationReviewDecision decision, String reviewNote) {
}

record AnnotationTaskResponse(
		UUID annotationTaskId,
		UUID projectId,
		UUID clipAssetId,
		UUID labelSchemaId,
		String labelSchemaDigest,
		String assigneeActorId,
		String createdBy,
		String status,
		boolean canSubmit,
		boolean canReview,
		boolean canRecheckPostCleaning,
		AnnotationRevisionSummary annotationRevision,
		Instant createdAt,
		Instant updatedAt,
		long version) {

	static AnnotationTaskResponse from(
			AnnotationTask task,
			String actorId,
			ProjectRole role,
			AnnotationRevision revision,
			AnnotationReview review,
			PostCleaningResultSummary postCleaningResult) {
		return new AnnotationTaskResponse(
				task.getAnnotationTaskId(),
				task.getProjectId(),
				task.getClipAssetId(),
				task.getLabelSchemaId(),
				task.getLabelSchemaDigest(),
				task.getAssigneeActorId(),
				task.getCreatedBy(),
				task.getStatus().name(),
				task.getStatus() == AnnotationTaskStatus.ASSIGNED
						&& task.getAssigneeActorId().equals(actorId),
				task.getStatus() == AnnotationTaskStatus.SUBMITTED
						&& role == ProjectRole.ADMIN && revision != null && review == null
						&& !revision.getSubmittedBy().equals(actorId),
				task.getStatus() == AnnotationTaskStatus.COMPLETED
						&& role == ProjectRole.ADMIN && revision != null && review != null
						&& review.getDecision() == AnnotationReviewDecision.APPROVED,
				AnnotationRevisionSummary.from(revision, review, postCleaningResult),
				task.getCreatedAt(),
				task.getUpdatedAt(),
				task.getVersion());
	}
}

record AnnotationTaskListResponse(List<AnnotationTaskResponse> items) {
}

record AnnotationRevisionSummary(
		UUID annotationRevisionId,
		int revisionNumber,
		UUID labelSchemaId,
		String labelSchemaDigest,
		Map<String, Object> annotationDocument,
		String submittedBy,
		Instant submittedAt,
		AnnotationReviewResponse review,
		PostCleaningResultResponse postCleaningResult) {

	static AnnotationRevisionSummary from(
			AnnotationRevision revision,
			AnnotationReview review,
			PostCleaningResultSummary postCleaningResult) {
		if (revision == null) {
			return null;
		}
		return new AnnotationRevisionSummary(
				revision.getAnnotationRevisionId(),
				revision.getRevisionNumber(),
				revision.getLabelSchemaId(),
				revision.getLabelSchemaDigest(),
				revision.getAnnotationDocument(),
				revision.getSubmittedBy(),
				revision.getSubmittedAt(),
				AnnotationReviewResponse.from(review),
				PostCleaningResultResponse.from(postCleaningResult));
	}
}

record PostCleaningResultResponse(
		UUID cleaningResultId,
		UUID cleaningRunId,
		String ruleSetKey,
		String ruleSetVersion,
		String ruleSetDigest,
		String outcome,
		List<String> reasonCodes,
		List<String> executedChecks,
		Instant evaluatedAt) {

	static PostCleaningResultResponse from(PostCleaningResultSummary result) {
		if (result == null) {
			return null;
		}
		return new PostCleaningResultResponse(
				result.cleaningResultId(),
				result.cleaningRunId(),
				result.ruleSetKey(),
				result.ruleSetVersion(),
				result.ruleSetDigest(),
				result.outcome(),
				result.reasonCodes(),
				result.executedChecks(),
				result.evaluatedAt());
	}
}

record AnnotationRevisionResponse(
		UUID annotationRevisionId,
		UUID projectId,
		UUID annotationTaskId,
		UUID clipAssetId,
		int revisionNumber,
		String annotationSchemaVersion,
		UUID labelSchemaId,
		String labelSchemaDigest,
		String documentSha256,
		String submittedBy,
		Instant submittedAt) {

	static AnnotationRevisionResponse from(AnnotationRevision revision) {
		return new AnnotationRevisionResponse(
				revision.getAnnotationRevisionId(),
				revision.getProjectId(),
				revision.getAnnotationTaskId(),
				revision.getClipAssetId(),
				revision.getRevisionNumber(),
				revision.getAnnotationSchemaVersion(),
				revision.getLabelSchemaId(),
				revision.getLabelSchemaDigest(),
				revision.getDocumentSha256(),
				revision.getSubmittedBy(),
				revision.getSubmittedAt());
	}
}

record AnnotationReviewResponse(
		UUID annotationReviewId,
		UUID projectId,
		UUID annotationRevisionId,
		String decision,
		String reviewNote,
		String reviewedBy,
		Instant reviewedAt) {

	static AnnotationReviewResponse from(AnnotationReview review) {
		if (review == null) {
			return null;
		}
		return new AnnotationReviewResponse(
				review.getAnnotationReviewId(),
				review.getProjectId(),
				review.getAnnotationRevisionId(),
				review.getDecision().name(),
				review.getReviewNote(),
				review.getReviewedBy(),
				review.getReviewedAt());
	}
}
