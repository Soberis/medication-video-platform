package com.medicationvideo.platform.aicandidate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;
import com.medicationvideo.platform.iamproject.ProjectAccessService;

/**
 * Minimal immutable algorithm-evaluation record boundary. It stores declared evaluation
 * facts only; it never derives ground truth from CandidateAnnotation.
 */
@Service
class AiEvaluationService {

	static final String SCHEMA_VERSION = "ai-evaluation.v1";

	private static final EnumSet<AiEvaluationMetricKey> REQUIRED_METRICS = EnumSet.allOf(AiEvaluationMetricKey.class);

	private final ProjectAccessService projectAccessService;

	private final JdbcTemplate jdbcTemplate;

	private final AuditEventRecorder auditRecorder;

	AiEvaluationService(ProjectAccessService projectAccessService, JdbcTemplate jdbcTemplate,
			AuditEventRecorder auditRecorder) {
		this.projectAccessService = projectAccessService;
		this.jdbcTemplate = jdbcTemplate;
		this.auditRecorder = auditRecorder;
	}

	@Transactional
	AiEvaluationResponse create(UUID projectId, AiEvaluationCreateRequest request, String actorId, String requestId,
			String traceId) {
		var projectRole = this.projectAccessService.lockAndRequireMember(projectId, actorId);
		var metrics = validateMetrics(request.metrics());
		var evaluationId = UUID.randomUUID();
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var status = metrics.stream().anyMatch(metric -> metric.status() == AiEvaluationMetricStatus.PENDING)
				? AiEvaluationMetricStatus.PENDING : AiEvaluationMetricStatus.RECORDED;
		this.jdbcTemplate.update("""
				INSERT INTO ai_evaluation (
				    ai_evaluation_id, project_id, sample_version, sample_sha256, model_sha256, profile_sha256,
				    evaluation_status, parameter_adjustment_note, created_by, created_at, schema_version)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", evaluationId, projectId, request.sampleVersion(), request.sampleSha256(), request.modelSha256(),
				request.profileSha256(), status.name(), request.parameterAdjustmentNote(), actorId, Timestamp.from(now),
				SCHEMA_VERSION);
		for (var metric : metrics) {
			this.jdbcTemplate.update("""
					INSERT INTO ai_evaluation_metric (
					    ai_evaluation_metric_id, ai_evaluation_id, metric_key, metric_status, metric_value)
					VALUES (?, ?, ?, ?, ?)
					""", UUID.randomUUID(), evaluationId, metric.metricKey().name(), metric.status().name(),
					metric.value());
		}
		var details = new HashMap<String, String>();
		details.put("evaluation_schema_version", SCHEMA_VERSION);
		details.put("evaluation_status", status.name());
		details.put("model_sha256", request.modelSha256());
		details.put("profile_sha256", request.profileSha256());
		details.put("parameter_adjustment_note", request.parameterAdjustmentNote());
		details.put("metric_statuses", metricStatuses(metrics));
		if (request.sampleVersion() != null) {
			details.put("sample_version", request.sampleVersion());
		}
		if (request.sampleSha256() != null) {
			details.put("sample_sha256", request.sampleSha256());
		}
		this.auditRecorder
			.record(new AuditEventCommand(now, AuditActorType.USER, actorId, projectId, "AI_EVALUATION_RECORDED",
					"AIEvaluation", evaluationId.toString(), SCHEMA_VERSION, AuditOutcome.SUCCEEDED,
					status == AiEvaluationMetricStatus.PENDING ? "AI_EVALUATION_PENDING" : "AI_EVALUATION_RECORDED",
					requestId, traceId, "PROJECT_MEMBERSHIP_" + projectRole.name(), details));
		return get(projectId, evaluationId, actorId);
	}

	@Transactional(readOnly = true)
	AiEvaluationResponse get(UUID projectId, UUID evaluationId, String actorId) {
		this.projectAccessService.requireMember(projectId, actorId);
		try {
			var record = this.jdbcTemplate.queryForObject("""
					SELECT ai_evaluation_id, project_id, sample_version, sample_sha256, model_sha256, profile_sha256,
					       evaluation_status, parameter_adjustment_note, created_by, created_at, schema_version
					FROM ai_evaluation
					WHERE project_id = ? AND ai_evaluation_id = ?
					""",
					(resultSet, ignored) -> new EvaluationRow(resultSet.getObject("ai_evaluation_id", UUID.class),
							resultSet.getObject("project_id", UUID.class), resultSet.getString("sample_version"),
							resultSet.getString("sample_sha256"), resultSet.getString("model_sha256"),
							resultSet.getString("profile_sha256"), resultSet.getString("evaluation_status"),
							resultSet.getString("parameter_adjustment_note"), resultSet.getString("created_by"),
							resultSet.getTimestamp("created_at").toInstant(), resultSet.getString("schema_version")),
					projectId, evaluationId);
			var metrics = this.jdbcTemplate.query("""
					SELECT metric_key, metric_status, metric_value
					FROM ai_evaluation_metric
					WHERE ai_evaluation_id = ?
					ORDER BY metric_key
					""",
					(resultSet, ignored) -> new AiEvaluationMetricResponse(
							AiEvaluationMetricKey.valueOf(resultSet.getString("metric_key")),
							AiEvaluationMetricStatus.valueOf(resultSet.getString("metric_status")),
							(Double) resultSet.getObject("metric_value")),
					evaluationId);
			return record.toResponse(metrics);
		}
		catch (EmptyResultDataAccessException exception) {
			throw new HandPoseJobException("AI_EVALUATION_NOT_FOUND",
					"The algorithm evaluation was not found for the current project.", 404);
		}
	}

	private static List<AiEvaluationMetricRequest> validateMetrics(List<AiEvaluationMetricRequest> metrics) {
		var keys = EnumSet.noneOf(AiEvaluationMetricKey.class);
		for (var metric : metrics) {
			if (!keys.add(metric.metricKey())) {
				throw new HandPoseJobException("AI_EVALUATION_METRIC_DUPLICATE",
						"Each algorithm evaluation metric must occur exactly once.", 400);
			}
			validateMetricValue(metric);
		}
		if (!keys.equals(REQUIRED_METRICS)) {
			throw new HandPoseJobException("AI_EVALUATION_METRICS_INCOMPLETE",
					"The V1.18 algorithm evaluation requires the seven frozen metrics.", 400);
		}
		return List.copyOf(metrics);
	}

	private static void validateMetricValue(AiEvaluationMetricRequest metric) {
		if (metric.status() == AiEvaluationMetricStatus.PENDING) {
			if (metric.value() != null) {
				throw new HandPoseJobException("AI_EVALUATION_PENDING_VALUE_FORBIDDEN",
						"A PENDING metric must retain a null value.", 400);
			}
			return;
		}
		if (metric.status() != AiEvaluationMetricStatus.RECORDED || metric.value() == null
				|| !Double.isFinite(metric.value()) || metric.value() < 0
				|| (isRate(metric.metricKey()) && metric.value() > 1)) {
			throw new HandPoseJobException("AI_EVALUATION_METRIC_VALUE_INVALID",
					"A RECORDED metric must contain finite evidence within its metric range.", 400);
		}
	}

	private static boolean isRate(AiEvaluationMetricKey key) {
		return key == AiEvaluationMetricKey.HAND_FRAME_RECALL
				|| key == AiEvaluationMetricKey.NO_HAND_FRAME_FALSE_POSITIVE_RATE
				|| key == AiEvaluationMetricKey.LANDMARK_21_VALID_RATE
				|| key == AiEvaluationMetricKey.HANDEDNESS_ACCURACY
				|| key == AiEvaluationMetricKey.FAST_MOTION_OCCLUSION_USEFULNESS;
	}

	private static String metricStatuses(List<AiEvaluationMetricRequest> metrics) {
		return metrics.stream()
			.sorted((first, second) -> first.metricKey().name().compareTo(second.metricKey().name()))
			.map(metric -> metric.metricKey().name() + "=" + metric.status().name())
			.reduce((first, second) -> first + "," + second)
			.orElseThrow();
	}

	private record EvaluationRow(UUID evaluationId, UUID projectId, String sampleVersion, String sampleSha256,
			String modelSha256, String profileSha256, String status, String parameterAdjustmentNote, String createdBy,
			Instant createdAt, String schemaVersion) {

		AiEvaluationResponse toResponse(List<AiEvaluationMetricResponse> metrics) {
			return new AiEvaluationResponse(this.evaluationId, this.projectId, this.sampleVersion, this.sampleSha256,
					this.modelSha256, this.profileSha256, this.status, this.parameterAdjustmentNote, metrics,
					this.createdBy, this.createdAt, this.schemaVersion);
		}

	}

}
