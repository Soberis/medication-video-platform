package com.medicationvideo.platform.assetcapture;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One bounded server-side read model for the ordinary video-management page. The lateral
 * reads deliberately collapse the latest state of each downstream workflow into this page
 * query, so the browser does not perform per-video calls.
 */
@Service
public class VideoSummaryQueryService {

	private static final String CURSOR_KIND = "video-summary";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	private final int handPoseMaxAttempts;

	VideoSummaryQueryService(NamedParameterJdbcTemplate jdbcTemplate,
			@Value("${platform.ai.hand-pose.max-attempts:3}") int handPoseMaxAttempts) {
		this.jdbcTemplate = jdbcTemplate;
		this.handPoseMaxAttempts = handPoseMaxAttempts;
	}

	@Transactional(readOnly = true)
	public VideoSummaryPage list(UUID projectId, UploadSourceType source, ClipAssetStatus mediaStatus,
			String handStatus, String handResult, String annotationStatus, String cleaningStatus, Integer cameraNumber,
			Instant capturedFrom, Instant capturedBefore, Instant receivedFrom, Instant receivedBefore, String cursor,
			int limit) {
		if (capturedFrom != null && capturedBefore != null && !capturedFrom.isBefore(capturedBefore)) {
			throw new AssetCatalogQueryException("INVALID_TIME_RANGE",
					"captured_from must be earlier than captured_before.");
		}
		if (receivedFrom != null && receivedBefore != null && !receivedFrom.isBefore(receivedBefore)) {
			throw new AssetCatalogQueryException("INVALID_TIME_RANGE",
					"received_from must be earlier than received_before.");
		}
		var decodedCursor = cursor == null ? null : AssetCatalogCursor.decode(CURSOR_KIND, cursor);
		var sql = new StringBuilder(
				"""
									SELECT asset.clip_asset_id AS video_id,
									       COALESCE(NULLIF(source_file.file_name, ''),
									                'video-' || COALESCE((stream.camera_index + 1)::text, 'unknown') || '.mp4') AS display_name,
									       stream.camera_index + 1 AS camera_number,
									       stream.record_start_utc AS captured_at,
									       asset.created_at AS received_at,
									       batch.upload_source_type AS source_name,
									       asset.asset_status AS media_status,
									       COALESCE(hand.status, 'NOT_REQUESTED') AS hand_status,
									       COALESCE(hand.hand_result, 'UNKNOWN') AS hand_result,
									       COALESCE(hand.hand_retryable, FALSE) AS hand_retryable,
									      COALESCE(annotation.task_status, 'NOT_STARTED') AS annotation_status,
									      COALESCE(cleaning.outcome, 'NOT_STARTED') AS cleaning_status,
									       COALESCE(retention.required_action, 'NOT_ASSESSED') AS retention_status
									FROM clip_asset asset
									JOIN upload_batch batch ON batch.batch_id = asset.batch_id
									LEFT JOIN upload_file source_file ON source_file.upload_file_id = asset.source_upload_file_id
									LEFT JOIN LATERAL (
									    SELECT camera_index, record_start_utc
									    FROM camera_stream
									    WHERE clip_asset_id = asset.clip_asset_id
									    ORDER BY created_at DESC, camera_stream_id DESC
									    LIMIT 1
									) stream ON TRUE
									LEFT JOIN LATERAL (
									    SELECT job.status, job.hand_result,
									           (job.status = 'FAILED' AND COALESCE(attempt.retryable, FALSE)
									            AND attempt.attempt < :handPoseMaxAttempts) AS hand_retryable
									    FROM ai_job job
									    LEFT JOIN LATERAL (
									        SELECT attempt, retryable
									        FROM ai_job_attempt
									        WHERE ai_job_id = job.ai_job_id
									        ORDER BY attempt DESC
									        LIMIT 1
									    ) attempt ON TRUE
									    WHERE job.project_id = asset.project_id AND job.clip_asset_id = asset.clip_asset_id
									    ORDER BY job.created_at DESC, job.ai_job_id DESC
									    LIMIT 1
									) hand ON TRUE
									LEFT JOIN LATERAL (
									    SELECT task.task_status, revision.annotation_revision_id
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
									    WHERE task.project_id = asset.project_id
									      AND task.clip_asset_id = asset.clip_asset_id
									    LIMIT 1
						) annotation ON TRUE
						LEFT JOIN LATERAL (
						    SELECT quality.decision AS quality_decision, quality.version AS quality_review_version
						    FROM quality_review quality
						    WHERE quality.project_id = asset.project_id
						      AND quality.clip_asset_id = asset.clip_asset_id
						    LIMIT 1
						) quality_fact ON TRUE
						LEFT JOIN LATERAL (
						    SELECT result.outcome
									    FROM cleaning_run run
									    JOIN cleaning_result result ON result.cleaning_run_id = run.cleaning_run_id
						    WHERE run.project_id = asset.project_id AND run.clip_asset_id = asset.clip_asset_id
						      AND run.annotation_revision_id = annotation.annotation_revision_id
						      AND run.quality_review_version = quality_fact.quality_review_version
						      AND quality_fact.quality_decision = 'QUALIFIED'
						      AND run.run_status = 'SUCCEEDED'
									    ORDER BY result.evaluated_at DESC, result.cleaning_result_id DESC
									    LIMIT 1
									) cleaning ON TRUE
									LEFT JOIN LATERAL (
									    SELECT required_action
									    FROM privacy_assessment
									    WHERE project_id = asset.project_id AND clip_asset_id = asset.clip_asset_id
									    ORDER BY assessed_at DESC, privacy_assessment_id DESC
									    LIMIT 1
									) retention ON TRUE
									WHERE asset.project_id = :projectId
									  AND asset.deleted_at IS NULL
									""");
		var parameters = new MapSqlParameterSource("projectId", projectId);
		parameters.addValue("handPoseMaxAttempts", this.handPoseMaxAttempts);
		if (source != null) {
			sql.append(" AND batch.upload_source_type = :source\n");
			parameters.addValue("source", source.name());
		}
		if (mediaStatus != null) {
			sql.append(" AND asset.asset_status = :mediaStatus\n");
			parameters.addValue("mediaStatus", mediaStatus.name());
		}
		if (handStatus != null) {
			sql.append(" AND COALESCE(hand.status, 'NOT_REQUESTED') = :handStatus\n");
			parameters.addValue("handStatus", handStatus);
		}
		if (handResult != null) {
			sql.append(" AND COALESCE(hand.hand_result, 'UNKNOWN') = :handResult\n");
			parameters.addValue("handResult", handResult);
		}
		if (annotationStatus != null) {
			sql.append(" AND COALESCE(annotation.task_status, 'NOT_STARTED') = :annotationStatus\n");
			parameters.addValue("annotationStatus", annotationStatus);
		}
		if (cleaningStatus != null) {
			sql.append(" AND COALESCE(cleaning.outcome, 'NOT_STARTED') = :cleaningStatus\n");
			parameters.addValue("cleaningStatus", cleaningStatus);
		}
		if (cameraNumber != null) {
			sql.append(" AND stream.camera_index + 1 = :cameraNumber\n");
			parameters.addValue("cameraNumber", cameraNumber);
		}
		if (capturedFrom != null) {
			sql.append(" AND stream.record_start_utc >= :capturedFrom\n");
			parameters.addValue("capturedFrom", java.sql.Timestamp.from(capturedFrom));
		}
		if (capturedBefore != null) {
			sql.append(" AND stream.record_start_utc < :capturedBefore\n");
			parameters.addValue("capturedBefore", java.sql.Timestamp.from(capturedBefore));
		}
		if (receivedFrom != null) {
			sql.append(" AND asset.created_at >= :receivedFrom\n");
			parameters.addValue("receivedFrom", java.sql.Timestamp.from(receivedFrom));
		}
		if (receivedBefore != null) {
			sql.append(" AND asset.created_at < :receivedBefore\n");
			parameters.addValue("receivedBefore", java.sql.Timestamp.from(receivedBefore));
		}
		if (decodedCursor != null) {
			sql.append(" AND (asset.created_at < :cursorReceivedAt OR (asset.created_at = :cursorReceivedAt"
					+ " AND asset.clip_asset_id < :cursorId))\n");
			parameters.addValue("cursorReceivedAt", java.sql.Timestamp.from(decodedCursor.recordedAt()));
			parameters.addValue("cursorId", decodedCursor.id());
		}
		sql.append(" ORDER BY asset.created_at DESC, asset.clip_asset_id DESC LIMIT :pageSize");
		parameters.addValue("pageSize", limit + 1);

		var rows = this.jdbcTemplate.query(sql.toString(), parameters, VideoSummaryQueryService::map);
		var hasNext = rows.size() > limit;
		var items = hasNext ? new ArrayList<>(rows.subList(0, limit)) : new ArrayList<>(rows);
		var nextCursor = hasNext
				? AssetCatalogCursor.encode(CURSOR_KIND, items.getLast().receivedAt(), items.getLast().videoId())
				: null;
		return new VideoSummaryPage(List.copyOf(items), nextCursor);
	}

	private static VideoSummary map(ResultSet resultSet, int rowNumber) throws SQLException {
		return new VideoSummary(resultSet.getObject("video_id", UUID.class), resultSet.getString("display_name"),
				(resultSet.getObject("camera_number") instanceof Number number) ? number.intValue() : null,
				instant(resultSet, "captured_at"), instant(resultSet, "received_at"),
				resultSet.getString("source_name"), resultSet.getString("media_status"),
				resultSet.getString("hand_status"), resultSet.getString("hand_result"),
				resultSet.getBoolean("hand_retryable"), resultSet.getString("annotation_status"),
				resultSet.getString("cleaning_status"), resultSet.getString("retention_status"));
	}

	private static Instant instant(ResultSet resultSet, String column) throws SQLException {
		var value = resultSet.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

}
