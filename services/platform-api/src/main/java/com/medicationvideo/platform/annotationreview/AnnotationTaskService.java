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
import com.medicationvideo.platform.qualitycleaning.QualityReviewQueryService;

@Service
class AnnotationTaskService {

	private final ProjectAccessService projectAccessService;
	private final ClipAssetReviewQueryService assetQueryService;
	private final QualityReviewQueryService qualityReviewQueryService;
	private final AnnotationTaskRepository repository;
	private final AnnotationRevisionRepository revisionRepository;
	private final AuditEventRecorder auditRecorder;

	AnnotationTaskService(
			ProjectAccessService projectAccessService,
			ClipAssetReviewQueryService assetQueryService,
			QualityReviewQueryService qualityReviewQueryService,
			AnnotationTaskRepository repository,
			AnnotationRevisionRepository revisionRepository,
			AuditEventRecorder auditRecorder) {
		this.projectAccessService = projectAccessService;
		this.assetQueryService = assetQueryService;
		this.qualityReviewQueryService = qualityReviewQueryService;
		this.repository = repository;
		this.revisionRepository = revisionRepository;
		this.auditRecorder = auditRecorder;
	}

	@Transactional(readOnly = true)
	AnnotationTaskListResponse list(UUID projectId, String actorId) {
		var role = this.projectAccessService.requireMember(projectId, actorId);
		List<AnnotationTask> tasks = role == ProjectRole.ADMIN
				? this.repository.findAllByProjectIdOrderByCreatedAtDescAnnotationTaskIdDesc(projectId)
				: this.repository.findAllByProjectIdAndAssigneeActorIdOrderByCreatedAtDescAnnotationTaskIdDesc(
						projectId, actorId);
		return new AnnotationTaskListResponse(tasks.stream()
				.map(task -> AnnotationTaskResponse.from(task, actorId))
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
			return AnnotationTaskResponse.from(replay.get(), actorId);
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

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var task = this.repository.saveAndFlush(AnnotationTask.create(
				projectId,
				clipAssetId,
				request.assigneeActorId(),
				actorId,
				idempotencyKey,
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
						"assignee_actor_id", task.getAssigneeActorId())));
		return AnnotationTaskResponse.from(task, actorId);
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
		var documentSha256 = digest(request);
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
		if (!this.assetQueryService.lockAndGetReviewFacts(projectId, task.getClipAssetId()).reviewable()
				|| !this.qualityReviewQueryService.lockAndGetCurrentDecision(projectId, task.getClipAssetId())
						.isQualified()) {
			throw new AnnotationTaskException(
					"ANNOTATION_TASK_NO_LONGER_QUALIFIED",
					"The clip asset is no longer eligible for annotation submission.",
					409);
		}
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		task.submit(now);
		var revision = this.revisionRepository.saveAndFlush(AnnotationRevision.submit(
				task, document, documentSha256, actorId, idempotencyKey, now));
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
						"document_sha256", documentSha256,
						"segment_count", Integer.toString(request.segments().size()))));
		return AnnotationRevisionResponse.from(revision);
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

	private static String digest(AnnotationRevisionSubmitCommand request) {
		try {
			var canonical = new StringBuilder("annotation-document.v1\n");
			for (var segment : request.segments()) {
				canonical.append(segment.startMs()).append(':').append(segment.endMs()).append(':');
				appendLengthPrefixed(canonical, segment.label().trim());
				canonical.append('\n');
			}
			canonical.append("note:");
			appendLengthPrefixed(canonical, request.note() == null ? "" : request.note().trim());
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
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
}

record AnnotationTaskCreateRequest(String assigneeActorId) {
}

record AnnotationRevisionSubmitCommand(List<AnnotationSegmentCommand> segments, String note) {
}

record AnnotationSegmentCommand(long startMs, long endMs, String label) {
}

record AnnotationTaskResponse(
		UUID annotationTaskId,
		UUID projectId,
		UUID clipAssetId,
		String assigneeActorId,
		String createdBy,
		String status,
		boolean canSubmit,
		Instant createdAt,
		Instant updatedAt,
		long version) {

	static AnnotationTaskResponse from(AnnotationTask task, String actorId) {
		return new AnnotationTaskResponse(
				task.getAnnotationTaskId(),
				task.getProjectId(),
				task.getClipAssetId(),
				task.getAssigneeActorId(),
				task.getCreatedBy(),
				task.getStatus().name(),
				task.getStatus() == AnnotationTaskStatus.ASSIGNED
						&& task.getAssigneeActorId().equals(actorId),
				task.getCreatedAt(),
				task.getUpdatedAt(),
				task.getVersion());
	}
}

record AnnotationTaskListResponse(List<AnnotationTaskResponse> items) {
}

record AnnotationRevisionResponse(
		UUID annotationRevisionId,
		UUID projectId,
		UUID annotationTaskId,
		UUID clipAssetId,
		int revisionNumber,
		String annotationSchemaVersion,
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
				revision.getDocumentSha256(),
				revision.getSubmittedBy(),
				revision.getSubmittedAt());
	}
}
