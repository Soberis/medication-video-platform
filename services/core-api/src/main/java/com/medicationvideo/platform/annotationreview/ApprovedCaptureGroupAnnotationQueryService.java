package com.medicationvideo.platform.annotationreview;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.aicandidate.ActionCandidateSource;
import com.medicationvideo.platform.aicandidate.ActionCandidateSourceQueryPort;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService.CaptureGroupFacts;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService.CaptureGroupView;
import com.medicationvideo.platform.qualitycleaning.CaptureGroupQualityQueryService;
import com.medicationvideo.platform.qualitycleaning.QualityReviewQueryService;

/** Read-only, provenance-checked snapshot seam consumed by dataset export. */
@Service
public class ApprovedCaptureGroupAnnotationQueryService {

	private final CaptureGroupViewQueryService captureGroupViewQueryService;

	private final AnnotationTaskRepository taskRepository;

	private final AnnotationRevisionRepository revisionRepository;

	private final AnnotationReviewRepository reviewRepository;

	private final CaptureGroupQualityQueryService groupQualityQueryService;

	private final QualityReviewQueryService viewQualityQueryService;

	private final ObjectProvider<ActionCandidateSourceQueryPort> candidateSource;

	public ApprovedCaptureGroupAnnotationQueryService(CaptureGroupViewQueryService captureGroupViewQueryService,
			AnnotationTaskRepository taskRepository, AnnotationRevisionRepository revisionRepository,
			AnnotationReviewRepository reviewRepository, CaptureGroupQualityQueryService groupQualityQueryService,
			QualityReviewQueryService viewQualityQueryService,
			ObjectProvider<ActionCandidateSourceQueryPort> candidateSource) {
		this.captureGroupViewQueryService = captureGroupViewQueryService;
		this.taskRepository = taskRepository;
		this.revisionRepository = revisionRepository;
		this.reviewRepository = reviewRepository;
		this.groupQualityQueryService = groupQualityQueryService;
		this.viewQualityQueryService = viewQualityQueryService;
		this.candidateSource = candidateSource;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public List<ApprovedGroupSnapshot> findApproved(UUID projectId, List<UUID> captureGroupIds) {
		var selectedIds = captureGroupIds == null ? Set.<UUID>of()
				: captureGroupIds.stream().filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
		var allFacts = this.captureGroupViewQueryService.findAll(projectId);
		var factsByGroup = allFacts.stream()
			.collect(Collectors.toUnmodifiableMap(CaptureGroupFacts::captureGroupId, facts -> facts));
		if (!selectedIds.isEmpty() && !factsByGroup.keySet().containsAll(selectedIds)) {
			throw notEligible();
		}
		var tasks = this.taskRepository.findAllByProjectIdOrderByCreatedAtDescAnnotationTaskIdDesc(projectId)
			.stream()
			.filter(task -> selectedIds.isEmpty() || selectedIds.contains(task.getCaptureGroupId()))
			.toList();
		var tasksById = tasks.stream()
			.collect(Collectors.toUnmodifiableMap(AnnotationTask::getAnnotationTaskId, task -> task));
		if (tasks.isEmpty() && !selectedIds.isEmpty()) {
			throw notEligible();
		}
		var latestByTask = this.revisionRepository.findAllByAnnotationTaskIdIn(tasksById.keySet())
			.stream()
			.filter(revision -> {
				var task = tasksById.get(revision.getAnnotationTaskId());
				return task != null && task.isRevisionForCurrentGroupInput(revision)
						&& "annotation-document.v2".equals(revision.getAnnotationSchemaVersion());
			})
			.collect(Collectors.toMap(AnnotationRevision::getAnnotationTaskId, revision -> revision,
					(left, right) -> left.getRevisionNumber() >= right.getRevisionNumber() ? left : right));
		var revisionIds = latestByTask.values().stream().map(AnnotationRevision::getAnnotationRevisionId).toList();
		var reviews = revisionIds.isEmpty() ? Map.<UUID, AnnotationReview>of()
				: this.reviewRepository.findAllByAnnotationRevisionIdIn(revisionIds)
					.stream()
					.collect(Collectors.toUnmodifiableMap(AnnotationReview::getAnnotationRevisionId, review -> review));
		var groupQuality = this.groupQualityQueryService
			.getCurrentDecisions(projectId, allFacts.stream().map(CaptureGroupFacts::captureGroupId).toList())
			.stream()
			.collect(Collectors.toUnmodifiableMap(CaptureGroupQualityQueryService.CurrentDecision::captureGroupId,
					decision -> decision));
		var viewQuality = this.viewQualityQueryService.findCurrentDecisions(projectId,
				allFacts.stream()
					.flatMap(facts -> facts.views().stream())
					.map(CaptureGroupView::clipAssetId)
					.filter(Objects::nonNull)
					.distinct()
					.toList());
		var snapshots = new ArrayList<ApprovedGroupSnapshot>();
		for (var task : tasks) {
			var revision = latestByTask.get(task.getAnnotationTaskId());
			var review = revision == null ? null : reviews.get(revision.getAnnotationRevisionId());
			if (revision == null || review == null || review.getDecision() != AnnotationReviewDecision.APPROVED
					|| task.getStatus() != AnnotationTaskStatus.COMPLETED) {
				continue;
			}
			var facts = factsByGroup.get(task.getCaptureGroupId());
			var quality = groupQuality.get(task.getCaptureGroupId());
			if (facts == null || quality == null || !quality.isAdmitting()
					|| !Objects.equals(quality.inputVersion(), revision.getInputVersion())
					|| !Objects.equals(quality.confirmedViewSet(), revision.getConfirmedViewSet())
					|| !CaptureGroupAnnotationWorkflow.viewSetStillCurrent(revision.getConfirmedViewSet(), facts,
							viewQuality)) {
				throw notEligible();
			}
			var snapshot = toSnapshot(task, revision, review, facts);
			snapshots.add(snapshot);
		}
		if (!selectedIds.isEmpty() && !snapshots.stream()
			.map(ApprovedGroupSnapshot::captureGroupId)
			.collect(Collectors.toSet())
			.containsAll(selectedIds)) {
			throw notEligible();
		}
		return snapshots.stream()
			.sorted(Comparator.comparing(snapshot -> snapshot.captureGroupId().toString()))
			.toList();
	}

	private ApprovedGroupSnapshot toSnapshot(AnnotationTask task, AnnotationRevision revision, AnnotationReview review,
			CaptureGroupFacts facts) {
		var document = revision.getAnnotationDocument();
		if (!(document.get("camera_views") instanceof List<?> cameraRows)
				|| !(document.get("correspondences") instanceof List<?> correspondenceRows)) {
			throw notEligible();
		}
		var correspondences = correspondenceRows.stream()
			.map(ApprovedCaptureGroupAnnotationQueryService::correspondence)
			.toList();
		var correspondenceById = new HashMap<UUID, Correspondence>();
		for (var correspondence : correspondences) {
			if (correspondenceById.put(correspondence.correspondenceId(), correspondence) != null) {
				throw notEligible();
			}
		}
		var currentByCamera = facts.views()
			.stream()
			.collect(Collectors.toUnmodifiableMap(CaptureGroupView::cameraNumber, view -> view));
		var cameraViews = cameraRows.stream()
			.map(value -> cameraView(value, currentByCamera, correspondenceById, task, revision))
			.toList();
		var expectedCameraNumbers = cameraNumbers(revision.getConfirmedViewSet());
		var actualCameraNumbers = cameraViews.stream().map(CameraView::cameraNumber).collect(Collectors.toSet());
		if (expectedCameraNumbers.isEmpty() || cameraViews.size() != expectedCameraNumbers.size()
				|| !actualCameraNumbers.equals(expectedCameraNumbers)) {
			throw notEligible();
		}
		validateCorrespondenceIntegrity(correspondences, cameraViews);
		if (review.getRevisionNumber() != revision.getRevisionNumber()
				|| !Objects.equals(review.getCaptureGroupId(), revision.getCaptureGroupId())
				|| !Objects.equals(review.getInputVersion(), revision.getInputVersion())
				|| !Objects.equals(review.getConfirmedViewSet(), revision.getConfirmedViewSet())
				|| !Set.copyOf(review.getReviewedCameraNumbers()).equals(expectedCameraNumbers)
				|| !review.getReturnCameraNumbers().isEmpty()) {
			throw notEligible();
		}
		return new ApprovedGroupSnapshot(revision.getProjectId(), revision.getCaptureGroupId(),
				task.getAnnotationTaskId(), revision.getAnnotationRevisionId(), revision.getRevisionNumber(),
				revision.getLabelSchemaId(), revision.getLabelSchemaDigest(), revision.getDocumentSha256(),
				revision.getInputVersion(), cameraViews, correspondences);
	}

	private CameraView cameraView(Object value, Map<Integer, CaptureGroupView> currentByCamera,
			Map<UUID, Correspondence> correspondenceById, AnnotationTask task, AnnotationRevision revision) {
		if (!(value instanceof Map<?, ?> row) || !(row.get("camera_number") instanceof Number number)) {
			throw notEligible();
		}
		var cameraNumber = number.intValue();
		var current = currentByCamera.get(cameraNumber);
		var streamId = uuid(row.get("camera_stream_id"));
		var clipId = uuid(row.get("clip_asset_id"));
		var sha = text(row.get("clip_asset_sha256"));
		var streamVersion = number(row.get("camera_stream_version"));
		var clipVersion = number(row.get("clip_asset_version"));
		var disposition = text(row.get("disposition"));
		var included = Boolean.TRUE.equals(row.get("included"));
		if (!("SUBMITTED".equals(disposition) || "EXCLUDED".equals(disposition))
				|| included != "SUBMITTED".equals(disposition)) {
			throw notEligible();
		}
		if (current == null || !Objects.equals(current.cameraStreamId(), streamId)
				|| !Objects.equals(current.clipAssetId(), clipId) || current.streamVersion() != streamVersion
				|| current.clipAssetVersion() != clipVersion || !Objects.equals(current.clipAssetSha256(), sha)
				|| (included
						&& (!current.present() || !current.assetAvailable() || !"INGESTED".equals(current.mediaStatus())
								|| sha == null || !sha.matches("^[a-f0-9]{64}$")))) {
			throw notEligible();
		}
		var segmentRows = row.get("segments") instanceof List<?> rows ? rows : null;
		if (segmentRows == null) {
			throw notEligible();
		}
		for (var segmentValue : segmentRows) {
			if (!(segmentValue instanceof Map<?, ?>)) {
				throw notEligible();
			}
			var segmentDocument = (Map<?, ?>) segmentValue;
			if (!(segmentDocument.get("source_candidate_ids") instanceof List<?>)) {
				throw notEligible();
			}
		}
		var rawCandidateIds = segmentRows.stream()
			.filter(Map.class::isInstance)
			.map(Map.class::cast)
			.flatMap(candidateRow -> candidateRow.get("source_candidate_ids") instanceof List<?> ids ? ids.stream()
					: java.util.stream.Stream.empty())
			.map(ApprovedCaptureGroupAnnotationQueryService::uuid)
			.toList();
		if (rawCandidateIds.stream().anyMatch(Objects::isNull)) {
			throw notEligible();
		}
		var sources = resolveCandidateSources(task, revision, clipId, rawCandidateIds);
		var segments = segmentRows.stream()
			.map(segmentValue -> segment(segmentValue, cameraNumber, correspondenceById, sources))
			.toList();
		if (!included && !segments.isEmpty()) {
			throw notEligible();
		}
		var reason = text(row.get("exclusion_reason"));
		if ((!included && (reason == null || reason.isBlank())) || (included && reason != null)) {
			throw notEligible();
		}
		var orderedSegments = segments.stream()
			.sorted(Comparator.comparingLong(Segment::startMs).thenComparingLong(Segment::endMs))
			.toList();
		for (var index = 1; index < orderedSegments.size(); index += 1) {
			if (orderedSegments.get(index).startMs() < orderedSegments.get(index - 1).endMs()) {
				throw notEligible();
			}
		}
		return new CameraView(cameraNumber, streamId, clipId, sha, streamVersion, clipVersion, included, reason,
				segments);
	}

	private static Segment segment(Object value, int cameraNumber, Map<UUID, Correspondence> correspondences,
			Map<UUID, ActionCandidateSource> sources) {
		if (!(value instanceof Map<?, ?> row)) {
			throw notEligible();
		}
		var id = uuid(row.get("segment_id"));
		var start = number(row.get("start_ms"));
		var end = number(row.get("end_ms"));
		var label = text(row.get("label"));
		var correspondenceId = uuid(row.get("correspondence_id"));
		var correspondence = correspondences.get(correspondenceId);
		if (id == null || start < 0 || end <= start || label == null || label.isBlank() || correspondence == null
				|| !Objects.equals(text(row.get("correspondence_status")), correspondence.status())
				|| !correspondence.members()
					.stream()
					.anyMatch(member -> member.cameraNumber() == cameraNumber && id.equals(member.segmentId()))) {
			throw notEligible();
		}
		if (!(row.get("source_candidate_ids") instanceof List<?> ids)) {
			throw notEligible();
		}
		var candidateIds = ids.stream().map(ApprovedCaptureGroupAnnotationQueryService::uuid).toList();
		if (candidateIds.stream().anyMatch(Objects::isNull)
				|| candidateIds.stream().distinct().count() != candidateIds.size()) {
			throw notEligible();
		}
		for (var candidateId : candidateIds) {
			var source = sources.get(candidateId);
			if (source == null || !Objects.equals(source.label(), label) || source.startMs() < start
					|| source.endMs() > end) {
				throw notEligible();
			}
		}
		return new Segment(id, start, end, label, correspondenceId, correspondence.status(), candidateIds);
	}

	private Map<UUID, ActionCandidateSource> resolveCandidateSources(AnnotationTask task, AnnotationRevision revision,
			UUID clipAssetId, List<UUID> candidateIds) {
		if (candidateIds.isEmpty()) {
			return Map.of();
		}
		var port = this.candidateSource.getIfAvailable();
		if (port == null || clipAssetId == null) {
			throw notEligible();
		}
		var ids = Set.copyOf(candidateIds);
		var sources = port.findEligible(revision.getProjectId(), revision.getCaptureGroupId(),
				task.getAnnotationTaskId(), clipAssetId, revision.getLabelSchemaId(), revision.getLabelSchemaDigest(),
				ids);
		if (sources == null || !sources.keySet().equals(ids)
				|| sources.values()
					.stream()
					.anyMatch(source -> !Objects.equals(source.clipAssetId(), clipAssetId)
							|| !Objects.equals(source.labelSchemaId(), revision.getLabelSchemaId())
							|| !Objects.equals(source.labelSchemaDigest(), revision.getLabelSchemaDigest()))) {
			throw notEligible();
		}
		return sources;
	}

	private static Correspondence correspondence(Object value) {
		if (!(value instanceof Map<?, ?> row)) {
			throw notEligible();
		}
		var id = uuid(row.get("correspondence_id"));
		var status = text(row.get("status"));
		var reason = text(row.get("reason"));
		if (id == null || !("CONFIRMED".equals(status) || "UNCERTAIN".equals(status))
				|| ("UNCERTAIN".equals(status) && (reason == null || reason.isBlank() || reason.length() > 1000))) {
			throw notEligible();
		}
		var members = row.get("segments") instanceof List<?> rows ? rows.stream().map(item -> {
			if (!(item instanceof Map<?, ?> ref) || !(ref.get("camera_number") instanceof Number camera)) {
				throw notEligible();
			}
			var segmentId = uuid(ref.get("segment_id"));
			if (segmentId == null) {
				throw notEligible();
			}
			return new CorrespondenceMember(camera.intValue(), segmentId);
		}).toList() : List.<CorrespondenceMember>of();
		if (members.isEmpty() || members.stream()
			.map(member -> member.cameraNumber() + ":" + member.segmentId())
			.distinct()
			.count() != members.size()) {
			throw notEligible();
		}
		return new Correspondence(id, status, reason, members);
	}

	private static void validateCorrespondenceIntegrity(List<Correspondence> correspondences,
			List<CameraView> cameraViews) {
		var segmentsByKey = new HashMap<SegmentKey, Segment>();
		for (var view : cameraViews) {
			for (var segment : view.segments()) {
				if (segmentsByKey.put(new SegmentKey(view.cameraNumber(), segment.segmentId()), segment) != null) {
					throw notEligible();
				}
			}
		}
		var memberToCorrespondence = new HashMap<SegmentKey, UUID>();
		for (var correspondence : correspondences) {
			var cameraNumbers = new HashSet<Integer>();
			var labels = new HashSet<String>();
			for (var member : correspondence.members()) {
				var key = new SegmentKey(member.cameraNumber(), member.segmentId());
				var segment = segmentsByKey.get(key);
				if (segment == null || memberToCorrespondence.put(key, correspondence.correspondenceId()) != null
						|| !Objects.equals(segment.correspondenceId(), correspondence.correspondenceId())) {
					throw notEligible();
				}
				cameraNumbers.add(member.cameraNumber());
				labels.add(segment.label());
			}
			if ("CONFIRMED".equals(correspondence.status()) && (cameraNumbers.size() < 2
					|| cameraNumbers.size() != correspondence.members().size() || labels.size() != 1)) {
				throw notEligible();
			}
		}
		if (memberToCorrespondence.size() != segmentsByKey.size()) {
			throw notEligible();
		}
	}

	private static Set<Integer> cameraNumbers(Map<String, Object> viewSet) {
		if (!(viewSet.get("views") instanceof List<?> rows)) {
			throw notEligible();
		}
		var cameraNumbers = new HashSet<Integer>();
		for (var value : rows) {
			if (!(value instanceof Map<?, ?> row) || !(row.get("camera_number") instanceof Number camera)
					|| camera.intValue() < 1 || camera.intValue() > 64 || !cameraNumbers.add(camera.intValue())) {
				throw notEligible();
			}
		}
		return Set.copyOf(cameraNumbers);
	}

	private static UUID uuid(Object value) {
		try {
			return value == null ? null : UUID.fromString(value.toString());
		}
		catch (IllegalArgumentException exception) {
			throw notEligible();
		}
	}

	private static String text(Object value) {
		return value instanceof String text ? text : null;
	}

	private static long number(Object value) {
		return value instanceof Number number ? number.longValue() : -1;
	}

	private static AnnotationTaskException notEligible() {
		return new AnnotationTaskException("APPROVED_CAPTURE_GROUP_ANNOTATION_NOT_ELIGIBLE",
				"The selected CaptureGroup does not have a current, provenance-valid approved multicamera annotation.",
				409);
	}

	public record ApprovedGroupSnapshot(UUID projectId, UUID captureGroupId, UUID annotationTaskId,
			UUID annotationRevisionId, int revisionNumber, UUID labelSchemaId, String labelSchemaDigest,
			String documentSha256, String inputVersion, List<CameraView> cameraViews,
			List<Correspondence> correspondences) {
		public ApprovedGroupSnapshot {
			cameraViews = List.copyOf(cameraViews);
			correspondences = List.copyOf(correspondences);
		}
	}

	public record CameraView(int cameraNumber, UUID cameraStreamId, UUID clipAssetId, String clipAssetSha256,
			long cameraStreamVersion, long clipAssetVersion, boolean included, String exclusionReason,
			List<Segment> segments) {
		public CameraView {
			segments = List.copyOf(segments);
		}
	}

	public record Segment(UUID segmentId, long startMs, long endMs, String label, UUID correspondenceId,
			String correspondenceStatus, List<UUID> sourceCandidateIds) {
		public Segment {
			sourceCandidateIds = List.copyOf(sourceCandidateIds);
		}
	}

	public record Correspondence(UUID correspondenceId, String status, String reason,
			List<CorrespondenceMember> members) {
		public Correspondence {
			members = List.copyOf(members);
		}
	}

	public record CorrespondenceMember(int cameraNumber, UUID segmentId) {
	}

	private record SegmentKey(int cameraNumber, UUID segmentId) {
	}

}
