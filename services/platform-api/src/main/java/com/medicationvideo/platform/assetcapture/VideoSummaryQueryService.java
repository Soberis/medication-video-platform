package com.medicationvideo.platform.assetcapture;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

	VideoSummaryQueryService(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(readOnly = true)
	public VideoSummaryPage list(UUID projectId, ClipAssetStatus mediaStatus, String handStatus, String handResult,
			Integer cameraNumber, Instant capturedFrom, Instant capturedBefore, Instant receivedFrom,
			Instant receivedBefore, String cursor, int limit) {
		if (capturedFrom != null && capturedBefore != null && !capturedFrom.isBefore(capturedBefore)) {
			throw new AssetCatalogQueryException("INVALID_TIME_RANGE",
					"captured_from must be earlier than captured_before.");
		}
		if (receivedFrom != null && receivedBefore != null && !receivedFrom.isBefore(receivedBefore)) {
			throw new AssetCatalogQueryException("INVALID_TIME_RANGE",
					"received_from must be earlier than received_before.");
		}
		var decodedCursor = cursor == null ? null : AssetCatalogCursor.decode(CURSOR_KIND, cursor);
		var sql = new StringBuilder("""
				SELECT asset.clip_asset_id AS video_id,
				       asset.client_file_id::text AS display_name,
				       stream.camera_index + 1 AS camera_number,
				       stream.record_start_utc AS captured_at,
				       asset.created_at AS received_at,
				       asset.asset_status AS media_status,
				       COALESCE(hand.status, 'NOT_REQUESTED') AS hand_status,
				       COALESCE(hand.hand_result, 'UNKNOWN') AS hand_result,
				       COALESCE(annotation.task_status, 'NOT_STARTED') AS annotation_status,
				       COALESCE(cleaning.run_status, 'NOT_STARTED') AS cleaning_status,
				       COALESCE(retention.required_action, 'NOT_ASSESSED') AS retention_status
				FROM clip_asset asset
				LEFT JOIN LATERAL (
				    SELECT camera_index, record_start_utc
				    FROM camera_stream
				    WHERE clip_asset_id = asset.clip_asset_id
				    ORDER BY created_at DESC, camera_stream_id DESC
				    LIMIT 1
				) stream ON TRUE
				LEFT JOIN LATERAL (
				    SELECT status, hand_result
				    FROM ai_job
				    WHERE project_id = asset.project_id AND clip_asset_id = asset.clip_asset_id
				    ORDER BY created_at DESC, ai_job_id DESC
				    LIMIT 1
				) hand ON TRUE
				LEFT JOIN annotation_task annotation
				  ON annotation.project_id = asset.project_id AND annotation.clip_asset_id = asset.clip_asset_id
				LEFT JOIN LATERAL (
				    SELECT run_status
				    FROM cleaning_run
				    WHERE project_id = asset.project_id AND clip_asset_id = asset.clip_asset_id
				    ORDER BY completed_at DESC, cleaning_run_id DESC
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
		if (cameraNumber != null) {
			sql.append(" AND stream.camera_index + 1 = :cameraNumber\n");
			parameters.addValue("cameraNumber", cameraNumber);
		}
		if (capturedFrom != null) {
			sql.append(" AND stream.record_start_utc >= :capturedFrom\n");
			parameters.addValue("capturedFrom", capturedFrom);
		}
		if (capturedBefore != null) {
			sql.append(" AND stream.record_start_utc < :capturedBefore\n");
			parameters.addValue("capturedBefore", capturedBefore);
		}
		if (receivedFrom != null) {
			sql.append(" AND asset.created_at >= :receivedFrom\n");
			parameters.addValue("receivedFrom", receivedFrom);
		}
		if (receivedBefore != null) {
			sql.append(" AND asset.created_at < :receivedBefore\n");
			parameters.addValue("receivedBefore", receivedBefore);
		}
		if (decodedCursor != null) {
			sql.append(" AND (asset.created_at < :cursorReceivedAt OR (asset.created_at = :cursorReceivedAt"
					+ " AND asset.clip_asset_id < :cursorId))\n");
			parameters.addValue("cursorReceivedAt", decodedCursor.recordedAt());
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
				resultSet.getString("media_status"), resultSet.getString("hand_status"),
				resultSet.getString("hand_result"), resultSet.getString("annotation_status"),
				resultSet.getString("cleaning_status"), resultSet.getString("retention_status"));
	}

	private static Instant instant(ResultSet resultSet, String column) throws SQLException {
		var value = resultSet.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

}
