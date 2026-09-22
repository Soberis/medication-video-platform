package com.medicationvideo.platform.annotationreview;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import com.medicationvideo.platform.qualitycleaning.CaptureGroupInputFreezeQueryService;

@Service
class AnnotationTaskService {

	private final ProjectAccessService projectAccessService;

	private final CaptureGroupCleaningQueryService captureGroupCleaningQueryService;

	private final CaptureGroupInputFreezeQueryService captureGroupInputFreezeQueryService;

	private final AnnotationLabelSchemaService labelSchemaService;

	private final CaptureGroupAnnotationWorkflow captureGroupAnnotationWorkflow;

	private final AnnotationTaskRepository repository;

	private final AnnotationRevisionRepository revisionRepository;

	private final AnnotationReviewRepository reviewRepository;

	private final AnnotationTaskViewRepository taskViewRepository;

	private final AnnotationTaskViewService taskViewService;

	AnnotationTaskService(ProjectAccessService projectAccessService,
			CaptureGroupCleaningQueryService captureGroupCleaningQueryService,
			CaptureGroupInputFreezeQueryService captureGroupInputFreezeQueryService,
			AnnotationLabelSchemaService labelSchemaService,
			CaptureGroupAnnotationWorkflow captureGroupAnnotationWorkflow, AnnotationTaskRepository repository,
			AnnotationRevisionRepository revisionRepository, AnnotationReviewRepository reviewRepository,
			AnnotationTaskViewRepository taskViewRepository, AnnotationTaskViewService taskViewService) {
		this.projectAccessService = projectAccessService;
		this.captureGroupCleaningQueryService = captureGroupCleaningQueryService;
		this.captureGroupInputFreezeQueryService = captureGroupInputFreezeQueryService;
		this.labelSchemaService = labelSchemaService;
		this.captureGroupAnnotationWorkflow = captureGroupAnnotationWorkflow;
		this.repository = repository;
		this.revisionRepository = revisionRepository;
		this.reviewRepository = reviewRepository;
		this.taskViewRepository = taskViewRepository;
		this.taskViewService = taskViewService;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	AnnotationTaskListResponse list(UUID projectId, String actorId, boolean platformAdministrator) {
		var role = this.projectAccessService.requireMember(projectId, actorId);
		var projectAdministrator = platformAdministrator && role == ProjectRole.ADMIN;
		var tasks = this.repository.findAllByProjectIdOrderByCreatedAtDescAnnotationTaskIdDesc(projectId);
		if (tasks.isEmpty()) {
			var items = List.<AnnotationTaskResponse>of();
			return new AnnotationTaskListResponse(items, AnnotationTaskProgressResponse.from(items));
		}
		var tasksById = tasks.stream()
			.collect(java.util.stream.Collectors.toMap(AnnotationTask::getAnnotationTaskId, task -> task));
		var revisionsByTaskId = this.revisionRepository.findAllByAnnotationTaskIdIn(tasksById.keySet())
			.stream()
			.filter(revision -> tasksById.get(revision.getAnnotationTaskId()).isRevisionForCurrentGroupInput(revision))
			.collect(java.util.stream.Collectors.toMap(AnnotationRevision::getAnnotationTaskId, revision -> revision,
					(left, right) -> left.getRevisionNumber() >= right.getRevisionNumber() ? left : right));
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
			return AnnotationTaskResponse.from(task, projectAdministrator, revision,
					revision == null ? null : reviewsByRevisionId.get(revision.getAnnotationRevisionId()),
					revision == null ? null : captureGroupCleaningByRevisionId.get(revision.getAnnotationRevisionId()));
		}).toList();
		var viewsByTask = this.taskViewService.findAll(tasksById.keySet())
			.stream()
			.collect(java.util.stream.Collectors.groupingBy(AnnotationTaskCameraViewResponse::annotationTaskId));
		var enrichedItems = items.stream()
			.map(item -> item.withCameraViews(viewsByTask.getOrDefault(item.annotationTaskId(), List.of())))
			.toList();
		return new AnnotationTaskListResponse(enrichedItems, AnnotationTaskProgressResponse.from(enrichedItems));
	}

	@Transactional
	AnnotationTaskResponse assignForHumanLabel(UUID projectId, UUID captureGroupId, String actorId,
			String assigneeActorId, String idempotencyKey, String requestId, String traceId) {
		var response = this.captureGroupAnnotationWorkflow.assignForHumanLabel(projectId, captureGroupId, actorId,
				assigneeActorId, idempotencyKey, requestId, traceId);
		var task = this.repository.findByProjectIdAndCaptureGroupId(projectId, captureGroupId).orElseThrow();
		return response.withCameraViews(this.taskViewService.findAll(task.getAnnotationTaskId()));
	}

	@Transactional
	AnnotationTaskResponse updateCameraView(UUID projectId, UUID annotationTaskId, int cameraNumber, String actorId,
			boolean platformAdministrator, String idempotencyKey, String requestId, String traceId,
			AnnotationCameraViewUpdateCommand request) {
		var role = this.projectAccessService.requireMember(projectId, actorId);
		var projectAdministrator = platformAdministrator && role == ProjectRole.ADMIN;
		var task = this.repository.findForUpdate(projectId, annotationTaskId)
			.orElseThrow(AnnotationTaskService::taskNotFound);
		this.captureGroupAnnotationWorkflow.requireCurrentGroupInput(projectId, task);
		var view = this.taskViewRepository.findForUpdate(projectId, annotationTaskId, cameraNumber)
			.orElseThrow(() -> new AnnotationTaskException("ANNOTATION_CAMERA_VIEW_NOT_FOUND",
					"The camera number is not present in the frozen CaptureGroup view set.", 404));
		if (request.state() == AnnotationTaskViewState.SUBMITTED) {
			this.captureGroupAnnotationWorkflow.requireCameraViewEligibleForSubmission(task, view);
		}
		var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		var changed = this.taskViewService.update(task, view, actorId, idempotencyKey, request, now);
		if (changed) {
			task.touch(now);
			this.repository.saveAndFlush(task);
		}
		var revision = this.revisionRepository.findTopByAnnotationTaskIdOrderByRevisionNumberDesc(annotationTaskId)
			.filter(task::isRevisionForCurrentGroupInput)
			.orElse(null);
		var review = revision == null ? null
				: this.reviewRepository.findByAnnotationRevisionId(revision.getAnnotationRevisionId()).orElse(null);
		var cleaning = revision == null ? null
				: this.captureGroupCleaningQueryService
					.findLatestByAnnotationRevisionIds(List.of(revision.getAnnotationRevisionId()))
					.get(revision.getAnnotationRevisionId());
		var response = AnnotationTaskResponse.from(task, projectAdministrator, revision, review, cleaning);
		if (changed) {
			this.captureGroupAnnotationWorkflow.recordCameraViewUpdated(projectId, task, view, actorId, requestId,
					traceId);
		}
		return response.withCameraViews(this.taskViewService.findAll(annotationTaskId));
	}

	@Transactional
	AnnotationRevisionResponse submitRevision(UUID projectId, UUID annotationTaskId, String actorId,
			String idempotencyKey, String requestId, String traceId, AnnotationRevisionSubmitCommand request) {
		this.projectAccessService.requireMember(projectId, actorId);
		var task = this.repository.findForUpdate(projectId, annotationTaskId)
			.orElseThrow(AnnotationTaskService::taskNotFound);
		var replay = this.revisionRepository
			.findByAnnotationTaskIdAndSubmittedByAndIdempotencyKey(task.getAnnotationTaskId(), actorId, idempotencyKey);
		if (replay.isPresent()) {
			if (!replay.get()
				.matchesGroupSubmission(request.expectedTaskVersion(), request.expectedInputVersion(),
						toCorrespondenceDocument(request.correspondences()), request.note())) {
				throw new AnnotationTaskException("ANNOTATION_REVISION_IDEMPOTENCY_KEY_REUSED",
						"The idempotency key was already used for a different annotation revision.", 409);
			}
			return AnnotationRevisionResponse.from(replay.get());
		}
		if (task.getStatus() != AnnotationTaskStatus.ASSIGNED) {
			throw new AnnotationTaskException("ANNOTATION_TASK_ALREADY_SUBMITTED",
					"Only an assigned annotation task can submit a group revision.", 409);
		}
		if (request.expectedTaskVersion() != task.getVersion()) {
			throw new AnnotationTaskException("ANNOTATION_TASK_VERSION_CONFLICT",
					"The annotation task changed after this revision was prepared.", 409);
		}
		if (!Objects.equals(task.getGroupInputVersion(), request.expectedInputVersion())) {
			throw new AnnotationTaskException("ANNOTATION_TASK_INPUT_VERSION_CONFLICT",
					"The CaptureGroup input changed after this revision was prepared.", 409);
		}
		this.captureGroupAnnotationWorkflow.requireCurrentGroupInput(projectId, task);
		var views = this.taskViewRepository.findAllByAnnotationTaskIdOrderByCameraNumberAsc(task.getAnnotationTaskId())
			.stream()
			.map(AnnotationTaskCameraViewResponse::from)
			.toList();
		var document = buildV2Document(task, request, views);
		var documentSha256 = digestCanonical(document);
		return this.captureGroupAnnotationWorkflow.submitRevision(projectId, task, actorId, idempotencyKey, requestId,
				traceId, request, document, documentSha256);
	}

	@Transactional
	AnnotationReviewResponse reviewRevision(UUID projectId, UUID annotationRevisionId, String actorId,
			String idempotencyKey, String requestId, String traceId, AnnotationReviewCommand request) {
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		var revision = this.revisionRepository.findForUpdate(projectId, annotationRevisionId)
			.orElseThrow(AnnotationTaskService::revisionNotFound);
		var existing = this.reviewRepository.findByAnnotationRevisionId(annotationRevisionId);
		if (existing.isPresent()) {
			var review = existing.get();
			if (review.getReviewedBy().equals(actorId) && review.matches(request.decision(), request.reviewNote(),
					request.reviewedCameraNumbers(), request.returnCameraNumbers())) {
				return AnnotationReviewResponse.from(review);
			}
			throw new AnnotationTaskException("ANNOTATION_REVISION_ALREADY_REVIEWED",
					"The annotation revision already has an immutable review decision.", 409);
		}
		return this.captureGroupAnnotationWorkflow.reviewRevision(projectId, revision, actorId, idempotencyKey,
				requestId, traceId, request);
	}

	private Map<String, Object> buildV2Document(AnnotationTask task, AnnotationRevisionSubmitCommand request,
			List<AnnotationTaskCameraViewResponse> views) {
		var expectedCameraNumbers = new HashSet<Integer>();
		if (task.getConfirmedViewSet().get("views") instanceof List<?> expectedViews) {
			for (var value : expectedViews) {
				if (value instanceof Map<?, ?> map && map.get("camera_number") instanceof Number camera) {
					expectedCameraNumbers.add(camera.intValue());
				}
			}
		}
		var viewsByCamera = new LinkedHashMap<Integer, AnnotationTaskCameraViewResponse>();
		for (var view : views) {
			if (viewsByCamera.put(view.cameraNumber(), view) != null) {
				throw invalidViewDisposition();
			}
		}
		if (expectedCameraNumbers.isEmpty() || !expectedCameraNumbers.equals(viewsByCamera.keySet())) {
			throw invalidViewDisposition();
		}
		var submittedViews = views.stream().filter(view -> view.state() == AnnotationTaskViewState.SUBMITTED).toList();
		if (submittedViews.isEmpty() || views.stream()
			.anyMatch(view -> view.state() != AnnotationTaskViewState.SUBMITTED
					&& view.state() != AnnotationTaskViewState.EXCLUDED)) {
			throw invalidViewDisposition();
		}
		var correspondences = toCorrespondenceDocument(request.correspondences());
		var correspondenceBySegment = new HashMap<String, Map<String, Object>>();
		var segmentLabels = new HashMap<String, String>();
		var allSegmentKeys = new HashSet<String>();
		for (var view : submittedViews) {
			for (var segment : view.segments()) {
				var segmentId = readSegmentId(segment);
				var key = segmentKey(view.cameraNumber(), segmentId);
				if (!allSegmentKeys.add(key)) {
					throw invalidCorrespondence();
				}
				segmentLabels.put(key, stringValue(segment.get("label")));
			}
		}
		var correspondenceIds = new HashSet<UUID>();
		var groupedSegmentKeys = new HashSet<String>();
		for (var command : request.correspondences() == null ? List.<AnnotationCorrespondenceCommand>of()
				: request.correspondences()) {
			if (command.correspondenceId() == null || command.status() == null
					|| !correspondenceIds.add(command.correspondenceId()) || command.segments() == null
					|| command.segments().isEmpty()) {
				throw invalidCorrespondence();
			}
			var refs = command.segments()
				.stream()
				.sorted(Comparator.comparingInt(AnnotationCorrespondenceSegmentRefCommand::cameraNumber)
					.thenComparing(ref -> ref.segmentId() == null ? "" : ref.segmentId().toString()))
				.toList();
			var cameras = new HashSet<Integer>();
			var labels = new HashSet<String>();
			var refDocuments = new ArrayList<Map<String, Object>>();
			for (var ref : refs) {
				if (ref.segmentId() == null || !cameras.add(ref.cameraNumber())) {
					throw invalidCorrespondence();
				}
				var key = segmentKey(ref.cameraNumber(), ref.segmentId());
				if (!allSegmentKeys.contains(key) || !groupedSegmentKeys.add(key)) {
					throw invalidCorrespondence();
				}
				labels.add(segmentLabels.get(key));
				var refDocument = new LinkedHashMap<String, Object>();
				refDocument.put("camera_number", ref.cameraNumber());
				refDocument.put("segment_id", ref.segmentId().toString());
				refDocuments.add(java.util.Collections.unmodifiableMap(refDocument));
			}
			var reason = command.reason() == null || command.reason().isBlank() ? null : command.reason().trim();
			if (command.status() == AnnotationCorrespondenceStatus.CONFIRMED
					&& (cameras.size() < 2 || labels.size() != 1)) {
				throw invalidCorrespondence();
			}
			if (command.status() == AnnotationCorrespondenceStatus.UNCERTAIN
					&& (reason == null || reason.length() > 1000)) {
				throw invalidCorrespondence();
			}
			var details = new LinkedHashMap<String, Object>();
			details.put("correspondence_id", command.correspondenceId().toString());
			details.put("status", command.status().name());
			details.put("segments", refDocuments);
			if (reason != null) {
				details.put("reason", reason);
			}
			var frozenDetails = java.util.Collections.unmodifiableMap(details);
			for (var ref : refs) {
				correspondenceBySegment.put(segmentKey(ref.cameraNumber(), ref.segmentId()), frozenDetails);
			}
		}
		if (!groupedSegmentKeys.equals(allSegmentKeys)) {
			throw invalidCorrespondence();
		}
		var cameraDocuments = views.stream()
			.sorted(Comparator.comparingInt(AnnotationTaskCameraViewResponse::cameraNumber))
			.map(view -> cameraSnapshot(view, correspondenceBySegment))
			.toList();
		var document = new LinkedHashMap<String, Object>();
		document.put("schema_version", "annotation-document.v2");
		document.put("capture_group_id", task.getCaptureGroupId().toString());
		document.put("expected_task_version", request.expectedTaskVersion());
		document.put("input_version", task.getGroupInputVersion());
		document.put("label_schema_id", task.getLabelSchemaId().toString());
		document.put("label_schema_digest", task.getLabelSchemaDigest());
		document.put("camera_views", cameraDocuments);
		document.put("correspondences", correspondences);
		if (request.note() != null && !request.note().isBlank()) {
			document.put("note", request.note().trim());
		}
		return java.util.Collections.unmodifiableMap(document);
	}

	private static Map<String, Object> cameraSnapshot(AnnotationTaskCameraViewResponse view,
			Map<String, Map<String, Object>> correspondenceBySegment) {
		var snapshot = new LinkedHashMap<String, Object>();
		snapshot.put("camera_number", view.cameraNumber());
		putNullable(snapshot, "camera_stream_id", view.cameraStreamId());
		putNullable(snapshot, "clip_asset_id", view.clipAssetId());
		putNullable(snapshot, "clip_asset_sha256", view.sourceAssetSha256());
		snapshot.put("camera_stream_version", view.cameraStreamVersion());
		snapshot.put("clip_asset_version", view.clipAssetVersion());
		snapshot.put("media_status", view.mediaStatus());
		snapshot.put("disposition", view.state().name());
		snapshot.put("included", view.state() == AnnotationTaskViewState.SUBMITTED);
		if (view.state() == AnnotationTaskViewState.EXCLUDED) {
			putNullable(snapshot, "exclusion_reason", view.exclusionReason());
			snapshot.put("segments", List.of());
		}
		else {
			snapshot.put("note", view.note());
			var segments = view.segments().stream().map(value -> {
				var segmentId = readSegmentId(value);
				var group = correspondenceBySegment.get(segmentKey(view.cameraNumber(), segmentId));
				if (group == null) {
					throw invalidCorrespondence();
				}
				var segment = new LinkedHashMap<String, Object>();
				segment.put("segment_id", segmentId.toString());
				segment.put("start_ms", value.get("start_ms"));
				segment.put("end_ms", value.get("end_ms"));
				segment.put("label", value.get("label"));
				segment.put("source_candidate_ids", value.getOrDefault("source_candidate_ids", List.of()));
				segment.put("correspondence_id", group.get("correspondence_id"));
				segment.put("correspondence_status", group.get("status"));
				return java.util.Collections.unmodifiableMap(segment);
			}).toList();
			snapshot.put("segments", segments);
		}
		return java.util.Collections.unmodifiableMap(snapshot);
	}

	private static void putNullable(Map<String, Object> map, String key, Object value) {
		map.put(key, value);
	}

	private static List<Map<String, Object>> toCorrespondenceDocument(List<AnnotationCorrespondenceCommand> values) {
		if (values == null) {
			return List.of();
		}
		return values.stream()
			.sorted(Comparator
				.comparing(value -> value.correspondenceId() == null ? "" : value.correspondenceId().toString()))
			.map(value -> {
				var details = new LinkedHashMap<String, Object>();
				if (value.correspondenceId() != null) {
					details.put("correspondence_id", value.correspondenceId().toString());
				}
				if (value.status() != null) {
					details.put("status", value.status().name());
				}
				var refs = value.segments() == null ? List.<Map<String, Object>>of()
						: value.segments()
							.stream()
							.sorted(Comparator.comparingInt(AnnotationCorrespondenceSegmentRefCommand::cameraNumber)
								.thenComparing(ref -> ref.segmentId() == null ? "" : ref.segmentId().toString()))
							.map(ref -> {
								var item = new LinkedHashMap<String, Object>();
								item.put("camera_number", ref.cameraNumber());
								if (ref.segmentId() != null) {
									item.put("segment_id", ref.segmentId().toString());
								}
								return java.util.Collections.unmodifiableMap(item);
							})
							.toList();
				details.put("segments", refs);
				if (value.reason() != null && !value.reason().isBlank()) {
					details.put("reason", value.reason().trim());
				}
				return java.util.Collections.unmodifiableMap(details);
			})
			.toList();
	}

	private static String digestCanonical(Object value) {
		try {
			var canonical = new StringBuilder();
			appendCanonical(canonical, value);
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

	private static void appendCanonical(StringBuilder target, Object value) {
		if (value == null) {
			target.append('N');
		}
		else if (value instanceof Map<?, ?> map) {
			var keys = map.keySet().stream().map(String::valueOf).sorted().toList();
			target.append('M').append(keys.size()).append('{');
			for (var key : keys) {
				appendCanonical(target, key);
				appendCanonical(target, map.get(key));
			}
			target.append('}');
		}
		else if (value instanceof List<?> list) {
			target.append('L').append(list.size()).append('[');
			list.forEach(item -> appendCanonical(target, item));
			target.append(']');
		}
		else if (value instanceof Number || value instanceof Boolean) {
			var token = value.toString();
			target.append(value instanceof Number ? 'D' : 'B').append(token.length()).append(':').append(token);
		}
		else {
			var token = value.toString();
			target.append('S').append(token.length()).append(':').append(token);
		}
	}

	private static String segmentKey(int cameraNumber, UUID segmentId) {
		return cameraNumber + ":" + segmentId;
	}

	private static UUID readSegmentId(Map<String, Object> segment) {
		try {
			return UUID.fromString(Objects.toString(segment.get("segment_id"), ""));
		}
		catch (IllegalArgumentException exception) {
			throw invalidViewDisposition();
		}
	}

	private static String stringValue(Object value) {
		return value instanceof String text ? text : "";
	}

	private static AnnotationTaskException invalidViewDisposition() {
		return new AnnotationTaskException("ANNOTATION_CAMERA_VIEW_DISPOSITION_INCOMPLETE",
				"Every frozen camera slot must be submitted or excluded before group submission.", 409);
	}

	private static AnnotationTaskException invalidCorrespondence() {
		return new AnnotationTaskException("ANNOTATION_CORRESPONDENCE_INVALID",
				"Every submitted segment must map to one valid group correspondence.", 400);
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

record GroupSubmissionContext(Map<String, Object> confirmedViewSet, String inputVersion) {
}

record GroupReviewContext(UUID captureGroupId, int revisionNumber, UUID primaryClipAssetId, Integer primaryCameraNumber,
		Map<String, Object> confirmedViewSet, String inputVersion, UUID groupQualityReviewId, String groupQualityResult,
		long groupQualityRevision, List<Map<String, Object>> qualifiedViews,
		List<Map<String, Object>> rejectedMissingViews, List<Map<String, Object>> candidateEvidence,
		List<Integer> reviewedCameraNumbers, List<Integer> returnCameraNumbers) {

	GroupReviewContext {
		captureGroupId = Objects.requireNonNull(captureGroupId, "captureGroupId");
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
		reviewedCameraNumbers = List.copyOf(reviewedCameraNumbers);
		returnCameraNumbers = List.copyOf(returnCameraNumbers);
	}
}

record AnnotationRevisionSubmitCommand(long expectedTaskVersion, String expectedInputVersion,
		List<AnnotationCorrespondenceCommand> correspondences, String note) {
}

record AnnotationSegmentCommand(long startMs, long endMs, String label) {
}

record AnnotationCorrespondenceCommand(UUID correspondenceId, AnnotationCorrespondenceStatus status,
		List<AnnotationCorrespondenceSegmentRefCommand> segments, String reason) {
}

record AnnotationCorrespondenceSegmentRefCommand(int cameraNumber, UUID segmentId) {
}

enum AnnotationCorrespondenceStatus {

	CONFIRMED, UNCERTAIN

}

record AnnotationReviewCommand(AnnotationReviewDecision decision, String reviewNote,
		List<Integer> reviewedCameraNumbers, List<Integer> returnCameraNumbers) {
	AnnotationReviewCommand {
		reviewedCameraNumbers = reviewedCameraNumbers == null ? List.of()
				: reviewedCameraNumbers.stream().distinct().sorted().toList();
		returnCameraNumbers = returnCameraNumbers == null ? List.of()
				: returnCameraNumbers.stream().distinct().sorted().toList();
	}
}

record AnnotationTaskResponse(UUID annotationTaskId, UUID projectId, UUID captureGroupId, UUID primaryClipAssetId,
		Integer primaryCameraNumber, UUID captureGroupQualityReviewId, long groupQualityRevision,
		Map<String, Object> confirmedViewSet, String groupInputVersion, UUID labelSchemaId, String labelSchemaDigest,
		String assigneeActorId, String createdBy, String status, boolean canSubmit, boolean canReview,
		AnnotationRevisionSummary annotationRevision, Instant createdAt, Instant updatedAt, long version,
		List<AnnotationTaskCameraViewResponse> cameraViews, AnnotationTaskCameraViewProgress cameraViewProgress) {

	AnnotationTaskResponse {
		Objects.requireNonNull(captureGroupId, "captureGroupId");
		Objects.requireNonNull(captureGroupQualityReviewId, "captureGroupQualityReviewId");
		Objects.requireNonNull(confirmedViewSet, "confirmedViewSet");
		Objects.requireNonNull(groupInputVersion, "groupInputVersion");
		Objects.requireNonNull(cameraViews, "cameraViews");
		Objects.requireNonNull(cameraViewProgress, "cameraViewProgress");
	}

	static AnnotationTaskResponse from(AnnotationTask task, boolean projectAdministrator, AnnotationRevision revision,
			AnnotationReview review, CaptureGroupCleaningSummary captureGroupCleaning) {
		return new AnnotationTaskResponse(task.getAnnotationTaskId(), task.getProjectId(), task.getCaptureGroupId(),
				null, null, task.getCaptureGroupQualityReviewId(), task.getGroupQualityRevision(),
				task.getConfirmedViewSet(), task.getGroupInputVersion(), task.getLabelSchemaId(),
				task.getLabelSchemaDigest(), task.getAssigneeActorId(), task.getCreatedBy(), task.getStatus().name(),
				task.getStatus() == AnnotationTaskStatus.ASSIGNED,
				canReview(task, projectAdministrator, revision, review),
				AnnotationRevisionSummary.from(revision, review, captureGroupCleaning), task.getCreatedAt(),
				task.getUpdatedAt(), task.getVersion(), List.of(), AnnotationTaskCameraViewProgress.from(List.of()));
	}

	AnnotationTaskResponse withCameraViews(List<AnnotationTaskCameraViewResponse> views) {
		var progress = AnnotationTaskCameraViewProgress.from(views);
		var ready = progress.total() > 0 && progress.pending() == 0 && progress.draft() == 0 && progress.submitted() > 0
				&& progress.submitted() + progress.excluded() == progress.total();
		return new AnnotationTaskResponse(this.annotationTaskId, this.projectId, this.captureGroupId,
				this.primaryClipAssetId, this.primaryCameraNumber, this.captureGroupQualityReviewId,
				this.groupQualityRevision, this.confirmedViewSet, this.groupInputVersion, this.labelSchemaId,
				this.labelSchemaDigest, this.assigneeActorId, this.createdBy, this.status, this.canSubmit && ready,
				this.canReview, this.annotationRevision, this.createdAt, this.updatedAt, this.version,
				List.copyOf(views), progress);
	}

	private static boolean canReview(AnnotationTask task, boolean projectAdministrator, AnnotationRevision revision,
			AnnotationReview review) {
		return task.getStatus() == AnnotationTaskStatus.SUBMITTED && projectAdministrator && revision != null
				&& review == null;
	}

}

record AnnotationTaskListResponse(List<AnnotationTaskResponse> items, AnnotationTaskProgressResponse progress) {
}

record AnnotationTaskProgressResponse(String scope, long total, long awaitingSubmission, long reworkRequired,
		long awaitingReview, long completed) {

	static AnnotationTaskProgressResponse from(List<AnnotationTaskResponse> items) {
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
		return new AnnotationTaskProgressResponse("PROJECT", items.size(), awaitingSubmission, reworkRequired,
				awaitingReview, completed);
	}
}

record AnnotationRevisionSummary(UUID annotationRevisionId, int revisionNumber, UUID labelSchemaId,
		String labelSchemaDigest, Map<String, Object> annotationDocument, String submittedBy, Instant submittedAt,
		UUID captureGroupId, UUID primaryClipAssetId, Integer primaryCameraNumber, Map<String, Object> confirmedViewSet,
		String inputVersion, AnnotationReviewResponse review, CaptureGroupCleaningResponse captureGroupCleaning) {

	AnnotationRevisionSummary {
		Objects.requireNonNull(captureGroupId, "captureGroupId");
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
		UUID captureGroupId, UUID primaryClipAssetId, Integer primaryCameraNumber, Map<String, Object> confirmedViewSet,
		String inputVersion, int revisionNumber, String annotationSchemaVersion, UUID labelSchemaId,
		String labelSchemaDigest, String documentSha256, String submittedBy, Instant submittedAt) {

	AnnotationRevisionResponse {
		Objects.requireNonNull(captureGroupId, "captureGroupId");
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
		UUID primaryClipAssetId, Integer primaryCameraNumber, Map<String, Object> confirmedViewSet, String inputVersion,
		List<Map<String, Object>> qualifiedViews, List<Map<String, Object>> rejectedMissingViews,
		List<Map<String, Object>> candidateEvidence, List<Integer> reviewedCameraNumbers,
		List<Integer> returnCameraNumbers) {

	AnnotationReviewResponse {
		Objects.requireNonNull(captureGroupId, "captureGroupId");
		Objects.requireNonNull(confirmedViewSet, "confirmedViewSet");
		Objects.requireNonNull(inputVersion, "inputVersion");
		Objects.requireNonNull(qualifiedViews, "qualifiedViews");
		Objects.requireNonNull(rejectedMissingViews, "rejectedMissingViews");
		Objects.requireNonNull(candidateEvidence, "candidateEvidence");
		Objects.requireNonNull(reviewedCameraNumbers, "reviewedCameraNumbers");
		Objects.requireNonNull(returnCameraNumbers, "returnCameraNumbers");
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
				review.getCandidateEvidence(), review.getReviewedCameraNumbers(), review.getReturnCameraNumbers());
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
