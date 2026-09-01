package com.medicationvideo.platform.datasetexport;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Minimal V1.27 group/event manifest. It is a read-only, versioned JSON contract and does
 * not create a DatasetVersion or copy media.
 */
@Service
class TrainingDataV3ExportService {

	static final String SCHEMA_VERSION = "training-data.v3";

	static final String MEDIA_TYPE = "application/vnd.medication.training-data.v3+json";

	static final String FILE_NAME = "training_data_v3.json";

	static final String SPLIT_UNIT = "capture_group";

	private static final String QUERY = """
			SELECT capture_group_id, annotation_revision_id, revision_number, annotation_document,
			       primary_clip_asset_id, primary_camera_number, confirmed_view_set, input_version,
			       annotation_review_id, review_decision, reviewed_by, reviewed_at,
			       qualified_views, rejected_missing_views, candidate_evidence,
			       capture_group_quality_review_id, group_quality_result, group_quality_revision,
			       group_quality_reviewed_by, group_quality_decided_at, capture_group_cleaning_id,
			       primary_interval, participating_views, excluded_views, mapping_provenance,
			       candidate_provenance, rule_set_key, rule_set_version, rule_set_digest,
			       cleaning_outcome, cleaning_reason_codes, cleaning_executed_checks,
			       cleaning_input_fingerprint, cleaning_evaluated_by, cleaning_authorization_basis,
			       cleaning_evaluated_at
			  FROM training_data_group_eligibility_v3
			 WHERE project_id = ?
			 ORDER BY capture_group_id, revision_number, annotation_revision_id
			""";

	private static final String ELIGIBILITY_VALIDATION_QUERY = """
			SELECT capture_group_id, annotation_revision_id
			  FROM training_data_group_eligibility_v3_integrity
			 WHERE project_id = ?
			   AND cleaning_outcome = 'PASS'
			   AND (context_consistent IS NOT TRUE
			        OR source_media_valid IS NOT TRUE
			        OR eligible_revision_count <> 1)
			 ORDER BY capture_group_id, annotation_revision_id
			""";

	private final JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper;

	private final String dataProfile;

	TrainingDataV3ExportService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
			@Value("${platform.training-data-export.data-profile:disabled}") String dataProfile) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
		this.dataProfile = dataProfile == null ? "" : dataProfile.trim().toLowerCase();
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	TrainingDataV3ExportPreviewResponse preview(UUID projectId) {
		var events = snapshot(projectId).events();
		return new TrainingDataV3ExportPreviewResponse(SCHEMA_VERSION, events.size(), events.size(), FILE_NAME,
				MEDIA_TYPE, SPLIT_UNIT, false);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	byte[] download(UUID projectId) {
		var document = snapshot(projectId);
		try {
			return this.objectMapper.writeValueAsString(document).getBytes(java.nio.charset.StandardCharsets.UTF_8);
		}
		catch (JacksonException exception) {
			throw new TrainingDataExportException("TRAINING_DATA_V3_EXPORT_FAILED",
					"The V1.27 training-data manifest could not be generated.", 500);
		}
	}

	TrainingDataV3Document readback(byte[] bytes) {
		try {
			return this.objectMapper.readValue(bytes, TrainingDataV3Document.class);
		}
		catch (JacksonException exception) {
			throw new TrainingDataExportException("TRAINING_DATA_V3_READBACK_INVALID",
					"The V1.27 training-data manifest could not be read back.", 422);
		}
	}

	static void validateSplitAssignments(Collection<SplitAssignment> assignments) {
		var splitByGroup = new HashMap<UUID, String>();
		for (var assignment : assignments) {
			Objects.requireNonNull(assignment);
			if (assignment.captureGroupId() == null || assignment.split() == null || assignment.split().isBlank()) {
				throw new TrainingDataExportException("TRAINING_DATA_SPLIT_ASSIGNMENT_INVALID",
						"Every split assignment must include a CaptureGroup and split.", 422);
			}
			var split = assignment.split().trim().toLowerCase();
			if (!List.of("train", "validation", "test").contains(split)) {
				throw new TrainingDataExportException("TRAINING_DATA_SPLIT_ASSIGNMENT_INVALID",
						"Split must be train, validation or test.", 422);
			}
			var previous = splitByGroup.putIfAbsent(assignment.captureGroupId(), split);
			if (previous != null && !previous.equals(split)) {
				throw new TrainingDataExportException("TRAINING_DATA_SPLIT_LEAKAGE",
						"A CaptureGroup cannot be assigned to more than one split.", 422);
			}
		}
	}

	private TrainingDataV3Document snapshot(UUID projectId) {
		requireSyntheticProfile();
		validateEligibility(projectId);
		List<TrainingDataV3Event> events;
		try {
			events = this.jdbcTemplate.query(QUERY, this::map, projectId);
		}
		catch (DataAccessException exception) {
			throw eligibilityProjectionInvalid();
		}
		return new TrainingDataV3Document(SCHEMA_VERSION, SPLIT_UNIT, events);
	}

	private void validateEligibility(UUID projectId) {
		List<Map<String, Object>> invalidRows;
		try {
			invalidRows = this.jdbcTemplate.queryForList(ELIGIBILITY_VALIDATION_QUERY, projectId);
		}
		catch (DataAccessException exception) {
			throw eligibilityProjectionInvalid();
		}
		if (!invalidRows.isEmpty()) {
			throw new TrainingDataExportException("TRAINING_DATA_V3_ELIGIBILITY_INVALID",
					"The current CaptureGroup source media or approved revision evidence is invalid or ambiguous; export is blocked.",
					422);
		}
	}

	private static TrainingDataExportException eligibilityProjectionInvalid() {
		return new TrainingDataExportException("TRAINING_DATA_V3_ELIGIBILITY_INVALID",
				"The V1.27 training-data eligibility projection could not prove a valid current source; export is blocked.",
				422);
	}

	private TrainingDataV3Event map(ResultSet resultSet, int rowNumber) throws SQLException {
		var annotationDocument = readJson(resultSet, "annotation_document");
		var labels = actionLabels(annotationDocument);
		var primaryClipAssetId = resultSet.getObject("primary_clip_asset_id", UUID.class);
		var primaryInterval = readJson(resultSet, "primary_interval");
		var participatingViews = sanitizeViewEvidence(this.objectMapper, readJson(resultSet, "participating_views"),
				primaryClipAssetId, primaryInterval);
		var excludedViews = sanitizeViewEvidence(this.objectMapper, readJson(resultSet, "excluded_views"),
				primaryClipAssetId, primaryInterval);
		return new TrainingDataV3Event(resultSet.getObject("capture_group_id", UUID.class),
				resultSet.getObject("annotation_revision_id", UUID.class), resultSet.getInt("revision_number"),
				labels.isEmpty() ? null : labels.getFirst(), labels, primaryClipAssetId,
				resultSet.getInt("primary_camera_number"), primaryInterval, readJson(resultSet, "confirmed_view_set"),
				resultSet.getString("input_version"), participatingViews, excludedViews,
				resultSet.getString("group_quality_result"), resultSet.getLong("group_quality_revision"),
				resultSet.getObject("capture_group_quality_review_id", UUID.class),
				resultSet.getString("group_quality_reviewed_by"), instant(resultSet, "group_quality_decided_at"),
				resultSet.getObject("annotation_review_id", UUID.class), resultSet.getString("review_decision"),
				resultSet.getString("reviewed_by"), instant(resultSet, "reviewed_at"),
				readJson(resultSet, "qualified_views"), readJson(resultSet, "rejected_missing_views"),
				readJson(resultSet, "candidate_evidence"), resultSet.getObject("capture_group_cleaning_id", UUID.class),
				resultSet.getString("rule_set_key"), resultSet.getString("rule_set_version"),
				resultSet.getString("rule_set_digest"), resultSet.getString("cleaning_outcome"),
				readJson(resultSet, "cleaning_reason_codes"), readJson(resultSet, "cleaning_executed_checks"),
				resultSet.getString("cleaning_input_fingerprint"), resultSet.getString("cleaning_evaluated_by"),
				resultSet.getString("cleaning_authorization_basis"), instant(resultSet, "cleaning_evaluated_at"),
				this.objectMapper.createArrayNode(), readJson(resultSet, "candidate_provenance"));
	}

	/**
	 * Normalizes current and historical Cleaning projections to the V1.27 time rule.
	 * Historical rows may have been produced while caller mappings were still accepted;
	 * those rows must not make an action-aligned supporting timestamp appear in v3.
	 */
	static JsonNode sanitizeViewEvidence(ObjectMapper objectMapper, JsonNode views, UUID primaryClipAssetId,
			JsonNode primaryInterval) {
		if (views == null || !views.isArray()) {
			return views;
		}
		var sanitized = objectMapper.createArrayNode();
		for (var view : views) {
			if (!view.isObject()) {
				sanitized.add(view);
				continue;
			}
			var copy = ((tools.jackson.databind.node.ObjectNode) view).deepCopy();
			var isPrimary = primaryClipAssetId != null && copy.has("clip_asset_id")
					&& primaryClipAssetId.toString().equals(copy.get("clip_asset_id").asText());
			if (isPrimary) {
				if (primaryInterval == null) {
					copy.remove("interval");
				}
				else {
					copy.set("interval", primaryInterval.deepCopy());
				}
				copy.put("interval_source", "PRIMARY_AUTHORITATIVE");
				copy.put("action_aligned_interval", true);
			}
			else {
				copy.remove("interval");
				copy.remove("action_interval");
				copy.remove("action_start_ms");
				copy.remove("action_end_ms");
				copy.remove("interval_source");
				copy.remove("action_aligned_interval");
				copy.remove("mapping_provenance");
				copy.put("interval_source", "EVENT_LEVEL_EVIDENCE");
				copy.put("action_aligned_interval", false);
			}
			sanitized.add(copy);
		}
		return sanitized;
	}

	private static Instant instant(ResultSet resultSet, String column) throws SQLException {
		var value = resultSet.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private JsonNode readJson(ResultSet resultSet, String column) throws SQLException {
		try {
			return this.objectMapper.readTree(resultSet.getString(column));
		}
		catch (JacksonException exception) {
			throw new TrainingDataExportException("TRAINING_DATA_V3_EVIDENCE_INVALID",
					"The V1.27 training-data projection contains invalid JSON.", 422);
		}
	}

	private static List<String> actionLabels(JsonNode annotationDocument) {
		var segments = annotationDocument == null ? null : annotationDocument.get("segments");
		if (segments == null || !segments.isArray()) {
			return List.of();
		}
		return java.util.stream.StreamSupport.stream(segments.spliterator(), false)
			.map(segment -> segment.get("label"))
			.filter(Objects::nonNull)
			.filter(JsonNode::isTextual)
			.map(JsonNode::asText)
			.distinct()
			.toList();
	}

	private void requireSyntheticProfile() {
		if (!"synthetic".equals(this.dataProfile)) {
			throw new TrainingDataExportException("TRAINING_DATA_EXPORT_UNAVAILABLE",
					"Training data export is enabled only for the synthetic profile until a non-synthetic authorization model is defined.",
					403);
		}
	}

	public record TrainingDataV3Document(String schemaVersion, String splitUnit, List<TrainingDataV3Event> events) {

		public TrainingDataV3Document {
			events = List.copyOf(events);
		}
	}

	public record TrainingDataV3Event(UUID captureGroupId, UUID annotationRevisionId, int revision, String actionLabel,
			List<String> actionLabels, UUID primaryClipAssetId, int primaryCameraNumber, JsonNode primaryInterval,
			JsonNode confirmedViewSet, String inputVersion, JsonNode participatingViews, JsonNode excludedViews,
			String groupQuality, long groupQualityRevision, UUID groupQualityReviewId, String groupQualityReviewedBy,
			Instant groupQualityDecidedAt, UUID annotationReviewId, String reviewDecision, String reviewedBy,
			Instant reviewedAt, JsonNode qualifiedViews, JsonNode rejectedMissingViews, JsonNode candidateEvidence,
			UUID captureGroupCleaningId, String cleaningRuleSetKey, String cleaningRuleSetVersion,
			String cleaningRuleSetDigest, String cleaningOutcome, JsonNode cleaningReasonCodes,
			JsonNode cleaningExecutedChecks, String cleaningInputFingerprint, String cleaningEvaluatedBy,
			String cleaningAuthorizationBasis, Instant cleaningEvaluatedAt, JsonNode mappingProvenance,
			JsonNode candidateProvenance) {

		public TrainingDataV3Event {
			actionLabels = List.copyOf(actionLabels);
		}
	}

	public record SplitAssignment(UUID captureGroupId, String split) {
	}

}
