package com.medicationvideo.platform.aicandidate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only candidate provenance exposed to the annotation review boundary.
 *
 * <p>
 * Candidate evidence is deliberately a compact summary of the latest successful job. It
 * never represents a formal annotation decision and cannot approve a Review.
 */
@Service
public class CandidateEvidenceQueryService {

	private final JdbcTemplate jdbcTemplate;

	CandidateEvidenceQueryService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(readOnly = true)
	public List<CandidateEvidence> findLatestSuccessfulByClipAssets(UUID projectId, Collection<UUID> clipAssetIds) {
		var ids = clipAssetIds.stream().filter(Objects::nonNull).distinct().toList();
		if (ids.isEmpty()) {
			return List.of();
		}
		var placeholders = ids.stream().map(ignored -> "?").collect(Collectors.joining(", "));
		var sql = """
				WITH latest_successful_job AS (
				    SELECT DISTINCT ON (job.clip_asset_id)
				           job.clip_asset_id,
				           job.ai_job_id,
				           job.algorithm_key,
				           job.algorithm_version,
				           job.model_sha256,
				           job.profile_sha256
				      FROM ai_job job
				     WHERE job.project_id = ?
				       AND job.clip_asset_id IN (%s)
				       AND job.algorithm_key = 'MEDIAPIPE_HAND_LANDMARKER'
				       AND job.status = 'SUCCEEDED'
				     ORDER BY job.clip_asset_id, job.created_at DESC, job.ai_job_id DESC
				)
				SELECT job.clip_asset_id,
				       job.ai_job_id,
				       job.algorithm_key,
				       job.algorithm_version,
				       job.model_sha256,
				       job.profile_sha256,
				       COUNT(candidate.candidate_annotation_id) AS candidate_count,
				       MIN(candidate.frame_timestamp_ms) AS first_timestamp_ms,
				       MAX(candidate.frame_timestamp_ms) AS last_timestamp_ms
				  FROM latest_successful_job job
				  LEFT JOIN candidate_annotation candidate
				    ON candidate.ai_job_id = job.ai_job_id
				   AND candidate.project_id = ?
				   AND candidate.clip_asset_id = job.clip_asset_id
				 GROUP BY job.clip_asset_id,
				          job.ai_job_id,
				          job.algorithm_key,
				          job.algorithm_version,
				          job.model_sha256,
				          job.profile_sha256
				 ORDER BY job.clip_asset_id
				""".formatted(placeholders);
		var parameters = new java.util.ArrayList<Object>();
		parameters.add(projectId);
		parameters.addAll(ids);
		parameters.add(projectId);
		return this.jdbcTemplate.query(sql, this::map, parameters.toArray());
	}

	private CandidateEvidence map(ResultSet resultSet, int rowNumber) throws SQLException {
		return new CandidateEvidence(resultSet.getObject("clip_asset_id", UUID.class),
				resultSet.getObject("ai_job_id", UUID.class), resultSet.getString("algorithm_key"),
				resultSet.getString("algorithm_version"), resultSet.getString("model_sha256"),
				resultSet.getString("profile_sha256"), resultSet.getLong("candidate_count"),
				resultSet.getObject("first_timestamp_ms", Long.class),
				resultSet.getObject("last_timestamp_ms", Long.class));
	}

	public record CandidateEvidence(UUID clipAssetId, UUID aiJobId, String algorithmKey, String algorithmVersion,
			String modelSha256, String profileSha256, long candidateCount, Long firstTimestampMs,
			Long lastTimestampMs) {
	}

}
