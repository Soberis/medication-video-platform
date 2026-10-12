package com.medicationvideo.platform.datasetexport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService;
import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService.CandidateAnnotationSnapshot;
import com.medicationvideo.platform.aicandidate.CandidateEvidenceQueryService.CandidateEvidence;
import com.medicationvideo.platform.annotationreview.ApprovedCaptureGroupAnnotationQueryService;
import com.medicationvideo.platform.annotationreview.ApprovedCaptureGroupAnnotationQueryService.ApprovedGroupSnapshot;
import com.medicationvideo.platform.annotationreview.ApprovedCaptureGroupAnnotationQueryService.CameraView;
import com.medicationvideo.platform.annotationreview.ApprovedCaptureGroupAnnotationQueryService.Correspondence;
import com.medicationvideo.platform.annotationreview.ApprovedCaptureGroupAnnotationQueryService.CorrespondenceMember;
import com.medicationvideo.platform.annotationreview.ApprovedCaptureGroupAnnotationQueryService.Segment;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService.CaptureGroupFacts;
import com.medicationvideo.platform.assetcapture.CaptureGroupViewQueryService.CaptureGroupView;

import io.jhdf.HdfFile;
import io.jhdf.api.WritableGroup;
import tools.jackson.databind.JsonNode;

/**
 * Builds compact HDF5 interchange files from already approved human labels and current
 * MediaPipe facts.
 */
@Service
class HumanDemonstrationHdf5ExportService {

	static final String SCHEMA_VERSION = "human-demonstration-hdf5.v2";

	static final String MEDIA_TYPE = "application/x-hdf5";

	private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
		.withZone(ZoneOffset.UTC);

	private static final String SHA256_PATTERN = "^[a-f0-9]{64}$";

	private final ApprovedCaptureGroupAnnotationQueryService approvedCaptureGroupAnnotationQueryService;

	private final CaptureGroupViewQueryService captureGroupViewQueryService;

	private final CandidateEvidenceQueryService candidateEvidenceQueryService;

	private final String dataProfile;

	HumanDemonstrationHdf5ExportService(
			ApprovedCaptureGroupAnnotationQueryService approvedCaptureGroupAnnotationQueryService,
			CaptureGroupViewQueryService captureGroupViewQueryService,
			CandidateEvidenceQueryService candidateEvidenceQueryService,
			@Value("${platform.training-data-export.data-profile:disabled}") String dataProfile) {
		this.approvedCaptureGroupAnnotationQueryService = approvedCaptureGroupAnnotationQueryService;
		this.captureGroupViewQueryService = captureGroupViewQueryService;
		this.candidateEvidenceQueryService = candidateEvidenceQueryService;
		this.dataProfile = dataProfile == null ? "" : dataProfile.trim().toLowerCase(Locale.ROOT);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	Path download(UUID projectId, List<UUID> captureGroupIds) {
		return download(projectId, captureGroupIds, ExportOptions.DEFAULT);
	}

	@FunctionalInterface
	interface ProgressCallback {

		void onProgress(int current, int total, String currentLabel);

	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	Path download(UUID projectId, List<UUID> captureGroupIds, ExportOptions options) {
		return download(projectId, captureGroupIds, options, null);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	Path download(UUID projectId, List<UUID> captureGroupIds, ExportOptions options,
			ProgressCallback progressCallback) {
		requireSyntheticProfile();
		var selectedIds = captureGroupIds == null ? List.<UUID>of() : captureGroupIds.stream().distinct().toList();
		var snapshots = this.approvedCaptureGroupAnnotationQueryService.findApproved(projectId, selectedIds);
		if (snapshots.isEmpty()) {
			throw unavailable("No current approved human annotation is ready for HDF5 export.");
		}
		var snapshotsByGroup = snapshots.stream()
			.collect(Collectors.toMap(ApprovedGroupSnapshot::captureGroupId, snapshot -> snapshot, (left, right) -> {
				throw invalid("The approved annotation query returned duplicate CaptureGroups.");
			}));
		if (!selectedIds.isEmpty() && !snapshotsByGroup.keySet().containsAll(selectedIds)) {
			throw unavailable("One or more selected CaptureGroups are no longer eligible for HDF5 export.");
		}
		var groups = snapshotsByGroup.keySet()
			.stream()
			.sorted(Comparator.comparing(UUID::toString))
			.map(groupId -> new ExportGroup(snapshotsByGroup.get(groupId),
					this.captureGroupViewQueryService.get(projectId, groupId)))
			.toList();
		Path output = null;
		try {
			output = Files.createTempFile("human-demonstration-", ".hdf5");
			try (var file = HdfFile.write(output)) {
				file.putAttribute("schema_version", SCHEMA_VERSION);
				file.putAttribute("project_id", projectId.toString());
				file.putAttribute("export_scope", selectedIds.isEmpty() ? "project" : "selection");
				file.putAttribute("contains_mp4_bytes", false);
				file.putAttribute("contains_robot_actions", false);
				file.putAttribute("contains_raw_candidates", options.includeRawCandidates());
				var data = file.putGroup("data");
				var total = groups.size();
				if (progressCallback != null) {
					progressCallback.onProgress(0, total, "正在准备写入 HDF5 数据集...");
				}
				for (var index = 0; index < total; index += 1) {
					var exportGroup = groups.get(index);
					var label = (exportGroup.snapshot() != null && exportGroup.snapshot().captureGroupId() != null)
							? "demo_%06d (%s)".formatted(index,
									exportGroup.snapshot().captureGroupId().toString().substring(0, 8))
							: "demo_%06d".formatted(index);
					if (progressCallback != null) {
						progressCallback.onProgress(index, total, label);
					}
					writeEpisode(data.putGroup("demo_%06d".formatted(index)), exportGroup, projectId, options);
					if (progressCallback != null) {
						progressCallback.onProgress(index + 1, total, label);
					}
				}
			}
			return output;
		}
		catch (IOException exception) {
			if (output != null) {
				try {
					Files.deleteIfExists(output);
				}
				catch (IOException ignored) {
				}
			}
			throw new TrainingDataExportException("HDF5_EXPORT_FAILED", "The HDF5 export could not be created.", 500);
		}
	}

	static String fileName(List<UUID> captureGroupIds, Instant generatedAt) {
		var scope = captureGroupIds == null || captureGroupIds.isEmpty() ? "project" : captureGroupIds.size() == 1
				? "event_" + captureGroupIds.getFirst().toString().substring(0, 8) : "selection";
		return "human_demonstration_hdf5_v2_%s_%s.hdf5".formatted(scope, FILE_TIMESTAMP.format(generatedAt));
	}

	private void writeEpisode(WritableGroup episode, ExportGroup exportGroup, UUID projectId, ExportOptions options) {
		var snapshot = exportGroup.snapshot();
		var group = exportGroup.facts();
		if (group == null || snapshot == null || !projectId.equals(group.projectId())
				|| !projectId.equals(snapshot.projectId()) || !group.captureGroupId().equals(snapshot.captureGroupId())
				|| group.views() == null || snapshot.cameraViews() == null) {
			throw invalid("The current CaptureGroup projection is incomplete.");
		}
		var cameraViews = snapshot.cameraViews()
			.stream()
			.sorted(Comparator.comparingInt(CameraView::cameraNumber))
			.toList();
		if (cameraViews.stream().map(CameraView::cameraNumber).distinct().count() != cameraViews.size()) {
			throw invalid("The approved revision contains duplicate camera slots.");
		}
		var includedViews = cameraViews.stream().filter(CameraView::included).toList();
		if (includedViews.isEmpty()) {
			throw invalid("The approved revision has no participating camera.");
		}
		if (includedViews.stream().anyMatch(view -> view.segments() == null || view.segments().isEmpty())) {
			throw invalid("An included camera must have at least one approved annotation segment.");
		}
		var currentViews = group.views()
			.stream()
			.collect(Collectors.toMap(CaptureGroupView::cameraNumber, view -> view, (left, right) -> {
				throw invalid("The current CaptureGroup projection contains duplicate camera slots.");
			}));
		var sources = includedViews.stream().map(view -> currentSource(view, currentViews)).toList();
		var evidenceByAsset = this.candidateEvidenceQueryService
			.findByClipAssets(projectId, sources.stream().map(CaptureGroupView::clipAssetId).toList())
			.stream()
			.collect(Collectors.toMap(CandidateEvidence::clipAssetId, evidence -> evidence, (left, right) -> {
				throw invalid("The current MediaPipe evidence contains duplicate ClipAsset entries.");
			}));
		var samplesByCamera = new LinkedHashMap<Integer, CameraSamples>();
		var orderedSegments = cameraViews.stream().filter(view -> {
			if (!view.included() && !view.segments().isEmpty()) {
				throw invalid("An excluded camera cannot contribute approved annotation segments.");
			}
			return view.included();
		})
			.flatMap(view -> view.segments().stream().map(segment -> new ExportSegment(view.cameraNumber(), segment)))
			.sorted(Comparator.comparingInt(ExportSegment::cameraNumber)
				.thenComparingLong(row -> row.segment().startMs())
				.thenComparingLong(row -> row.segment().endMs()))
			.toList();
		var segmentsByCamera = orderedSegments.stream()
			.collect(Collectors.groupingBy(ExportSegment::cameraNumber, LinkedHashMap::new,
					Collectors.mapping(ExportSegment::segment, Collectors.toList())));
		validateCorrespondences(snapshot.correspondences(), orderedSegments);
		for (var index = 0; index < includedViews.size(); index += 1) {
			var annotationView = includedViews.get(index);
			var sourceView = sources.get(index);
			var evidence = evidenceByAsset.get(sourceView.clipAssetId());
			validateEvidence(evidence, sourceView);
			var cameraSegments = segmentsByCamera.getOrDefault(annotationView.cameraNumber(), List.of());
			var labeledSamples = evidence.candidates()
				.stream()
				.sorted(Comparator.comparingLong(CandidateAnnotationSnapshot::frameTimestampMs)
					.thenComparingInt(CandidateAnnotationSnapshot::handIndex)
					.thenComparing(candidate -> candidate.candidateAnnotationId().toString()))
				.map(candidate -> new LabeledSample(candidate, segmentAt(cameraSegments, candidate.frameTimestampMs()),
						annotationView.cameraNumber()))
				.filter(row -> row.segment() != null)
				.toList();
			if (labeledSamples.stream()
				.anyMatch(row -> !projectId.equals(row.candidate().projectId())
						|| !sourceView.clipAssetId().equals(row.candidate().clipAssetId())
						|| !evidence.aiJobId().equals(row.candidate().aiJobId()))) {
				throw invalid("A labeled MediaPipe sample does not match its approved camera source.");
			}
			samplesByCamera.put(annotationView.cameraNumber(),
					new CameraSamples(annotationView, sourceView, evidence, labeledSamples));
		}
		var allRows = samplesByCamera.values().stream().flatMap(view -> view.rows().stream()).toList();
		if (allRows.isEmpty()) {
			throw unavailable("The approved annotation has no MediaPipe hand samples inside its labeled intervals.");
		}
		episode.putAttribute("capture_group_id", group.captureGroupId().toString());
		episode.putAttribute("annotation_revision_id", snapshot.annotationRevisionId().toString());
		episode.putAttribute("revision_number", snapshot.revisionNumber());
		episode.putAttribute("label_schema_id", snapshot.labelSchemaId().toString());
		episode.putAttribute("label_schema_digest", snapshot.labelSchemaDigest());
		episode.putAttribute("document_sha256", snapshot.documentSha256());
		episode.putAttribute("input_version", snapshot.inputVersion());
		episode.putAttribute("included_camera_count", includedViews.size());
		writeAnnotations(episode.putGroup("annotations"), snapshot, orderedSegments);
		writeLabeledSampleTable(episode, samplesByCamera, allRows, options);
		if (options.includeRawCandidates()) {
			var viewGroup = episode.putGroup("views");
			for (var camera : samplesByCamera.values()) {
				writeView(viewGroup.putGroup("camera_%02d".formatted(camera.annotationView().cameraNumber())),
						camera.annotationView(), camera.sourceView(), camera.evidence(), projectId, options);
			}
		}
	}

	private static CaptureGroupView currentSource(CameraView annotationView,
			Map<Integer, CaptureGroupView> currentViews) {
		var source = currentViews.get(annotationView.cameraNumber());
		if (source == null || annotationView.cameraStreamId() == null || annotationView.clipAssetId() == null
				|| !annotationView.cameraStreamId().equals(source.cameraStreamId())
				|| !annotationView.clipAssetId().equals(source.clipAssetId())
				|| annotationView.cameraStreamVersion() != source.streamVersion()
				|| annotationView.clipAssetVersion() != source.clipAssetVersion()
				|| annotationView.clipAssetSha256() == null || !annotationView.clipAssetSha256().matches(SHA256_PATTERN)
				|| !annotationView.clipAssetSha256().equals(source.clipAssetSha256()) || !source.present()
				|| !source.assetAvailable() || !source.enabledForHandDetection()
				|| !"INGESTED".equals(source.mediaStatus())) {
			throw invalid("An approved camera source no longer matches the current CaptureGroup view.");
		}
		return source;
	}

	private static void validateEvidence(CandidateEvidence evidence, CaptureGroupView source) {
		if (evidence == null || !evidence.provenanceValid()
				|| !CandidateEvidenceQueryService.EXECUTION_STATUS_SUCCEEDED_WITH_CANDIDATES
					.equals(evidence.executionStatus())
				|| evidence.aiJobId() == null || evidence.aiJobAttemptId() == null || evidence.attempt() == null
				|| evidence.inputSha256() == null || evidence.profileSha256() == null || evidence.resultSha256() == null
				|| evidence.algorithmKey() == null || evidence.algorithmVersion() == null
				|| evidence.modelSha256() == null || !evidence.inputSha256().matches(SHA256_PATTERN)
				|| !evidence.profileSha256().matches(SHA256_PATTERN) || !evidence.resultSha256().matches(SHA256_PATTERN)
				|| !evidence.modelSha256().matches(SHA256_PATTERN)
				|| !source.clipAssetId().equals(evidence.clipAssetId())
				|| !source.clipAssetSha256().equals(evidence.clipAssetSha256())) {
			throw invalid("An included camera has no current provenance-verified MediaPipe candidates.");
		}
	}

	private static void writeAnnotations(WritableGroup annotations, ApprovedGroupSnapshot snapshot,
			List<ExportSegment> segments) {
		annotations.putAttribute("annotation_revision_id", snapshot.annotationRevisionId().toString());
		annotations.putAttribute("document_sha256", snapshot.documentSha256());
		annotations.putDataset("camera_number", segments.stream().mapToInt(ExportSegment::cameraNumber).toArray());
		annotations.putDataset("segment_id",
				segments.stream().map(row -> row.segment().segmentId().toString()).toArray(String[]::new));
		annotations.putDataset("segment_start_ms",
				segments.stream().mapToLong(row -> row.segment().startMs()).toArray());
		annotations.putDataset("segment_end_ms", segments.stream().mapToLong(row -> row.segment().endMs()).toArray());
		annotations.putDataset("label", segments.stream().map(row -> row.segment().label()).toArray(String[]::new));
		var actionCandidateIds = new ArrayList<String>();
		var actionCandidateSegmentIndices = new ArrayList<Integer>();
		for (var index = 0; index < segments.size(); index += 1) {
			for (var candidateId : segments.get(index).segment().sourceCandidateIds()) {
				actionCandidateIds.add(candidateId.toString());
				actionCandidateSegmentIndices.add(index);
			}
		}
		if (!actionCandidateIds.isEmpty()) {
			annotations.putDataset("source_candidate_id", actionCandidateIds.toArray(String[]::new));
			annotations.putDataset("source_candidate_segment_index",
					actionCandidateSegmentIndices.stream().mapToInt(Integer::intValue).toArray());
		}
		var correspondences = annotations.putGroup("correspondences");
		correspondences.putDataset("correspondence_id",
				snapshot.correspondences()
					.stream()
					.map(row -> row.correspondenceId().toString())
					.toArray(String[]::new));
		correspondences.putDataset("status",
				snapshot.correspondences()
					.stream()
					.map(ApprovedCaptureGroupAnnotationQueryService.Correspondence::status)
					.toArray(String[]::new));
		correspondences.putDataset("reason",
				snapshot.correspondences()
					.stream()
					.map(row -> row.reason() == null ? "" : row.reason())
					.toArray(String[]::new));
		var correspondenceMembers = snapshot.correspondences()
			.stream()
			.flatMap(correspondence -> correspondence.members()
				.stream()
				.map(member -> new CorrespondenceLink(correspondence.correspondenceId(), member)))
			.toList();
		correspondences.putDataset("member_correspondence_id",
				correspondenceMembers.stream().map(row -> row.correspondenceId().toString()).toArray(String[]::new));
		correspondences.putDataset("camera_number",
				correspondenceMembers.stream().mapToInt(row -> row.member().cameraNumber()).toArray());
		correspondences.putDataset("segment_id",
				correspondenceMembers.stream().map(row -> row.member().segmentId().toString()).toArray(String[]::new));
	}

	private static void validateCorrespondences(List<Correspondence> correspondences, List<ExportSegment> segments) {
		var segmentsByKey = segments.stream()
			.collect(Collectors.toUnmodifiableMap(row -> segmentKey(row.cameraNumber(), row.segment().segmentId()),
					ExportSegment::segment, (left, right) -> {
						throw invalid("The approved revision contains duplicate camera segment identifiers.");
					}));
		var correspondencesById = correspondences.stream()
			.collect(Collectors.toUnmodifiableMap(Correspondence::correspondenceId, value -> value, (left, right) -> {
				throw invalid("The approved revision contains duplicate correspondence identifiers.");
			}));
		var correspondenceMemberKeys = new HashSet<String>();
		for (var correspondence : correspondences) {
			if (!"CONFIRMED".equals(correspondence.status()) && !"UNCERTAIN".equals(correspondence.status())) {
				throw invalid("The approved revision contains an unknown correspondence status.");
			}
			if ("UNCERTAIN".equals(correspondence.status())
					&& (correspondence.reason() == null || correspondence.reason().isBlank())) {
				throw invalid("An uncertain action correspondence has no reason.");
			}
			var cameraNumbers = new HashSet<Integer>();
			for (var member : correspondence.members()) {
				var memberKey = segmentKey(member.cameraNumber(), member.segmentId());
				var segment = segmentsByKey.get(memberKey);
				if (segment == null || !correspondence.correspondenceId().equals(segment.correspondenceId())
						|| !correspondence.status().equals(segment.correspondenceStatus())
						|| !correspondenceMemberKeys.add(memberKey)) {
					throw invalid("An action correspondence member does not match its approved camera segment.");
				}
				cameraNumbers.add(member.cameraNumber());
			}
			if ("CONFIRMED".equals(correspondence.status()) && cameraNumbers.size() < 2) {
				throw invalid("A confirmed action correspondence must cover at least two cameras.");
			}
		}
		for (var row : segments) {
			var correspondenceId = row.segment().correspondenceId();
			if (correspondenceId == null) {
				throw invalid("An approved camera segment has no explicit correspondence disposition.");
			}
			if (!correspondencesById.containsKey(correspondenceId)
					|| !correspondenceMemberKeys.contains(segmentKey(row.cameraNumber(), row.segment().segmentId()))) {
				throw invalid("A camera segment references a missing action correspondence.");
			}
		}
	}

	private static String segmentKey(int cameraNumber, UUID segmentId) {
		return cameraNumber + ":" + segmentId;
	}

	private static void writeLabeledSampleTable(WritableGroup episode, Map<Integer, CameraSamples> samplesByCamera,
			List<LabeledSample> allRows, ExportOptions options) {
		var samples = episode.putGroup("samples");
		var sources = samples.putGroup("sources");
		for (var camera : samplesByCamera.values()) {
			var view = camera.annotationView();
			var evidence = camera.evidence();
			var source = sources.putGroup("camera_%02d".formatted(view.cameraNumber()));
			source.putAttribute("camera_number", view.cameraNumber());
			source.putAttribute("camera_stream_id", view.cameraStreamId().toString());
			source.putAttribute("camera_stream_version", view.cameraStreamVersion());
			source.putAttribute("clip_asset_id", view.clipAssetId().toString());
			source.putAttribute("clip_asset_version", view.clipAssetVersion());
			source.putAttribute("clip_asset_sha256", view.clipAssetSha256());
			source.putAttribute("ai_job_id", evidence.aiJobId().toString());
			source.putAttribute("ai_job_attempt_id", evidence.aiJobAttemptId().toString());
			source.putAttribute("attempt", evidence.attempt());
			source.putAttribute("algorithm_key", evidence.algorithmKey());
			source.putAttribute("algorithm_version", evidence.algorithmVersion());
			source.putAttribute("model_sha256", evidence.modelSha256());
			source.putAttribute("profile_sha256", evidence.profileSha256());
			source.putAttribute("input_sha256", evidence.inputSha256());
			source.putAttribute("result_sha256", evidence.resultSha256());
		}
		if (options.tensorLayout()) {
			writeV2TensorSamples(samples, allRows);
		}
		else {
			writeV2TableSamples(samples, allRows);
		}
	}

	private static void writeV2TensorSamples(WritableGroup samples, List<LabeledSample> rows) {
		samples.putAttribute("layout", "dual-hand-tensor.v2");

		var rowsByCameraAndTimestamp = rows.stream()
			.collect(Collectors.groupingBy(
					row -> new CameraTimestamp(row.cameraNumber(), row.candidate().frameTimestampMs()),
					LinkedHashMap::new, Collectors.toList()));

		int stepCount = rowsByCameraAndTimestamp.size();
		samples.putAttribute("step_count", stepCount);

		var cameraNumbers = new int[stepCount];
		var frameTimestamps = new long[stepCount];
		var segmentStarts = new long[stepCount];
		var segmentEnds = new long[stepCount];
		var labels = new String[stepCount];
		var imageWidths = new int[stepCount];
		var imageHeights = new int[stepCount];
		var landmarks = new float[stepCount][2][21][3];
		var handMask = new int[stepCount][2];
		var handednessScore = new double[stepCount][2];
		var candidateIdsByStep = new ArrayList<Map<Integer, String>>(stepCount);

		int index = 0;
		for (var entry : rowsByCameraAndTimestamp.entrySet()) {
			var cameraTimestamp = entry.getKey();
			long timestampMs = cameraTimestamp.timestampMs();
			var candidatesAtTimestamp = entry.getValue();
			cameraNumbers[index] = cameraTimestamp.cameraNumber();
			frameTimestamps[index] = timestampMs;
			var segment = candidatesAtTimestamp.getFirst().segment();
			segmentStarts[index] = segment.startMs();
			segmentEnds[index] = segment.endMs();
			labels[index] = segment.label();
			imageWidths[index] = candidatesAtTimestamp.getFirst().candidate().imageWidth();
			imageHeights[index] = candidatesAtTimestamp.getFirst().candidate().imageHeight();
			var frameWidth = imageWidths[index];
			var frameHeight = imageHeights[index];
			if (candidatesAtTimestamp.stream()
				.anyMatch(row -> row.candidate().imageWidth() != frameWidth
						|| row.candidate().imageHeight() != frameHeight)) {
				throw invalid("Candidates in one camera frame have inconsistent image dimensions.");
			}
			var candidateIdsByHand = new HashMap<Integer, String>();

			for (var sample : candidatesAtTimestamp) {
				var cand = sample.candidate();
				int slot = resolveHandSlot(cand);
				double score = cand.handednessScore() != null ? cand.handednessScore().doubleValue() : 0.0;
				if (handMask[index][slot] == 0 || score > handednessScore[index][slot]) {
					handMask[index][slot] = 1;
					handednessScore[index][slot] = score;
					copyLandmarks(cand, landmarks[index][slot]);
					candidateIdsByHand.put(slot, cand.candidateAnnotationId().toString());
				}
			}
			candidateIdsByStep.add(Map.copyOf(candidateIdsByHand));
			index += 1;
		}

		samples.putDataset("camera_number", cameraNumbers);
		samples.putDataset("frame_timestamp_ms", frameTimestamps);
		samples.putDataset("segment_start_ms", segmentStarts);
		samples.putDataset("segment_end_ms", segmentEnds);
		samples.putDataset("image_width", imageWidths);
		samples.putDataset("image_height", imageHeights);
		samples.putDataset("label", labels);
		samples.putDataset("landmarks", landmarks);
		samples.putDataset("hand_mask", handMask);
		samples.putDataset("handedness_score", handednessScore);
		writeTensorCandidateProvenance(samples, candidateIdsByStep);
	}

	private static void writeV2TableSamples(WritableGroup samples, List<LabeledSample> rows) {
		samples.putAttribute("layout", "columnar-table.v2");
		samples.putAttribute("row_count", rows.size());

		var landmarks = new float[rows.size()][21][3];
		for (var index = 0; index < rows.size(); index += 1) {
			copyLandmarks(rows.get(index).candidate(), landmarks[index]);
		}
		samples.putDataset("candidate_annotation_id",
				rows.stream().map(row -> row.candidate().candidateAnnotationId().toString()).toArray(String[]::new));
		samples.putDataset("camera_number", rows.stream().mapToInt(LabeledSample::cameraNumber).toArray());
		samples.putDataset("frame_timestamp_ms",
				rows.stream().mapToLong(row -> row.candidate().frameTimestampMs()).toArray());
		samples.putDataset("segment_start_ms", rows.stream().mapToLong(row -> row.segment().startMs()).toArray());
		samples.putDataset("segment_end_ms", rows.stream().mapToLong(row -> row.segment().endMs()).toArray());
		samples.putDataset("hand_index", rows.stream().mapToInt(row -> row.candidate().handIndex()).toArray());
		samples.putDataset("handedness", rows.stream().map(row -> row.candidate().handedness()).toArray(String[]::new));
		samples.putDataset("handedness_score",
				rows.stream()
					.mapToDouble(row -> row.candidate().handednessScore() == null ? 0.0
							: row.candidate().handednessScore().doubleValue())
					.toArray());
		samples.putDataset("image_width", rows.stream().mapToInt(row -> row.candidate().imageWidth()).toArray());
		samples.putDataset("image_height", rows.stream().mapToInt(row -> row.candidate().imageHeight()).toArray());
		samples.putDataset("label", rows.stream().map(row -> row.segment().label()).toArray(String[]::new));
		samples.putDataset("landmarks", landmarks);
	}

	private static void writeTensorCandidateProvenance(WritableGroup samples, List<Map<Integer, String>> idsByStep) {
		var stepIndices = new java.util.ArrayList<Integer>();
		var handIndices = new java.util.ArrayList<Integer>();
		var candidateIds = new java.util.ArrayList<String>();
		for (var step = 0; step < idsByStep.size(); step += 1) {
			for (var candidate : idsByStep.get(step).entrySet()) {
				stepIndices.add(step);
				handIndices.add(candidate.getKey());
				candidateIds.add(candidate.getValue());
			}
		}
		var provenance = samples.putGroup("candidate_provenance");
		provenance.putDataset("step_index", stepIndices.stream().mapToInt(Integer::intValue).toArray());
		provenance.putDataset("hand_index", handIndices.stream().mapToInt(Integer::intValue).toArray());
		provenance.putDataset("candidate_annotation_id", candidateIds.toArray(String[]::new));
	}

	private static int resolveHandSlot(CandidateAnnotationSnapshot candidate) {
		if (candidate.handedness() != null) {
			if ("LEFT".equalsIgnoreCase(candidate.handedness())) {
				return 0;
			}
			if ("RIGHT".equalsIgnoreCase(candidate.handedness())) {
				return 1;
			}
		}
		return candidate.handIndex() == 0 ? 0 : 1;
	}

	private static Segment segmentAt(List<Segment> segments, long timestampMs) {
		return segments.stream()
			.filter(segment -> timestampMs >= segment.startMs() && timestampMs < segment.endMs())
			.findFirst()
			.orElse(null);
	}

	private static void writeView(WritableGroup output, CameraView annotationView, CaptureGroupView view,
			CandidateEvidence evidence, UUID projectId, ExportOptions options) {
		if (view.cameraStreamId() == null || view.clipAssetId() == null || view.cameraNumber() < 1
				|| view.clipAssetSha256() == null || !view.clipAssetSha256().matches("^[a-f0-9]{64}$")
				|| evidence == null || !evidence.provenanceValid() || !view.clipAssetId().equals(evidence.clipAssetId())
				|| !view.clipAssetSha256().equals(evidence.clipAssetSha256()) || evidence.executionStatus() == null) {
			throw invalid("The current MediaPipe evidence cannot be linked to its source video.");
		}
		output.putAttribute("camera_stream_id", view.cameraStreamId().toString());
		output.putAttribute("camera_number", view.cameraNumber());
		output.putAttribute("camera_stream_version", annotationView.cameraStreamVersion());
		output.putAttribute("clip_asset_id", view.clipAssetId().toString());
		output.putAttribute("clip_asset_version", annotationView.clipAssetVersion());
		output.putAttribute("clip_asset_sha256", view.clipAssetSha256());
		output.putAttribute("detection_status", evidence.executionStatus());
		if (!CandidateEvidenceQueryService.EXECUTION_STATUS_SUCCEEDED_WITH_CANDIDATES
			.equals(evidence.executionStatus())) {
			return;
		}
		if (evidence.aiJobId() == null || evidence.aiJobAttemptId() == null || evidence.attempt() == null
				|| evidence.inputSha256() == null || evidence.resultSha256() == null) {
			throw invalid("The successful MediaPipe provenance is incomplete.");
		}
		output.putAttribute("ai_job_id", evidence.aiJobId().toString());
		output.putAttribute("ai_job_attempt_id", evidence.aiJobAttemptId().toString());
		output.putAttribute("attempt", evidence.attempt());
		output.putAttribute("algorithm_key", evidence.algorithmKey());
		output.putAttribute("algorithm_version", evidence.algorithmVersion());
		output.putAttribute("model_sha256", evidence.modelSha256());
		output.putAttribute("profile_sha256", evidence.profileSha256());
		output.putAttribute("input_sha256", evidence.inputSha256());
		output.putAttribute("result_sha256", evidence.resultSha256());

		if (!options.includeRawCandidates()) {
			return;
		}

		var candidates = evidence.candidates();
		if (candidates.isEmpty()) {
			throw invalid("A successful MediaPipe hand result has no candidate samples.");
		}
		if (candidates.stream()
			.anyMatch(candidate -> !projectId.equals(candidate.projectId())
					|| !view.clipAssetId().equals(candidate.clipAssetId())
					|| !evidence.aiJobId().equals(candidate.aiJobId()))) {
			throw invalid("A MediaPipe candidate does not match the current source video.");
		}
		var ordered = candidates.stream()
			.sorted(Comparator.comparingLong(CandidateAnnotationSnapshot::frameTimestampMs)
				.thenComparingInt(CandidateAnnotationSnapshot::handIndex)
				.thenComparing(candidate -> candidate.candidateAnnotationId().toString()))
			.toList();
		var landmarks = new float[ordered.size()][21][3];
		for (var index = 0; index < ordered.size(); index += 1) {
			copyLandmarks(ordered.get(index), landmarks[index]);
		}
		var candidateGroup = output.putGroup("candidates");
		candidateGroup.putDataset("frame_timestamp_ms",
				ordered.stream().mapToLong(CandidateAnnotationSnapshot::frameTimestampMs).toArray());
		candidateGroup.putDataset("candidate_annotation_id",
				ordered.stream().map(candidate -> candidate.candidateAnnotationId().toString()).toArray(String[]::new));
		candidateGroup.putDataset("hand_index",
				ordered.stream().mapToInt(CandidateAnnotationSnapshot::handIndex).toArray());
		candidateGroup.putDataset("handedness",
				ordered.stream().map(CandidateAnnotationSnapshot::handedness).toArray(String[]::new));
		candidateGroup.putDataset("handedness_score", ordered.stream()
			.mapToDouble(
					candidate -> candidate.handednessScore() == null ? 0.0 : candidate.handednessScore().doubleValue())
			.toArray());
		candidateGroup.putDataset("image_width",
				ordered.stream().mapToInt(CandidateAnnotationSnapshot::imageWidth).toArray());
		candidateGroup.putDataset("image_height",
				ordered.stream().mapToInt(CandidateAnnotationSnapshot::imageHeight).toArray());
		candidateGroup.putDataset("landmarks", landmarks);
	}

	private static void copyLandmarks(CandidateAnnotationSnapshot candidate, float[][] target) {
		var landmarks = candidate.landmarks();
		if (landmarks == null || !landmarks.isArray() || landmarks.size() != 21) {
			throw invalid("A MediaPipe candidate does not contain 21 landmarks.");
		}
		for (var index = 0; index < 21; index += 1) {
			var point = landmarks.get(index);
			target[index][0] = finiteCoordinate(point, "x");
			target[index][1] = finiteCoordinate(point, "y");
			target[index][2] = finiteCoordinate(point, "z");
		}
	}

	private static float finiteCoordinate(JsonNode point, String coordinate) {
		if (point == null || !point.isObject() || !point.has(coordinate) || !point.get(coordinate).isNumber()) {
			throw invalid("A MediaPipe landmark coordinate is invalid.");
		}
		var value = point.get(coordinate).floatValue();
		if (!Float.isFinite(value)) {
			throw invalid("A MediaPipe landmark coordinate is not finite.");
		}
		return value;
	}

	private static TrainingDataExportException unavailable(String detail) {
		return new TrainingDataExportException("HDF5_EXPORT_NOT_READY", detail, 422);
	}

	private static TrainingDataExportException invalid(String detail) {
		return new TrainingDataExportException("HDF5_EXPORT_DATA_INVALID", detail, 422);
	}

	private void requireSyntheticProfile() {
		if (!"synthetic".equals(this.dataProfile)) {
			throw new TrainingDataExportException("HDF5_EXPORT_UNAVAILABLE",
					"HDF5 demonstration export is enabled only for the synthetic profile until a non-synthetic authorization model is defined.",
					403);
		}
	}

	private record LabeledSample(CandidateAnnotationSnapshot candidate, Segment segment, int cameraNumber) {
	}

	private record ExportSegment(int cameraNumber, Segment segment) {
	}

	private record CorrespondenceLink(UUID correspondenceId, CorrespondenceMember member) {
	}

	private record CameraTimestamp(int cameraNumber, long timestampMs) {
	}

	private record CameraSamples(CameraView annotationView, CaptureGroupView sourceView, CandidateEvidence evidence,
			List<LabeledSample> rows) {
	}

	private record ExportGroup(ApprovedGroupSnapshot snapshot, CaptureGroupFacts facts) {
	}

	record ExportOptions(boolean includeRawCandidates, boolean tensorLayout) {

		static final ExportOptions DEFAULT = new ExportOptions(false, true);

		static ExportOptions v2(boolean includeRawCandidates, boolean tensorLayout) {
			return new ExportOptions(includeRawCandidates, tensorLayout);
		}

	}

}
