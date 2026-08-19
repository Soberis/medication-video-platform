package com.medicationvideo.platform.assetcapture;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class DataManagementSummaryQueryService {

	private static final String SCOPE = """
			FROM clip_asset asset
			JOIN upload_batch batch ON batch.batch_id = asset.batch_id
			LEFT JOIN LATERAL (
			    SELECT camera_index, record_start_utc
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
			    SELECT job.status, job.hand_result
			    FROM ai_job job
			    WHERE job.project_id = asset.project_id
			      AND job.clip_asset_id = asset.clip_asset_id
			    ORDER BY job.created_at DESC, job.ai_job_id DESC
			    LIMIT 1
			) hand ON TRUE
			LEFT JOIN LATERAL (
			SELECT task.task_status,
			       revision.annotation_revision_id,
			       COALESCE(review.decision, 'UNREVIEWED') AS review_decision
			    FROM annotation_task task
			    LEFT JOIN LATERAL (
			        SELECT revision.annotation_revision_id
			        FROM annotation_revision revision
			        WHERE revision.project_id = task.project_id
			          AND revision.annotation_task_id = task.annotation_task_id
			          AND revision.clip_asset_id = task.clip_asset_id
			        ORDER BY revision.revision_number DESC, revision.submitted_at DESC,
			                 revision.annotation_revision_id DESC
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
			    SELECT quality.decision AS quality_decision, quality.version AS quality_review_version
			    FROM quality_review quality
			    WHERE quality.project_id = asset.project_id
			      AND quality.clip_asset_id = asset.clip_asset_id
			    LIMIT 1
			) quality_fact ON TRUE
			LEFT JOIN LATERAL (
			    SELECT result.outcome AS cleaning_outcome,
			           run.quality_review_version,
			           run.run_status
			    FROM cleaning_run run
			    JOIN cleaning_result result ON result.cleaning_run_id = run.cleaning_run_id
			    WHERE run.project_id = asset.project_id
			      AND run.clip_asset_id = asset.clip_asset_id
			      AND run.annotation_revision_id = annotation_fact.annotation_revision_id
			      AND run.quality_review_version = quality_fact.quality_review_version
			      AND quality_fact.quality_decision = 'QUALIFIED'
			      AND run.run_status = 'SUCCEEDED'
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

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	DataManagementSummary get(UUID projectId, UploadSourceType source, ClipAssetStatus mediaStatus, String handStatus,
			String handResult, String annotationStatus, String cleaningStatus, Integer cameraNumber,
			Instant capturedFrom, Instant capturedBefore, Instant receivedFrom, Instant receivedBefore) {
		validateRange(capturedFrom, capturedBefore, "captured_from", "captured_before");
		validateRange(receivedFrom, receivedBefore, "received_from", "received_before");
		var parameters = new MapSqlParameterSource("projectId", projectId);
		var filters = filters(parameters, source, mediaStatus, handStatus, handResult, annotationStatus, cleaningStatus,
				cameraNumber, capturedFrom, capturedBefore, receivedFrom, receivedBefore);
		var base = this.jdbcTemplate.queryForMap(
				"""
						SELECT count(*) AS total_videos,
						       coalesce(sum(media.duration_microseconds), 0) AS total_duration,
						       coalesce(sum(asset.size_bytes), 0) AS storage_bytes,
						       count(*) FILTER (WHERE asset.asset_status = 'INGESTED'
						         AND coalesce(annotation_fact.task_status, 'NOT_STARTED') IN ('NOT_STARTED', 'ASSIGNED')) AS pending_annotation,
						       count(*) FILTER (WHERE asset.asset_status = 'INGESTED'
						         AND coalesce(annotation_fact.task_status, 'NOT_STARTED') = 'SUBMITTED'
						         AND coalesce(annotation_fact.review_decision, 'UNREVIEWED') <> 'APPROVED') AS pending_review,
						       count(*) FILTER (WHERE asset.asset_status = 'INGESTED'
						         AND annotation_fact.task_status = 'COMPLETED'
						         AND annotation_fact.review_decision = 'APPROVED'
						         AND quality_fact.quality_decision = 'QUALIFIED'
						         AND cleaning_fact.cleaning_outcome IS NULL) AS pending_cleaning,
						       count(*) FILTER (WHERE asset.asset_status = 'INGESTED'
						         AND annotation_fact.task_status = 'COMPLETED'
						         AND annotation_fact.review_decision = 'APPROVED'
						         AND quality_fact.quality_decision = 'QUALIFIED'
						         AND cleaning_fact.quality_review_version = quality_fact.quality_review_version
						         AND cleaning_fact.run_status = 'SUCCEEDED'
						         AND cleaning_fact.cleaning_outcome = 'PASS') AS usable_count
						"""
						+ SCOPE + filters,
				parameters);

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

	private static String filters(MapSqlParameterSource parameters, UploadSourceType source,
			ClipAssetStatus mediaStatus, String handStatus, String handResult, String annotationStatus,
			String cleaningStatus, Integer cameraNumber, Instant capturedFrom, Instant capturedBefore,
			Instant receivedFrom, Instant receivedBefore) {
		var sql = new StringBuilder();
		if (source != null) {
			sql.append(" AND batch.upload_source_type = :source");
			parameters.addValue("source", source.name());
		}
		if (mediaStatus != null) {
			sql.append(" AND asset.asset_status = :mediaStatus");
			parameters.addValue("mediaStatus", mediaStatus.name());
		}
		if (handStatus != null) {
			sql.append(" AND COALESCE(hand.status, 'NOT_REQUESTED') = :handStatus");
			parameters.addValue("handStatus", handStatus);
		}
		if (handResult != null) {
			sql.append(" AND COALESCE(hand.hand_result, 'UNKNOWN') = :handResult");
			parameters.addValue("handResult", handResult);
		}
		if (annotationStatus != null) {
			sql.append(" AND COALESCE(annotation_fact.task_status, 'NOT_STARTED') = :annotationStatus");
			parameters.addValue("annotationStatus", annotationStatus);
		}
		if (cleaningStatus != null) {
			sql.append(" AND COALESCE(cleaning_fact.cleaning_outcome, 'NOT_STARTED') = :cleaningStatus");
			parameters.addValue("cleaningStatus", cleaningStatus);
		}
		if (cameraNumber != null) {
			sql.append(" AND stream.camera_index + 1 = :cameraNumber");
			parameters.addValue("cameraNumber", cameraNumber);
		}
		if (capturedFrom != null) {
			sql.append(" AND stream.record_start_utc >= :capturedFrom");
			parameters.addValue("capturedFrom", Timestamp.from(capturedFrom));
		}
		if (capturedBefore != null) {
			sql.append(" AND stream.record_start_utc < :capturedBefore");
			parameters.addValue("capturedBefore", Timestamp.from(capturedBefore));
		}
		if (receivedFrom != null) {
			sql.append(" AND asset.created_at >= :receivedFrom");
			parameters.addValue("receivedFrom", Timestamp.from(receivedFrom));
		}
		if (receivedBefore != null) {
			sql.append(" AND asset.created_at < :receivedBefore");
			parameters.addValue("receivedBefore", Timestamp.from(receivedBefore));
		}
		return sql.toString();
	}

	private static void validateRange(Instant from, Instant before, String fromName, String beforeName) {
		if (from != null && before != null && !from.isBefore(before)) {
			throw new AssetCatalogQueryException("INVALID_TIME_RANGE",
					fromName + " must be earlier than " + beforeName + ".");
		}
	}

	private static long number(Object value) {
		return value instanceof Number number ? number.longValue() : 0L;
	}

}
