package com.medicationvideo.platform.aicandidate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves only action candidates from the current successful job and its frozen
 * task/schema context. The adapter owns the action-candidate tables and exposes a
 * read-only seam to annotation review.
 */
@Component
class ActionCandidateSourceQueryAdapter implements ActionCandidateSourceQueryPort {

	private static final String SHA256_PATTERN = "^[a-f0-9]{64}$";

	private static final Pattern SHA256 = Pattern.compile(SHA256_PATTERN);

	// pgJDBC requires "??" to pass PostgreSQL's JSONB key-exists operator through as a
	// literal "?".
	private static final String CURRENT_ACTION_JOB_SQL = """
			SELECT c.action_candidate_id, c.clip_asset_id, c.label_schema_id, c.label_schema_digest,
			       c.start_ms, c.end_ms, c.action_label
			  FROM action_candidate c
			  JOIN ai_job j ON j.ai_job_id = c.ai_job_id
			 WHERE c.project_id = ?
			   AND c.capture_group_id = ?
			   AND c.clip_asset_id = ?
			   AND (c.annotation_task_id = ? OR c.annotation_task_id IS NULL)
			   AND c.label_schema_id = ?
			   AND c.label_schema_digest = ?
			   AND c.ai_job_id = (
			       SELECT current_job.ai_job_id
			         FROM ai_job current_job
			        WHERE current_job.project_id = ?
			          AND current_job.clip_asset_id = ?
			          AND current_job.algorithm_key = 'QWEN3_5_ACTION'
			        ORDER BY current_job.created_at DESC, current_job.ai_job_id DESC
			        LIMIT 1
			   )
			   AND j.project_id = ?
			   AND j.clip_asset_id = ?
			   AND j.algorithm_key = 'QWEN3_5_ACTION'
			   AND j.status = 'SUCCEEDED'
			   AND j.annotation_task_id IS NOT DISTINCT FROM c.annotation_task_id
			   AND j.label_schema_id = ?
			   AND j.label_schema_digest = ?
			   AND j.allowed_labels ?? c.action_label
			   AND EXISTS (
			       SELECT 1 FROM ai_job_attempt attempt
			        WHERE attempt.ai_job_id = j.ai_job_id AND attempt.status = 'SUCCEEDED'
			   )
			   AND c.action_candidate_id IN (%s)
			 ORDER BY c.action_candidate_id
			""";

	private final JdbcTemplate jdbcTemplate;

	ActionCandidateSourceQueryAdapter(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, ActionCandidateSource> findEligible(UUID projectId, UUID captureGroupId, UUID annotationTaskId,
			UUID clipAssetId, UUID labelSchemaId, String labelSchemaDigest, Set<UUID> candidateIds) {
		if (projectId == null || captureGroupId == null || annotationTaskId == null || clipAssetId == null
				|| labelSchemaId == null || labelSchemaDigest == null || !SHA256.matcher(labelSchemaDigest).matches()
				|| candidateIds == null || candidateIds.isEmpty()
				|| candidateIds.stream().anyMatch(java.util.Objects::isNull)) {
			return Map.of();
		}

		var orderedIds = candidateIds.stream().sorted().toList();
		var placeholders = String.join(", ", java.util.Collections.nCopies(orderedIds.size(), "?"));
		var sql = CURRENT_ACTION_JOB_SQL.formatted(placeholders);
		var parameters = new ArrayList<Object>(12 + orderedIds.size());
		parameters.add(projectId);
		parameters.add(captureGroupId);
		parameters.add(clipAssetId);
		parameters.add(annotationTaskId);
		parameters.add(labelSchemaId);
		parameters.add(labelSchemaDigest);
		parameters.add(projectId);
		parameters.add(clipAssetId);
		parameters.add(projectId);
		parameters.add(clipAssetId);
		parameters.add(labelSchemaId);
		parameters.add(labelSchemaDigest);
		parameters.addAll(orderedIds);

		var result = new LinkedHashMap<UUID, ActionCandidateSource>();
		this.jdbcTemplate.query(sql, parameters.toArray(), (row) -> {
			var source = new ActionCandidateSource(row.getObject("action_candidate_id", UUID.class),
					row.getObject("clip_asset_id", UUID.class), row.getObject("label_schema_id", UUID.class),
					row.getString("label_schema_digest"), row.getLong("start_ms"), row.getLong("end_ms"),
					row.getString("action_label"));
			result.put(source.candidateId(), source);
		});
		return Map.copyOf(result);
	}

}
