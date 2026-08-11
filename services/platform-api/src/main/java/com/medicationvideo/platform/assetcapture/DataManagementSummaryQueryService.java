package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DataManagementSummaryQueryService {

	private static final String SCOPE = """
			FROM clip_asset asset
			JOIN upload_batch batch ON batch.batch_id = asset.batch_id
			LEFT JOIN LATERAL (
			    SELECT camera_index
			    FROM camera_stream
			    WHERE clip_asset_id = asset.clip_asset_id
			    ORDER BY created_at DESC, camera_stream_id DESC
			    LIMIT 1
			) stream ON TRUE
			LEFT JOIN LATERAL (
			    SELECT duration_microseconds
			    FROM media_report report
			    WHERE report.project_id = asset.project_id
			      AND report.clip_asset_id = asset.clip_asset_id
			      AND report.input_digest = asset.sha256
			    ORDER BY report.created_at DESC, report.media_report_id DESC
			    LIMIT 1
			) media ON TRUE
			LEFT JOIN LATERAL (
			    SELECT task.task_status,
			           COALESCE(review.decision, 'UNREVIEWED') AS review_decision
			    FROM annotation_task task
			    LEFT JOIN LATERAL (
			        SELECT revision.annotation_revision_id
			        FROM annotation_revision revision
			        WHERE revision.project_id = task.project_id
			          AND revision.annotation_task_id = task.annotation_task_id
			        ORDER BY revision.submitted_at DESC, revision.annotation_revision_id DESC
			        LIMIT 1
			    ) revision ON TRUE
			    LEFT JOIN LATERAL (
			        SELECT decision
			        FROM annotation_review review
			        WHERE review.project_id = task.project_id
			          AND review.annotation_revision_id = revision.annotation_revision_id
			        ORDER BY review.reviewed_at DESC, review.annotation_review_id DESC
			        LIMIT 1
			    ) review ON TRUE
			    WHERE task.project_id = asset.project_id
			      AND task.clip_asset_id = asset.clip_asset_id
			    LIMIT 1
			) annotation_fact ON TRUE
			LEFT JOIN LATERAL (
			    SELECT result.outcome AS cleaning_outcome
			    FROM cleaning_run run
			    JOIN cleaning_result result ON result.cleaning_run_id = run.cleaning_run_id
			    WHERE run.project_id = asset.project_id
			      AND run.clip_asset_id = asset.clip_asset_id
			    ORDER BY result.evaluated_at DESC, result.cleaning_result_id DESC
			    LIMIT 1
			) cleaning_fact ON TRUE
			WHERE asset.project_id = :projectId
			  AND asset.deleted_at IS NULL
			""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	DataManagementSummaryQueryService(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(readOnly = true)
	DataManagementSummary get(UUID projectId, Instant from, Instant to, UploadSourceType source, Integer cameraNumber) {
		if (from != null && to != null && !from.isBefore(to)) {
			throw new AssetCatalogQueryException("INVALID_TIME_RANGE", "from must be earlier than to.");
		}
		var parameters = new MapSqlParameterSource("projectId", projectId);
		var filters = filters(parameters, from, to, source, cameraNumber);
		var base = this.jdbcTemplate.queryForMap("""
				SELECT count(*) AS total_videos,
				       coalesce(sum(media.duration_microseconds), 0) AS total_duration,
				       coalesce(sum(asset.size_bytes), 0) AS storage_bytes,
				       count(*) FILTER (WHERE asset.asset_status = 'INGESTED'
				         AND coalesce(annotation_fact.task_status, 'NOT_STARTED') <> 'COMPLETED') AS pending_annotation,
				       count(*) FILTER (WHERE asset.asset_status = 'INGESTED'
				         AND coalesce(annotation_fact.task_status, 'NOT_STARTED') = 'SUBMITTED'
				         AND coalesce(annotation_fact.review_decision, 'UNREVIEWED') <> 'APPROVED') AS pending_review,
				       count(*) FILTER (WHERE asset.asset_status = 'INGESTED'
				         AND annotation_fact.review_decision = 'APPROVED'
				         AND coalesce(cleaning_fact.cleaning_outcome, 'NOT_STARTED') <> 'PASS') AS pending_cleaning,
				       count(*) FILTER (WHERE cleaning_fact.cleaning_outcome = 'PASS') AS usable_count
				""" + SCOPE + filters, parameters);

		return new DataManagementSummary(number(base.get("total_videos")), number(base.get("total_duration")),
				number(base.get("storage_bytes")), grouped("batch.upload_source_type", parameters, filters),
				grouped("coalesce((stream.camera_index + 1)::text, 'UNASSIGNED')", parameters, filters),
				grouped("asset.asset_status", parameters, filters), number(base.get("pending_annotation")),
				number(base.get("pending_review")), number(base.get("pending_cleaning")),
				number(base.get("usable_count")));
	}

	private Map<String, Long> grouped(String expression, MapSqlParameterSource parameters, String filters) {
		var rows = this.jdbcTemplate.queryForList("SELECT " + expression + " AS bucket, count(*) AS count " + SCOPE
				+ filters + " GROUP BY " + expression + " ORDER BY bucket", parameters);
		var result = new LinkedHashMap<String, Long>();
		for (var row : rows) {
			result.put(String.valueOf(row.get("bucket")), number(row.get("count")));
		}
		return Map.copyOf(result);
	}

	private static String filters(MapSqlParameterSource parameters, Instant from, Instant to, UploadSourceType source,
			Integer cameraNumber) {
		var sql = new StringBuilder();
		if (from != null) {
			sql.append(" AND asset.created_at >= :from");
			parameters.addValue("from", from);
		}
		if (to != null) {
			sql.append(" AND asset.created_at < :to");
			parameters.addValue("to", to);
		}
		if (source != null) {
			sql.append(" AND batch.upload_source_type = :source");
			parameters.addValue("source", source.name());
		}
		if (cameraNumber != null) {
			sql.append(" AND stream.camera_index + 1 = :cameraNumber");
			parameters.addValue("cameraNumber", cameraNumber);
		}
		return sql.toString();
	}

	private static long number(Object value) {
		return value instanceof Number number ? number.longValue() : 0L;
	}

}
