package com.medicationvideo.platform.annotationreview;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.aicandidate.ActionCandidateSource;
import com.medicationvideo.platform.aicandidate.ActionCandidateSourceQueryPort;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService.CaptureGroupView;
import com.medicationvideo.platform.mediaprivacy.MediaDurationQueryService;

@Service
class AnnotationTaskViewService {

	private final AnnotationTaskViewRepository repository;

	private final AnnotationTaskViewHistoryRepository historyRepository;

	private final AnnotationLabelSchemaService labelSchemaService;

	private final MediaDurationQueryService mediaDurationQueryService;

	private final ObjectProvider<ActionCandidateSourceQueryPort> candidateSource;

	AnnotationTaskViewService(AnnotationTaskViewRepository repository,
			AnnotationTaskViewHistoryRepository historyRepository, AnnotationLabelSchemaService labelSchemaService,
			MediaDurationQueryService mediaDurationQueryService,
			ObjectProvider<ActionCandidateSourceQueryPort> candidateSource) {
		this.repository = repository;
		this.historyRepository = historyRepository;
		this.labelSchemaService = labelSchemaService;
		this.mediaDurationQueryService = mediaDurationQueryService;
		this.candidateSource = candidateSource;
	}

	@Transactional
	void synchronize(AnnotationTask task, List<CaptureGroupView> currentViews, boolean resetOnInputChange,
			Instant now) {
		var byCamera = new LinkedHashMap<Integer, CaptureGroupView>();
		for (var view : currentViews) {
			if (byCamera.put(view.cameraNumber(), view) != null) {
				throw sourceSnapshotInvalid();
			}
		}
		var expected = cameraNumbers(task.getConfirmedViewSet());
		if (!expected.equals(byCamera.keySet())) {
			throw sourceSnapshotInvalid();
		}
		var persisted = this.repository.findAllByAnnotationTaskIdOrderByCameraNumberAsc(task.getAnnotationTaskId());
		var byPersistedCamera = persisted.stream()
			.collect(java.util.stream.Collectors.toMap(AnnotationTaskView::getCameraNumber, value -> value));
		for (var view : currentViews) {
			var row = byPersistedCamera.get(view.cameraNumber());
			if (row == null) {
				this.repository.save(AnnotationTaskView.create(task, view.cameraNumber(), view.cameraStreamId(),
						view.clipAssetId(), view.streamVersion(), view.clipAssetVersion(), view.clipAssetSha256(),
						view.mediaStatus(), now));
			}
			else if (resetOnInputChange || !Objects.equals(row.getCameraStreamId(), view.cameraStreamId())
					|| !Objects.equals(row.getClipAssetId(), view.clipAssetId())
					|| row.getCameraStreamVersion() != view.streamVersion()
					|| row.getClipAssetVersion() != view.clipAssetVersion()
					|| !Objects.equals(row.getSourceAssetSha256(), view.clipAssetSha256())
					|| !Objects.equals(row.getMediaStatus(), view.mediaStatus())) {
				row.refreshSource(view.cameraStreamId(), view.clipAssetId(), view.streamVersion(),
						view.clipAssetVersion(), view.clipAssetSha256(), view.mediaStatus(), now);
				this.repository.save(row);
			}
		}
	}

	List<AnnotationTaskCameraViewResponse> findAll(Collection<UUID> taskIds) {
		if (taskIds.isEmpty()) {
			return List.of();
		}
		return this.repository.findAllByAnnotationTaskIdInOrderByCameraNumberAsc(taskIds)
			.stream()
			.map(AnnotationTaskCameraViewResponse::from)
			.toList();
	}

	List<AnnotationTaskCameraViewResponse> findAll(UUID taskId) {
		return this.repository.findAllByAnnotationTaskIdOrderByCameraNumberAsc(taskId)
			.stream()
			.map(AnnotationTaskCameraViewResponse::from)
			.toList();
	}

	boolean update(AnnotationTask task, AnnotationTaskView view, String actorId, String idempotencyKey,
			AnnotationCameraViewUpdateCommand request, Instant now) {
		var normalizedSegments = normalizeSegments(request.segments());
		var normalizedNote = request.note() == null ? "" : request.note().trim();
		var reason = request.exclusionReason() == null || request.exclusionReason().isBlank() ? null
				: request.exclusionReason().trim();
		var requestSnapshot = new LinkedHashMap<String, Object>();
		requestSnapshot.put("expected_version", request.expectedVersion());
		requestSnapshot.put("expected_input_version", request.expectedInputVersion());
		requestSnapshot.put("state", request.state().name());
		requestSnapshot.put("segments", segmentJson(normalizedSegments));
		requestSnapshot.put("note", normalizedNote);
		requestSnapshot.put("exclusion_reason", reason);
		var previous = this.historyRepository.findByAnnotationTaskIdAndCameraNumberAndActorIdAndIdempotencyKey(
				task.getAnnotationTaskId(), view.getCameraNumber(), actorId, idempotencyKey);
		if (previous.isPresent()) {
			var storedRequest = previous.get().getViewSnapshot().get("request");
			if (!jsonValuesEqual(storedRequest, requestSnapshot)) {
				throw new AnnotationTaskException("ANNOTATION_CAMERA_VIEW_IDEMPOTENCY_KEY_REUSED",
						"The idempotency key was already used for a different camera view update.", 409);
			}
			return false;
		}
		if (task.getStatus() != AnnotationTaskStatus.ASSIGNED) {
			throw new AnnotationTaskException("ANNOTATION_TASK_NOT_EDITABLE",
					"Camera views can only be edited before the group revision is submitted.", 409);
		}
		if (request.expectedVersion() != view.getVersion()) {
			throw new AnnotationTaskException("ANNOTATION_CAMERA_VIEW_VERSION_CONFLICT",
					"This camera view changed since it was loaded. Refresh the task before saving again.", 409);
		}
		if (!Objects.equals(task.getGroupInputVersion(), request.expectedInputVersion())) {
			throw new AnnotationTaskException("ANNOTATION_CAMERA_VIEW_INPUT_VERSION_CONFLICT",
					"The CaptureGroup input changed since this camera view was loaded.", 409);
		}
		var oldState = view.getState();
		if (request.state() == AnnotationTaskViewState.PENDING) {
			if (oldState != AnnotationTaskViewState.EXCLUDED || !normalizedSegments.isEmpty() || reason != null
					|| !normalizedNote.isEmpty()) {
				throw new AnnotationTaskException("ANNOTATION_CAMERA_VIEW_STATE_INVALID",
						"Only an excluded camera view can be restored to PENDING, with its draft cleared.", 400);
			}
		}
		else if (oldState == AnnotationTaskViewState.EXCLUDED && request.state() != AnnotationTaskViewState.EXCLUDED) {
			throw new AnnotationTaskException("ANNOTATION_CAMERA_VIEW_RESTORE_REQUIRED",
					"An excluded camera view must be restored to PENDING before editing or submitting it again.", 409);
		}
		else if (request.state() == AnnotationTaskViewState.EXCLUDED) {
			if (reason == null || reason.length() > 1000 || !normalizedSegments.isEmpty()) {
				throw new AnnotationTaskException("ANNOTATION_CAMERA_VIEW_EXCLUSION_INVALID",
						"An excluded camera view needs a reason and cannot submit segments.", 400);
			}
		}
		else if (reason != null) {
			throw new AnnotationTaskException("ANNOTATION_CAMERA_VIEW_EXCLUSION_INVALID",
					"Only excluded camera views may have an exclusion reason.", 400);
		}
		validateSegmentStructure(normalizedSegments);
		if (!normalizedSegments.isEmpty()) {
			this.labelSchemaService.requireAllowedPrimaryStepLabels(task.getLabelSchemaId(),
					task.getLabelSchemaDigest(),
					normalizedSegments.stream().map(AnnotationCameraViewSegmentCommand::label).toList());
		}
		if (request.state() == AnnotationTaskViewState.SUBMITTED) {
			if (view.getClipAssetId() == null || !"INGESTED".equals(view.getMediaStatus())) {
				throw new AnnotationTaskException("ANNOTATION_CAMERA_VIEW_NOT_REVIEWABLE",
						"Only a present, ingested camera view can be submitted.", 409);
			}
			validateSourceCandidates(task, view, normalizedSegments);
			validateDuration(task.getProjectId(), view.getClipAssetId(), normalizedSegments);
		}
		var draft = new LinkedHashMap<String, Object>();
		draft.put("segments", segmentJson(normalizedSegments));
		draft.put("note", normalizedNote);
		view.update(request.state(), draft, reason, now);
		this.repository.saveAndFlush(view);
		var action = request.state() == AnnotationTaskViewState.SUBMITTED ? AnnotationTaskViewHistoryAction.SUBMITTED
				: request.state() == AnnotationTaskViewState.EXCLUDED ? AnnotationTaskViewHistoryAction.EXCLUDED
						: (oldState == AnnotationTaskViewState.EXCLUDED
								&& request.state() == AnnotationTaskViewState.PENDING)
										? AnnotationTaskViewHistoryAction.RESTORED
										: AnnotationTaskViewHistoryAction.DRAFT_SAVED;
		var historySnapshot = new LinkedHashMap<String, Object>();
		historySnapshot.put("request", requestSnapshot);
		historySnapshot.put("state", view.getState().name());
		historySnapshot.put("draft_document", view.getDraftDocument());
		historySnapshot.put("exclusion_reason", view.getExclusionReason());
		historySnapshot.put("view_version", view.getVersion());
		this.historyRepository
			.save(AnnotationTaskViewHistory.record(view, action, actorId, idempotencyKey, historySnapshot, now));
		return true;
	}

	void restoreAfterReview(AnnotationTask task, AnnotationRevision revision, List<Integer> cameraNumbers,
			String actorId, String reviewIdempotencyKey, Instant now) {
		var snapshots = revision.getAnnotationDocument().get("camera_views") instanceof List<?> rows ? rows : List.of();
		var snapshotsByCamera = snapshots.stream()
			.filter(Map.class::isInstance)
			.map(Map.class::cast)
			.filter(row -> row.get("camera_number") instanceof Number)
			.collect(java.util.stream.Collectors.toMap(row -> ((Number) row.get("camera_number")).intValue(),
					row -> row));
		var namespacedKey = "review:"
				+ UUID.nameUUIDFromBytes(reviewIdempotencyKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		for (var cameraNumber : cameraNumbers) {
			var snapshot = snapshotsByCamera.get(cameraNumber);
			if (snapshot == null || !Boolean.TRUE.equals(snapshot.get("included"))) {
				throw new AnnotationTaskException("ANNOTATION_REVIEW_CAMERA_SCOPE_INVALID",
						"Only submitted camera views can be restored for rework.", 400);
			}
			var view = this.repository.findForUpdate(task.getProjectId(), task.getAnnotationTaskId(), cameraNumber)
				.orElseThrow(() -> new AnnotationTaskException("ANNOTATION_CAMERA_VIEW_NOT_FOUND",
						"A camera in the submitted revision no longer exists.", 409));
			var segments = new ArrayList<Map<String, Object>>();
			if (snapshot.get("segments") instanceof List<?> rows) {
				for (var value : rows) {
					if (!(value instanceof Map<?, ?> row)) {
						throw new AnnotationTaskException("ANNOTATION_REVISION_DOCUMENT_INVALID",
								"A submitted camera segment is malformed.", 409);
					}
					var segment = new LinkedHashMap<String, Object>();
					for (var key : List.of("segment_id", "start_ms", "end_ms", "label", "source_candidate_ids")) {
						if (row.containsKey(key)) {
							segment.put(key, row.get(key));
						}
					}
					segments.add(java.util.Collections.unmodifiableMap(segment));
				}
			}
			var note = snapshot.get("note") instanceof String text ? text : "";
			var draft = new LinkedHashMap<String, Object>();
			draft.put("segments", List.copyOf(segments));
			draft.put("note", note);
			view.update(AnnotationTaskViewState.DRAFT, draft, null, now);
			this.repository.saveAndFlush(view);
			var snapshotDetails = new LinkedHashMap<String, Object>();
			snapshotDetails.put("request",
					Map.of("state", "DRAFT", "revision_id", revision.getAnnotationRevisionId().toString()));
			snapshotDetails.put("state", "DRAFT");
			snapshotDetails.put("draft_document", view.getDraftDocument());
			snapshotDetails.put("exclusion_reason", null);
			snapshotDetails.put("view_version", view.getVersion());
			this.historyRepository.save(AnnotationTaskViewHistory.record(view, AnnotationTaskViewHistoryAction.RESTORED,
					actorId, namespacedKey, snapshotDetails, now));
		}
	}

	private void validateSourceCandidates(AnnotationTask task, AnnotationTaskView view,
			List<AnnotationCameraViewSegmentCommand> segments) {
		var ids = segments.stream()
			.flatMap(segment -> segment.sourceCandidateIds().stream())
			.collect(java.util.stream.Collectors.toSet());
		if (ids.isEmpty()) {
			return;
		}
		var port = this.candidateSource.getIfAvailable();
		if (port == null) {
			throw new AnnotationTaskException("ANNOTATION_CANDIDATE_SOURCE_UNAVAILABLE",
					"Action candidate provenance cannot currently be verified.", 503);
		}
		var sources = port.findEligible(task.getProjectId(), task.getCaptureGroupId(), task.getAnnotationTaskId(),
				view.getClipAssetId(), task.getLabelSchemaId(), task.getLabelSchemaDigest(), ids);
		if (sources == null || !sources.keySet().equals(ids)) {
			throw invalidCandidateSource();
		}
		for (var segment : segments) {
			for (var candidateId : segment.sourceCandidateIds()) {
				var source = sources.get(candidateId);
				if (source == null || !Objects.equals(source.candidateId(), candidateId)
						|| !Objects.equals(source.clipAssetId(), view.getClipAssetId())
						|| !Objects.equals(source.labelSchemaId(), task.getLabelSchemaId())
						|| !Objects.equals(source.labelSchemaDigest(), task.getLabelSchemaDigest())) {
					throw invalidCandidateSource();
				}
			}
		}
	}

	private void validateDuration(UUID projectId, UUID clipAssetId, List<AnnotationCameraViewSegmentCommand> segments) {
		var duration = this.mediaDurationQueryService.findVerifiedDurationMicroseconds(projectId, clipAssetId);
		if (duration.isEmpty()) {
			throw new AnnotationTaskException("ANNOTATION_MEDIA_DURATION_UNVERIFIED",
					"The camera media duration must be verified before submitting annotations.", 409);
		}
		var maxMs = duration.getAsLong() / 1_000;
		if (segments.stream().anyMatch(segment -> segment.endMs() > maxMs)) {
			throw new AnnotationTaskException("ANNOTATION_SEGMENT_EXCEEDS_MEDIA_DURATION",
					"An annotation segment exceeds this camera's verified media duration.", 400);
		}
	}

	private static List<AnnotationCameraViewSegmentCommand> normalizeSegments(
			List<AnnotationCameraViewSegmentCommand> segments) {
		return segments == null ? List.of()
				: segments.stream()
					.map(segment -> new AnnotationCameraViewSegmentCommand(
							Objects.requireNonNull(segment.segmentId(), "segmentId"), segment.startMs(),
							segment.endMs(), segment.label() == null ? "" : segment.label().trim(),
							segment.sourceCandidateIds() == null ? List.of()
									: segment.sourceCandidateIds().stream().sorted().toList()))
					.toList();
	}

	private static void validateSegmentStructure(List<AnnotationCameraViewSegmentCommand> segments) {
		if (segments.size() > 200) {
			throw new AnnotationTaskException("ANNOTATION_CAMERA_VIEW_SEGMENT_LIMIT_EXCEEDED",
					"A camera view may contain at most 200 segments.", 400);
		}
		var ids = new HashSet<UUID>();
		for (var segment : segments) {
			if (segment.startMs() < 0 || segment.endMs() <= segment.startMs() || segment.label().isBlank()
					|| segment.label().length() > 64 || !ids.add(segment.segmentId())
					|| new HashSet<>(segment.sourceCandidateIds()).size() != segment.sourceCandidateIds().size()) {
				throw new AnnotationTaskException("ANNOTATION_CAMERA_VIEW_SEGMENT_INVALID",
						"Camera segments need unique IDs, positive local ranges and valid labels.", 400);
			}
		}
		var sorted = segments.stream()
			.sorted(Comparator.comparingLong(AnnotationCameraViewSegmentCommand::startMs)
				.thenComparingLong(AnnotationCameraViewSegmentCommand::endMs))
			.toList();
		for (var index = 1; index < sorted.size(); index += 1) {
			if (sorted.get(index).startMs() < sorted.get(index - 1).endMs()) {
				throw new AnnotationTaskException("ANNOTATION_CAMERA_VIEW_SEGMENT_OVERLAP",
						"Segments in one camera view cannot overlap.", 400);
			}
		}
	}

	private static List<Map<String, Object>> segmentJson(List<AnnotationCameraViewSegmentCommand> segments) {
		return segments.stream().map(segment -> {
			var value = new LinkedHashMap<String, Object>();
			value.put("segment_id", segment.segmentId().toString());
			value.put("start_ms", segment.startMs());
			value.put("end_ms", segment.endMs());
			value.put("label", segment.label());
			value.put("source_candidate_ids", segment.sourceCandidateIds().stream().map(UUID::toString).toList());
			return java.util.Collections.unmodifiableMap(value);
		}).toList();
	}

	private static Set<Integer> cameraNumbers(Map<String, Object> viewSet) {
		if (!(viewSet.get("views") instanceof List<?> views)) {
			throw sourceSnapshotInvalid();
		}
		var result = new java.util.TreeSet<Integer>();
		for (var item : views) {
			if (!(item instanceof Map<?, ?> view) || !(view.get("camera_number") instanceof Number number)
					|| !result.add(number.intValue())) {
				throw sourceSnapshotInvalid();
			}
		}
		return Set.copyOf(result);
	}

	private static AnnotationTaskException sourceSnapshotInvalid() {
		return new AnnotationTaskException("ANNOTATION_TASK_SOURCE_SNAPSHOT_INVALID",
				"The frozen CaptureGroup view set does not match its camera slots.", 409);
	}

	private static AnnotationTaskException invalidCandidateSource() {
		return new AnnotationTaskException("ANNOTATION_CANDIDATE_SOURCE_INVALID",
				"A source candidate is not valid for this camera, label schema or segment.", 400);
	}

	private static boolean jsonValuesEqual(Object left, Object right) {
		if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
			return new java.math.BigDecimal(leftNumber.toString())
				.compareTo(new java.math.BigDecimal(rightNumber.toString())) == 0;
		}
		if (left instanceof Map<?, ?> leftMap && right instanceof Map<?, ?> rightMap) {
			return leftMap.size() == rightMap.size() && leftMap.keySet()
				.stream()
				.allMatch(key -> rightMap.containsKey(key) && jsonValuesEqual(leftMap.get(key), rightMap.get(key)));
		}
		if (left instanceof List<?> leftList && right instanceof List<?> rightList) {
			return leftList.size() == rightList.size() && java.util.stream.IntStream.range(0, leftList.size())
				.allMatch(index -> jsonValuesEqual(leftList.get(index), rightList.get(index)));
		}
		return Objects.equals(left, right);
	}

}

record AnnotationCameraViewUpdateCommand(long expectedVersion, String expectedInputVersion,
		AnnotationTaskViewState state, List<AnnotationCameraViewSegmentCommand> segments, String note,
		String exclusionReason) {
}

record AnnotationCameraViewSegmentCommand(UUID segmentId, long startMs, long endMs, String label,
		List<UUID> sourceCandidateIds) {
}

record AnnotationTaskCameraViewResponse(UUID annotationTaskId, int cameraNumber, UUID cameraStreamId, UUID clipAssetId,
		String sourceAssetSha256, String mediaStatus, long cameraStreamVersion, long clipAssetVersion, long version,
		AnnotationTaskViewState state, List<Map<String, Object>> segments, String note, String exclusionReason) {

	static AnnotationTaskCameraViewResponse from(AnnotationTaskView view) {
		var segments = view.getDraftDocument().get("segments") instanceof List<?> values
				? values.stream().filter(Map.class::isInstance).map(value -> {
					@SuppressWarnings("unchecked")
					var row = (Map<String, Object>) value;
					return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(row));
				}).toList() : List.<Map<String, Object>>of();
		var note = view.getDraftDocument().get("note") instanceof String value ? value : "";
		return new AnnotationTaskCameraViewResponse(view.getAnnotationTaskId(), view.getCameraNumber(),
				view.getCameraStreamId(), view.getClipAssetId(), view.getSourceAssetSha256(), view.getMediaStatus(),
				view.getCameraStreamVersion(), view.getClipAssetVersion(), view.getVersion(), view.getState(), segments,
				note, view.getExclusionReason());
	}
}

record AnnotationTaskCameraViewProgress(int total, int pending, int draft, int submitted, int excluded) {
	static AnnotationTaskCameraViewProgress from(List<AnnotationTaskCameraViewResponse> views) {
		return new AnnotationTaskCameraViewProgress(views.size(), count(views, AnnotationTaskViewState.PENDING),
				count(views, AnnotationTaskViewState.DRAFT), count(views, AnnotationTaskViewState.SUBMITTED),
				count(views, AnnotationTaskViewState.EXCLUDED));
	}

	private static int count(List<AnnotationTaskCameraViewResponse> views, AnnotationTaskViewState state) {
		return (int) views.stream().filter(view -> view.state() == state).count();
	}
}
