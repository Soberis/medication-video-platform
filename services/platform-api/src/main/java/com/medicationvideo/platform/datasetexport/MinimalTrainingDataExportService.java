package com.medicationvideo.platform.datasetexport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.PositionOutputStream;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Synchronous, read-only P0 export. The database views are the authoritative eligibility
 * projection; this service only aligns candidate geometry with the already approved human
 * action intervals and serializes the result.
 */
@Service
class MinimalTrainingDataExportService {

	static final String MEDIA_TYPE = "application/vnd.apache.parquet";

	static final String FILE_NAME = "training_data.parquet";

	static final String SCHEMA_VERSION = "training-data.v2";

	private static final int LANDMARK_COUNT = 21;

	private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");

	private static final String ROW_DEFINITION = "one MediaPipe Candidate hand observation inside one APPROVED human "
			+ "action interval [start_ms, end_ms)";

	static final Map<String, String> PARQUET_METADATA = Map.ofEntries(Map.entry("schema_version", SCHEMA_VERSION),
			Map.entry("cleaning_gate", "PASS"), Map.entry("action_label_source", "human_annotation_revision"),
			Map.entry("action_interval_source", "human_annotation_revision.annotation_document.segments"),
			Map.entry("hand_geometry_source", "candidate_annotation"),
			Map.entry("hand_geometry_is_ground_truth", "false"),
			Map.entry("candidate_algorithm_source", "ai_job.algorithm_key+algorithm_version"),
			Map.entry("handedness_is_ground_truth", "false"),
			Map.entry("handedness_score_source", "candidate_annotation.handedness_score"),
			Map.entry("hand_mask_policy", "not_available"),
			Map.entry("eligibility_projection", "training_data_eligibility"),
			Map.entry("candidate_projection", "training_data_candidate_observations"),
			Map.entry("coordinate_system", "normalized_image_xy_and_mediapipe_relative_z"),
			Map.entry("video_policy", "video_id_only_no_mp4"));

	private static final MessageType PARQUET_SCHEMA = MessageTypeParser.parseMessageType(parquetSchema());

	private static final String ELIGIBILITY_QUERY = """
			SELECT video_id, content_type, asset_status, asset_deleted, asset_sha256,
			       quality_decision, quality_review_version, annotation_revision_id,
			       annotation_task_id, revision_number, annotation_document, document_sha256,
			       label_schema_id, label_schema_digest, annotation_review_id,
			       annotation_review_decision, cleaning_result_id, cleaning_run_id,
			       cleaning_outcome, cleaning_run_status, cleaning_rule_set_id, rule_set_digest
			FROM training_data_eligibility
			WHERE project_id = ?
			ORDER BY video_id
			""";

	private static final String CANDIDATE_QUERY = """
			SELECT video_id, annotation_revision_id, candidate_annotation_id,
			       algorithm_key, algorithm_version, frame_timestamp_ms, hand_index, handedness, handedness_score,
			       landmarks, model_sha256, profile_sha256, valid_landmark_count
			FROM training_data_candidate_observations
			WHERE project_id = ?
			ORDER BY video_id, frame_timestamp_ms, hand_index, candidate_annotation_id
			""";

	private final JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper;

	private final String dataProfile;

	MinimalTrainingDataExportService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
			@Value("${platform.training-data-export.data-profile:disabled}") String dataProfile) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
		this.dataProfile = dataProfile == null ? "" : dataProfile.trim().toLowerCase();
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public TrainingDataExportPreviewResponse preview(UUID projectId) {
		return snapshot(projectId).preview();
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public byte[] download(UUID projectId) {
		return writeParquet(snapshot(projectId).rows());
	}

	private ExportSnapshot snapshot(UUID projectId) {
		requireSyntheticProfile();
		var eligibleVideos = this.jdbcTemplate.query(ELIGIBILITY_QUERY, this::mapEligibility, projectId);
		var candidates = eligibleVideos.isEmpty() ? List.<CandidateObservation>of()
				: this.jdbcTemplate.query(CANDIDATE_QUERY, this::mapCandidate, projectId);
		var built = buildTrainingRows(eligibleVideos, candidates);
		return new ExportSnapshot(built.rows(), eligibleVideos.size(), candidates.size(),
				built.unlabeledCandidateObservationCount());
	}

	RowBuild buildTrainingRows(List<EligibleVideo> eligibleVideos, List<CandidateObservation> candidates) {
		var rows = new ArrayList<TrainingDataRow>();
		var eligibleByVideo = new HashMap<UUID, EligibleVideo>();
		var segmentsByVideo = new HashMap<UUID, List<ActionSegment>>();
		for (var eligibleVideo : eligibleVideos) {
			validateEligibility(eligibleVideo);
			eligibleByVideo.put(eligibleVideo.videoId(), eligibleVideo);
			segmentsByVideo.put(eligibleVideo.videoId(), parseSegments(eligibleVideo.annotationDocument()));
		}
		var unlabeledCandidates = 0L;
		for (var candidate : candidates) {
			var eligibleVideo = eligibleByVideo.get(candidate.videoId());
			if (eligibleVideo == null) {
				throw invalidEvidence("CandidateAnnotation " + candidate.candidateAnnotationId()
						+ " is not bound to an eligible video.");
			}
			if (!eligibleVideo.annotationRevisionId().equals(candidate.annotationRevisionId())) {
				throw invalidEvidence("CandidateAnnotation " + candidate.candidateAnnotationId()
						+ " is not bound to the current AnnotationRevision.");
			}
			var actionSegment = actionSegmentAt(segmentsByVideo.get(candidate.videoId()), candidate.timestampMs());
			if (actionSegment == null) {
				unlabeledCandidates++;
				continue;
			}
			rows.add(toTrainingRow(eligibleVideo, candidate, actionSegment));
		}
		rows.sort(Comparator.comparing(TrainingDataRow::videoId)
			.thenComparingLong(TrainingDataRow::timestampMs)
			.thenComparingInt(TrainingDataRow::handIndex));
		return new RowBuild(List.copyOf(rows), unlabeledCandidates);
	}

	private void requireSyntheticProfile() {
		if (!"synthetic".equals(this.dataProfile)) {
			throw new TrainingDataExportException("TRAINING_DATA_EXPORT_UNAVAILABLE",
					"Training data export is enabled only for the synthetic profile until a non-synthetic authorization model is defined.",
					403);
		}
	}

	private EligibleVideo mapEligibility(ResultSet resultSet, int rowNumber) throws SQLException {
		return new EligibleVideo(resultSet.getObject("video_id", UUID.class), resultSet.getString("content_type"),
				resultSet.getString("asset_status"), resultSet.getBoolean("asset_deleted"),
				resultSet.getString("asset_sha256"), resultSet.getString("quality_decision"),
				resultSet.getLong("quality_review_version"), resultSet.getObject("annotation_revision_id", UUID.class),
				resultSet.getObject("annotation_task_id", UUID.class), resultSet.getInt("revision_number"),
				readJson(resultSet, "annotation_document"), resultSet.getString("document_sha256"),
				resultSet.getObject("label_schema_id", UUID.class), resultSet.getString("label_schema_digest"),
				resultSet.getObject("annotation_review_id", UUID.class),
				resultSet.getString("annotation_review_decision"),
				resultSet.getObject("cleaning_result_id", UUID.class),
				resultSet.getObject("cleaning_run_id", UUID.class), resultSet.getString("cleaning_outcome"),
				resultSet.getString("cleaning_run_status"), resultSet.getObject("cleaning_rule_set_id", UUID.class),
				resultSet.getString("rule_set_digest"));
	}

	private CandidateObservation mapCandidate(ResultSet resultSet, int rowNumber) throws SQLException {
		return new CandidateObservation(resultSet.getObject("video_id", UUID.class),
				resultSet.getObject("annotation_revision_id", UUID.class),
				resultSet.getObject("candidate_annotation_id", UUID.class), resultSet.getLong("frame_timestamp_ms"),
				resultSet.getString("algorithm_key"), resultSet.getString("algorithm_version"),
				resultSet.getInt("hand_index"), resultSet.getString("handedness"),
				resultSet.getDouble("handedness_score"), readJson(resultSet, "landmarks"),
				resultSet.getString("model_sha256"), resultSet.getString("profile_sha256"),
				resultSet.getInt("valid_landmark_count"));
	}

	private JsonNode readJson(ResultSet resultSet, String column) throws SQLException {
		try {
			var source = resultSet.getString(column);
			return this.objectMapper.readTree(source);
		}
		catch (RuntimeException exception) {
			throw invalidEvidence("The export evidence contains invalid JSON in " + column + ".");
		}
	}

	private static void validateEligibility(EligibleVideo video) {
		if (!"video/mp4".equalsIgnoreCase(video.contentType()) || !"INGESTED".equals(video.assetStatus())
				|| video.assetDeleted() || !"QUALIFIED".equals(video.qualityDecision())
				|| !"APPROVED".equals(video.annotationReviewDecision()) || !"PASS".equals(video.cleaningOutcome())
				|| !"SUCCEEDED".equals(video.cleaningRunStatus())) {
			throw invalidEvidence("Video " + video.videoId() + " does not satisfy the current training-data gates.");
		}
		for (var value : java.util.Arrays.asList(video.videoId(), video.annotationRevisionId(),
				video.annotationTaskId(), video.labelSchemaId(), video.annotationReviewId(), video.cleaningResultId(),
				video.cleaningRunId(), video.cleaningRuleSetId())) {
			if (value == null) {
				throw invalidEvidence("Video " + video.videoId() + " is missing bound eligibility evidence.");
			}
		}
		for (var digest : java.util.Arrays.asList(video.assetSha256(), video.documentSha256(),
				video.labelSchemaDigest(), video.ruleSetDigest())) {
			if (digest == null || !SHA256.matcher(digest).matches()) {
				throw invalidEvidence("Video " + video.videoId() + " is missing a bound SHA-256 evidence value.");
			}
		}
	}

	private TrainingDataRow toTrainingRow(EligibleVideo eligibleVideo, CandidateObservation candidate,
			ActionSegment actionSegment) {
		if (!"MEDIAPIPE_HAND_LANDMARKER".equals(candidate.algorithmKey()) || candidate.algorithmVersion() == null
				|| candidate.algorithmVersion().isBlank() || candidate.algorithmVersion().length() > 32
				|| candidate.timestampMs() < 0 || candidate.handIndex() < 0 || candidate.handIndex() > 1
				|| !List.of("LEFT", "RIGHT", "UNKNOWN").contains(candidate.handedness())
				|| !Double.isFinite(candidate.handednessScore()) || candidate.handednessScore() < 0
				|| candidate.handednessScore() > 1 || candidate.validLandmarkCount() != LANDMARK_COUNT
				|| candidate.modelSha256() == null || !SHA256.matcher(candidate.modelSha256()).matches()
				|| candidate.profileSha256() == null || !SHA256.matcher(candidate.profileSha256()).matches()) {
			throw invalidEvidence("CandidateAnnotation " + candidate.candidateAnnotationId()
					+ " does not contain valid export provenance.");
		}
		var landmarks = flattenLandmarks(candidate.landmarks(), candidate.candidateAnnotationId());
		return new TrainingDataRow(eligibleVideo.videoId(), candidate.timestampMs(), actionSegment.startMs(),
				actionSegment.endMs(), actionSegment.label(), eligibleVideo.annotationRevisionId(),
				candidate.handIndex(), candidate.candidateAnnotationId(), candidate.handedness(),
				(float) candidate.handednessScore(), candidate.algorithmKey(), candidate.algorithmVersion(),
				candidate.modelSha256(), candidate.profileSha256(), landmarks);
	}

	private static List<Float> flattenLandmarks(JsonNode landmarks, UUID candidateId) {
		if (landmarks == null || !landmarks.isArray() || landmarks.size() != LANDMARK_COUNT) {
			throw invalidEvidence("CandidateAnnotation " + candidateId + " does not contain 21 landmarks.");
		}
		var flattened = new ArrayList<Float>(LANDMARK_COUNT * 3);
		for (var point : landmarks) {
			if (point == null || !point.isObject()) {
				throw invalidEvidence("CandidateAnnotation " + candidateId + " contains an invalid landmark.");
			}
			for (var axis : List.of("x", "y", "z")) {
				var coordinate = point.get(axis);
				if (coordinate == null || !coordinate.isNumber() || !Double.isFinite(coordinate.asDouble())) {
					throw invalidEvidence("CandidateAnnotation " + candidateId + " contains a non-finite landmark.");
				}
				flattened.add(coordinate.floatValue());
			}
		}
		return List.copyOf(flattened);
	}

	private static List<ActionSegment> parseSegments(JsonNode document) {
		if (document == null || !document.isObject()
				|| !"annotation-document.v1".equals(text(document, "schema_version"))) {
			throw invalidEvidence("The current AnnotationRevision is not annotation-document.v1.");
		}
		var rawSegments = document.get("segments");
		if (rawSegments == null || !rawSegments.isArray() || rawSegments.isEmpty()) {
			throw invalidEvidence("The current AnnotationRevision has no action segments.");
		}
		var segments = new ArrayList<ActionSegment>();
		for (var rawSegment : rawSegments) {
			if (rawSegment == null || !rawSegment.isObject() || !rawSegment.has("start_ms") || !rawSegment.has("end_ms")
					|| !rawSegment.has("label") || !rawSegment.get("start_ms").isIntegralNumber()
					|| !rawSegment.get("end_ms").isIntegralNumber() || !rawSegment.get("label").isTextual()) {
				throw invalidEvidence("The current AnnotationRevision contains an invalid action segment.");
			}
			var startMs = rawSegment.get("start_ms").longValue();
			var endMs = rawSegment.get("end_ms").longValue();
			var label = rawSegment.get("label").asText();
			if (startMs < 0 || endMs <= startMs || label.isBlank()) {
				throw invalidEvidence("The current AnnotationRevision contains an invalid action segment.");
			}
			segments.add(new ActionSegment(startMs, endMs, label));
		}
		segments.sort(Comparator.comparingLong(ActionSegment::startMs).thenComparingLong(ActionSegment::endMs));
		var previousEnd = -1L;
		for (var segment : segments) {
			if (segment.startMs() < previousEnd) {
				throw invalidEvidence("The current AnnotationRevision contains overlapping action segments.");
			}
			previousEnd = segment.endMs();
		}
		return List.copyOf(segments);
	}

	private static String text(JsonNode object, String field) {
		var value = object.get(field);
		return value == null || !value.isTextual() ? null : value.asText();
	}

	private static ActionSegment actionSegmentAt(List<ActionSegment> segments, long timestampMs) {
		for (var segment : segments) {
			if (segment.startMs() <= timestampMs && timestampMs < segment.endMs()) {
				return segment;
			}
			if (timestampMs < segment.startMs()) {
				break;
			}
		}
		return null;
	}

	static byte[] writeParquet(List<TrainingDataRow> rows) {
		var output = new ByteArrayOutputFile();
		var factory = new SimpleGroupFactory(PARQUET_SCHEMA);
		try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(output)
			.withType(PARQUET_SCHEMA)
			.withExtraMetaData(PARQUET_METADATA)
			.build()) {
			for (var trainingRow : rows) {
				var group = factory.newGroup();
				group.add("video_id", trainingRow.videoId().toString());
				group.add("timestamp_ms", trainingRow.timestampMs());
				group.add("action_start_ms", trainingRow.actionStartMs());
				group.add("action_end_ms", trainingRow.actionEndMs());
				group.add("action_label", trainingRow.actionLabel());
				group.add("annotation_revision_id", trainingRow.annotationRevisionId().toString());
				group.add("hand_index", trainingRow.handIndex());
				group.add("hand_presence", true);
				group.add("hand_mask_available", false);
				group.add("candidate_annotation_id", trainingRow.candidateAnnotationId().toString());
				group.add("handedness", trainingRow.handedness());
				group.add("handedness_score", trainingRow.handednessScore());
				group.add("handedness_is_ground_truth", false);
				group.add("algorithm_key", trainingRow.algorithmKey());
				group.add("algorithm_version", trainingRow.algorithmVersion());
				group.add("model_sha256", trainingRow.modelSha256());
				group.add("profile_sha256", trainingRow.profileSha256());
				for (var index = 0; index < LANDMARK_COUNT; index++) {
					var base = index * 3;
					group.add("landmark_%02d_x".formatted(index), trainingRow.landmarks().get(base));
					group.add("landmark_%02d_y".formatted(index), trainingRow.landmarks().get(base + 1));
					group.add("landmark_%02d_z".formatted(index), trainingRow.landmarks().get(base + 2));
				}
				writer.write(group);
			}
		}
		catch (IOException exception) {
			throw new TrainingDataExportException("TRAINING_DATA_EXPORT_FAILED",
					"The training data Parquet could not be generated.", 500);
		}
		return output.bytes();
	}

	private static String parquetSchema() {
		var schema = new StringBuilder("""
				message training_data {
					required binary video_id (UTF8);
					required int64 timestamp_ms;
					required int64 action_start_ms;
					required int64 action_end_ms;
					required binary action_label (UTF8);
					required binary annotation_revision_id (UTF8);
					required int32 hand_index;
					required boolean hand_presence;
					required boolean hand_mask_available;
					required binary candidate_annotation_id (UTF8);
					required binary handedness (UTF8);
					required float handedness_score;
					required boolean handedness_is_ground_truth;
					required binary algorithm_key (UTF8);
					required binary algorithm_version (UTF8);
					required binary model_sha256 (UTF8);
					required binary profile_sha256 (UTF8);
				""");
		for (var index = 0; index < LANDMARK_COUNT; index++) {
			schema.append("\trequired float landmark_%02d_x;\n".formatted(index));
			schema.append("\trequired float landmark_%02d_y;\n".formatted(index));
			schema.append("\trequired float landmark_%02d_z;\n".formatted(index));
		}
		return schema.append("}\n").toString();
	}

	private static TrainingDataExportException invalidEvidence(String message) {
		return new TrainingDataExportException("TRAINING_DATA_EXPORT_EVIDENCE_INVALID", message, 422);
	}

	record EligibleVideo(UUID videoId, String contentType, String assetStatus, boolean assetDeleted, String assetSha256,
			String qualityDecision, long qualityReviewVersion, UUID annotationRevisionId, UUID annotationTaskId,
			int revisionNumber, JsonNode annotationDocument, String documentSha256, UUID labelSchemaId,
			String labelSchemaDigest, UUID annotationReviewId, String annotationReviewDecision, UUID cleaningResultId,
			UUID cleaningRunId, String cleaningOutcome, String cleaningRunStatus, UUID cleaningRuleSetId,
			String ruleSetDigest) {
	}

	record CandidateObservation(UUID videoId, UUID annotationRevisionId, UUID candidateAnnotationId, long timestampMs,
			String algorithmKey, String algorithmVersion, int handIndex, String handedness, double handednessScore,
			JsonNode landmarks, String modelSha256, String profileSha256, int validLandmarkCount) {
	}

	record TrainingDataRow(UUID videoId, long timestampMs, long actionStartMs, long actionEndMs, String actionLabel,
			UUID annotationRevisionId, int handIndex, UUID candidateAnnotationId, String handedness,
			float handednessScore, String algorithmKey, String algorithmVersion, String modelSha256,
			String profileSha256, List<Float> landmarks) {
	}

	record ActionSegment(long startMs, long endMs, String label) {
	}

	record ExportSnapshot(List<TrainingDataRow> rows, long eligibleVideoCount, long candidateObservationCount,
			long unlabeledCandidateObservationCount) {

		TrainingDataExportPreviewResponse preview() {
			return new TrainingDataExportPreviewResponse(SCHEMA_VERSION, this.eligibleVideoCount,
					this.candidateObservationCount, this.rows.size(), this.unlabeledCandidateObservationCount,
					ROW_DEFINITION, FILE_NAME, MEDIA_TYPE, false);
		}
	}

	record RowBuild(List<TrainingDataRow> rows, long unlabeledCandidateObservationCount) {
	}

	private static final class ByteArrayOutputFile implements OutputFile {

		private ByteArrayOutputStream output;

		@Override
		public PositionOutputStream create(long blockSizeHint) throws IOException {
			if (this.output != null) {
				throw new IOException("Parquet output was already created.");
			}
			return createOrOverwrite(blockSizeHint);
		}

		@Override
		public PositionOutputStream createOrOverwrite(long blockSizeHint) {
			this.output = new ByteArrayOutputStream();
			return new ByteArrayPositionOutputStream(this.output);
		}

		@Override
		public boolean supportsBlockSize() {
			return false;
		}

		@Override
		public long defaultBlockSize() {
			return 0;
		}

		byte[] bytes() {
			return this.output == null ? new byte[0] : this.output.toByteArray();
		}

	}

	private static final class ByteArrayPositionOutputStream extends PositionOutputStream {

		private final OutputStream output;

		private long position;

		private ByteArrayPositionOutputStream(OutputStream output) {
			this.output = output;
		}

		@Override
		public long getPos() {
			return this.position;
		}

		@Override
		public void write(int value) throws IOException {
			this.output.write(value);
			this.position++;
		}

		@Override
		public void write(byte[] bytes, int offset, int length) throws IOException {
			this.output.write(bytes, offset, length);
			this.position += length;
		}

		@Override
		public void close() throws IOException {
			this.output.close();
		}

	}

}
