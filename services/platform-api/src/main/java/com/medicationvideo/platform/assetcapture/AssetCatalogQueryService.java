package com.medicationvideo.platform.assetcapture;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssetCatalogQueryService {

	private static final String SESSION_CURSOR_KIND = "capture-session";

	private static final String ASSET_CURSOR_KIND = "clip-asset";

	private static final String EVENT_LOG_CURSOR_KIND = "capture-event-log";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	private final CaptureSessionRepository sessionRepository;

	private final CameraSetSnapshotRepository snapshotRepository;

	private final CameraSetSnapshotMemberRepository snapshotMemberRepository;

	private final CameraStreamRepository streamRepository;

	private final ClipAssetRepository clipAssetRepository;

	private final CaptureEventLogRepository captureEventLogRepository;

	public AssetCatalogQueryService(NamedParameterJdbcTemplate jdbcTemplate, CaptureSessionRepository sessionRepository,
			CameraSetSnapshotRepository snapshotRepository, CameraSetSnapshotMemberRepository snapshotMemberRepository,
			CameraStreamRepository streamRepository, ClipAssetRepository clipAssetRepository,
			CaptureEventLogRepository captureEventLogRepository) {
		this.jdbcTemplate = jdbcTemplate;
		this.sessionRepository = sessionRepository;
		this.snapshotRepository = snapshotRepository;
		this.snapshotMemberRepository = snapshotMemberRepository;
		this.streamRepository = streamRepository;
		this.clipAssetRepository = clipAssetRepository;
		this.captureEventLogRepository = captureEventLogRepository;
	}

	@Transactional(readOnly = true)
	CaptureSessionListResponse listCaptureSessions(UUID projectId, String deviceId,
			CaptureSessionAssemblyStatus assemblyStatus, CaptureQualityStatus qualityStatus, Instant recordedFrom,
			Instant recordedBefore, String cursor, int limit) {
		validateTimeRange(recordedFrom, recordedBefore, "recorded_from", "recorded_before");
		var decodedCursor = decodeCursor(SESSION_CURSOR_KIND, cursor);
		var sql = new StringBuilder("""
				SELECT session.capture_session_id,
				       session.batch_id,
				       session.device_id,
				       session.camera_set_id,
				       snapshot.camera_set_key,
				       snapshot.configuration_version,
				       snapshot.layout_version,
				       session.expected_camera_count,
				       (SELECT count(*) FROM camera_stream stream
				         WHERE stream.capture_session_id = session.capture_session_id) AS bound_stream_count,
				       session.received_camera_count,
				       (SELECT count(*) FROM camera_stream stream
				         WHERE stream.capture_session_id = session.capture_session_id
				           AND stream.stream_status = 'INGEST_FAILED') AS ingest_failed_stream_count,
				       session.sync_group_id,
				       session.device_time_utc,
				       session.server_receive_time,
				       session.assembly_status,
				       session.quality_status,
					   session.created_at
				FROM capture_session session
				JOIN camera_set_snapshot snapshot
				  ON snapshot.capture_session_id = session.capture_session_id
				WHERE session.project_id = :projectId
				""");
		var parameters = new MapSqlParameterSource("projectId", projectId);
		if (deviceId != null) {
			sql.append(" AND session.device_id = :deviceId\n");
			parameters.addValue("deviceId", deviceId);
		}
		if (assemblyStatus != null) {
			sql.append(" AND session.assembly_status = :assemblyStatus\n");
			parameters.addValue("assemblyStatus", assemblyStatus.name());
		}
		if (qualityStatus != null) {
			sql.append(" AND session.quality_status = :qualityStatus\n");
			parameters.addValue("qualityStatus", qualityStatus.name());
		}
		if (recordedFrom != null) {
			sql.append(" AND session.device_time_utc >= :recordedFrom\n");
			parameters.addValue("recordedFrom", Timestamp.from(recordedFrom));
		}
		if (recordedBefore != null) {
			sql.append(" AND session.device_time_utc < :recordedBefore\n");
			parameters.addValue("recordedBefore", Timestamp.from(recordedBefore));
		}
		if (decodedCursor != null) {
			sql.append("""
					 AND (COALESCE(session.device_time_utc, session.server_receive_time) < :cursorRecordedAt
					      OR (COALESCE(session.device_time_utc, session.server_receive_time) = :cursorRecordedAt
					          AND session.capture_session_id < :cursorId))
					""");
			parameters.addValue("cursorRecordedAt", Timestamp.from(decodedCursor.recordedAt()));
			parameters.addValue("cursorId", decodedCursor.id());
		}
		sql.append(
				" ORDER BY COALESCE(session.device_time_utc, session.server_receive_time) DESC, session.capture_session_id DESC LIMIT :fetchLimit");
		parameters.addValue("fetchLimit", limit + 1);

		var rows = this.jdbcTemplate.query(sql.toString(), parameters, this::mapCaptureSession);
		var page = boundedPage(rows, limit);
		var nextCursor = rows.size() > limit ? AssetCatalogCursor.encode(SESSION_CURSOR_KIND,
				catalogSortTime(page.getLast()), page.getLast().captureSessionId()) : null;
		return new CaptureSessionListResponse(page, nextCursor);
	}

	private static Instant catalogSortTime(CaptureSessionListItemResponse session) {
		return session.deviceTimeUtc() == null ? session.serverReceiveTime() : session.deviceTimeUtc();
	}

	@Transactional(readOnly = true)
	CaptureSessionResponse getCaptureSession(UUID projectId, UUID captureSessionId) {
		var session = this.sessionRepository.findByCaptureSessionIdAndProjectId(captureSessionId, projectId)
			.orElseThrow(() -> new AssetCatalogQueryException("CAPTURE_SESSION_NOT_FOUND",
					"The capture session was not found in the current project."));
		var snapshot = this.snapshotRepository.findByCaptureSessionId(captureSessionId)
			.orElseThrow(() -> new IllegalStateException("A capture session has no camera set snapshot."));
		var members = this.snapshotMemberRepository
			.findAllByCameraSetSnapshotIdOrderByCameraIndexAsc(snapshot.getCameraSetSnapshotId());
		var streams = this.streamRepository.findAllByCaptureSessionIdOrderByCameraIndexAsc(captureSessionId);
		return CaptureSessionResponse.from(session, snapshot, members, streams);
	}

	@Transactional(readOnly = true)
	ClipAssetListResponse listClipAssets(UUID projectId, UUID captureSessionId, UUID batchId, String deviceId,
			String cameraRole, ClipAssetStatus status, UUID folderId, boolean unclassified, Instant recordedFrom,
			Instant recordedBefore, String cursor, int limit) {
		validateTimeRange(recordedFrom, recordedBefore, "recorded_from", "recorded_before");
		if (folderId != null && unclassified) {
			throw new AssetCatalogQueryException("INVALID_FOLDER_FILTER",
					"folder_id and unclassified=true cannot be combined.");
		}
		var decodedCursor = decodeCursor(ASSET_CURSOR_KIND, cursor);
		var recordedAtExpression = "COALESCE(stream.record_start_utc, asset.created_at)";
		var sql = new StringBuilder("""
				SELECT asset.clip_asset_id,
				       asset.batch_id,
				       asset.source_upload_file_id,
				       asset.client_file_id,
				       asset.content_type,
				       asset.size_bytes,
				       asset.sha256,
				       asset.asset_status,
				       asset.folder_id,
				       asset.created_at,
				       asset.updated_at,
				       stream.capture_session_id,
				       stream.camera_stream_id,
				       stream.camera_channel_id,
				       stream.camera_index,
				       member.camera_role,
				       member.mount_position,
				       stream.record_start_utc,
				       stream.time_offset_ms,
				       stream.declared_sync_status,
				       stream.stream_status,
				       stream.quality_status AS stream_quality_status,
				       COALESCE(stream.record_start_utc, asset.created_at) AS recorded_at
				FROM clip_asset asset
				LEFT JOIN camera_stream stream ON stream.clip_asset_id = asset.clip_asset_id
				LEFT JOIN capture_session session ON session.capture_session_id = stream.capture_session_id
				LEFT JOIN camera_set_snapshot_member member
				  ON member.camera_set_snapshot_member_id = stream.camera_set_snapshot_member_id
				WHERE asset.project_id = :projectId
				  AND asset.deleted_at IS NULL
				""");
		var parameters = new MapSqlParameterSource("projectId", projectId);
		if (captureSessionId != null) {
			sql.append(" AND stream.capture_session_id = :captureSessionId\n");
			parameters.addValue("captureSessionId", captureSessionId);
		}
		if (batchId != null) {
			sql.append(" AND asset.batch_id = :batchId\n");
			parameters.addValue("batchId", batchId);
		}
		if (deviceId != null) {
			sql.append(" AND session.device_id = :deviceId\n");
			parameters.addValue("deviceId", deviceId);
		}
		if (cameraRole != null) {
			sql.append(" AND member.camera_role = :cameraRole\n");
			parameters.addValue("cameraRole", cameraRole);
		}
		if (status != null) {
			sql.append(" AND asset.asset_status = :assetStatus\n");
			parameters.addValue("assetStatus", status.name());
		}
		if (folderId != null) {
			sql.append(" AND asset.folder_id = :folderId\n");
			parameters.addValue("folderId", folderId);
		}
		else if (unclassified) {
			sql.append(" AND asset.folder_id IS NULL\n");
		}
		if (recordedFrom != null) {
			sql.append(" AND ").append(recordedAtExpression).append(" >= :recordedFrom\n");
			parameters.addValue("recordedFrom", Timestamp.from(recordedFrom));
		}
		if (recordedBefore != null) {
			sql.append(" AND ").append(recordedAtExpression).append(" < :recordedBefore\n");
			parameters.addValue("recordedBefore", Timestamp.from(recordedBefore));
		}
		if (decodedCursor != null) {
			sql.append(" AND (")
				.append(recordedAtExpression)
				.append(" < :cursorRecordedAt OR (")
				.append(recordedAtExpression)
				.append(" = :cursorRecordedAt AND asset.clip_asset_id < :cursorId))\n");
			parameters.addValue("cursorRecordedAt", Timestamp.from(decodedCursor.recordedAt()));
			parameters.addValue("cursorId", decodedCursor.id());
		}
		sql.append(" ORDER BY ")
			.append(recordedAtExpression)
			.append(" DESC, asset.clip_asset_id DESC LIMIT :fetchLimit");
		parameters.addValue("fetchLimit", limit + 1);

		var rows = this.jdbcTemplate.query(sql.toString(), parameters, this::mapClipAsset);
		var page = boundedPage(rows, limit);
		var items = page.stream().map(ClipAssetRow::response).toList();
		var nextCursor = rows.size() > limit ? AssetCatalogCursor.encode(ASSET_CURSOR_KIND, page.getLast().recordedAt(),
				page.getLast().response().clipAssetId()) : null;
		return new ClipAssetListResponse(items, nextCursor);
	}

	@Transactional(readOnly = true)
	ClipAssetReadResponse getClipAsset(UUID projectId, UUID clipAssetId) {
		var asset = this.clipAssetRepository.findByClipAssetIdAndProjectId(clipAssetId, projectId)
			.filter(existing -> !existing.isDeleted())
			.orElseThrow(() -> new AssetCatalogQueryException("CLIP_ASSET_NOT_FOUND",
					"The clip asset was not found in the current project."));
		var binding = this.streamRepository.findByClipAssetId(clipAssetId).map(stream -> {
			var member = this.snapshotMemberRepository.findById(stream.getCameraSetSnapshotMemberId())
				.orElseThrow(() -> new IllegalStateException("A camera stream has no matching snapshot member."));
			return binding(stream, member);
		}).orElse(null);
		return new ClipAssetReadResponse(asset.getClipAssetId(), asset.getBatchId(), asset.getSourceUploadFileId(),
				asset.getClientFileId(), asset.getContentType(), asset.getSizeBytes(), asset.getSha256(),
				asset.getStatus(), asset.getFolderId(), binding, asset.getCreatedAt(), asset.getUpdatedAt());
	}

	@Transactional(readOnly = true)
	CaptureEventLogListResponse listCaptureEventLogs(UUID projectId, UUID batchId, String deviceId, Instant indexedFrom,
			Instant indexedBefore, String cursor, int limit) {
		validateTimeRange(indexedFrom, indexedBefore, "indexed_from", "indexed_before");
		var decodedCursor = decodeCursor(EVENT_LOG_CURSOR_KIND, cursor);
		var sql = new StringBuilder("""
				SELECT capture_event_log_id,
				       batch_id,
				       source_upload_file_id,
				       client_file_id,
				       device_id,
				       content_type,
				       size_bytes,
				       sha256,
				       indexed_at
				FROM capture_event_log
				WHERE project_id = :projectId
				""");
		var parameters = new MapSqlParameterSource("projectId", projectId);
		if (batchId != null) {
			sql.append(" AND batch_id = :batchId\n");
			parameters.addValue("batchId", batchId);
		}
		if (deviceId != null) {
			sql.append(" AND device_id = :deviceId\n");
			parameters.addValue("deviceId", deviceId);
		}
		if (indexedFrom != null) {
			sql.append(" AND indexed_at >= :indexedFrom\n");
			parameters.addValue("indexedFrom", Timestamp.from(indexedFrom));
		}
		if (indexedBefore != null) {
			sql.append(" AND indexed_at < :indexedBefore\n");
			parameters.addValue("indexedBefore", Timestamp.from(indexedBefore));
		}
		if (decodedCursor != null) {
			sql.append("""
					 AND (indexed_at < :cursorIndexedAt
					      OR (indexed_at = :cursorIndexedAt
					          AND capture_event_log_id < :cursorId))
					""");
			parameters.addValue("cursorIndexedAt", Timestamp.from(decodedCursor.recordedAt()));
			parameters.addValue("cursorId", decodedCursor.id());
		}
		sql.append(" ORDER BY indexed_at DESC, capture_event_log_id DESC LIMIT :fetchLimit");
		parameters.addValue("fetchLimit", limit + 1);

		var rows = this.jdbcTemplate.query(sql.toString(), parameters, this::mapCaptureEventLog);
		var page = boundedPage(rows, limit);
		var nextCursor = rows.size() > limit ? AssetCatalogCursor.encode(EVENT_LOG_CURSOR_KIND,
				page.getLast().indexedAt(), page.getLast().captureEventLogId()) : null;
		return new CaptureEventLogListResponse(page, nextCursor);
	}

	@Transactional(readOnly = true)
	CaptureEventLogReadResponse getCaptureEventLog(UUID projectId, UUID captureEventLogId) {
		return this.captureEventLogRepository.findByCaptureEventLogIdAndProjectId(captureEventLogId, projectId)
			.map(CaptureEventLogReadResponse::from)
			.orElseThrow(() -> new AssetCatalogQueryException("CAPTURE_EVENT_LOG_NOT_FOUND",
					"The capture event log was not found in the current project."));
	}

	/**
	 * Cross-module read-only entry point. Returns a frozen snapshot of the fields a
	 * downstream module (e.g. mediaprivacy) needs to schedule work against a clip asset.
	 * Does not leak storage locations, ETag or batch identifiers.
	 */
	@Transactional(readOnly = true)
	public ClipAssetInputSummary getClipAssetInputSummary(UUID projectId, UUID clipAssetId) {
		var asset = this.clipAssetRepository.findByClipAssetIdAndProjectId(clipAssetId, projectId)
			.filter(existing -> !existing.isDeleted())
			.orElseThrow(() -> new AssetCatalogQueryException("CLIP_ASSET_NOT_FOUND",
					"The clip asset was not found in the current project."));
		return new ClipAssetInputSummary(asset.getClipAssetId(), asset.getProjectId(), asset.getContentType(),
				asset.getSizeBytes(), asset.getSha256(), asset.getStatus(), asset.getCreatedAt(), asset.getUpdatedAt());
	}

	private CaptureSessionListItemResponse mapCaptureSession(ResultSet resultSet, int rowNumber) throws SQLException {
		return new CaptureSessionListItemResponse(resultSet.getObject("capture_session_id", UUID.class),
				resultSet.getObject("batch_id", UUID.class), resultSet.getString("device_id"),
				resultSet.getObject("camera_set_id", UUID.class), resultSet.getString("camera_set_key"),
				resultSet.getInt("configuration_version"), resultSet.getString("layout_version"),
				resultSet.getInt("expected_camera_count"), resultSet.getInt("bound_stream_count"),
				resultSet.getInt("received_camera_count"), resultSet.getInt("ingest_failed_stream_count"),
				resultSet.getString("sync_group_id"), instant(resultSet, "device_time_utc"),
				instant(resultSet, "server_receive_time"),
				CaptureSessionAssemblyStatus.valueOf(resultSet.getString("assembly_status")),
				CaptureQualityStatus.valueOf(resultSet.getString("quality_status")), instant(resultSet, "created_at"));
	}

	private ClipAssetRow mapClipAsset(ResultSet resultSet, int rowNumber) throws SQLException {
		ClipAssetSessionBindingResponse binding = null;
		var cameraStreamId = resultSet.getObject("camera_stream_id", UUID.class);
		if (cameraStreamId != null) {
			binding = new ClipAssetSessionBindingResponse(resultSet.getObject("capture_session_id", UUID.class),
					cameraStreamId, resultSet.getObject("camera_channel_id", UUID.class),
					resultSet.getInt("camera_index"), resultSet.getString("camera_role"),
					resultSet.getString("mount_position"), instant(resultSet, "record_start_utc"),
					resultSet.getLong("time_offset_ms"),
					DeclaredSyncStatus.valueOf(resultSet.getString("declared_sync_status")),
					CameraStreamStatus.valueOf(resultSet.getString("stream_status")),
					CaptureQualityStatus.valueOf(resultSet.getString("stream_quality_status")));
		}
		var response = new ClipAssetReadResponse(resultSet.getObject("clip_asset_id", UUID.class),
				resultSet.getObject("batch_id", UUID.class), resultSet.getObject("source_upload_file_id", UUID.class),
				resultSet.getObject("client_file_id", UUID.class), resultSet.getString("content_type"),
				resultSet.getLong("size_bytes"), resultSet.getString("sha256"),
				ClipAssetStatus.valueOf(resultSet.getString("asset_status")),
				resultSet.getObject("folder_id", UUID.class), binding, instant(resultSet, "created_at"),
				instant(resultSet, "updated_at"));
		return new ClipAssetRow(response, instant(resultSet, "recorded_at"));
	}

	private CaptureEventLogReadResponse mapCaptureEventLog(ResultSet resultSet, int rowNumber) throws SQLException {
		return new CaptureEventLogReadResponse(resultSet.getObject("capture_event_log_id", UUID.class),
				resultSet.getObject("batch_id", UUID.class), resultSet.getObject("source_upload_file_id", UUID.class),
				resultSet.getObject("client_file_id", UUID.class), resultSet.getString("device_id"),
				resultSet.getString("content_type"), resultSet.getLong("size_bytes"), resultSet.getString("sha256"),
				instant(resultSet, "indexed_at"));
	}

	private static ClipAssetSessionBindingResponse binding(CameraStream stream, CameraSetSnapshotMember member) {
		return new ClipAssetSessionBindingResponse(stream.getCaptureSessionId(), stream.getCameraStreamId(),
				stream.getCameraChannelId(), stream.getCameraIndex(), member.getCameraRole(), member.getMountPosition(),
				stream.getRecordStartUtc(), stream.getTimeOffsetMs(), stream.getDeclaredSyncStatus(),
				stream.getStreamStatus(), stream.getQualityStatus());
	}

	private static Instant instant(ResultSet resultSet, String column) throws SQLException {
		var value = resultSet.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private static AssetCatalogCursor decodeCursor(String kind, String cursor) {
		return cursor == null ? null : AssetCatalogCursor.decode(kind, cursor);
	}

	private static void validateTimeRange(Instant from, Instant before, String fromParameter, String beforeParameter) {
		if (from != null && before != null && !from.isBefore(before)) {
			throw new AssetCatalogQueryException("INVALID_TIME_RANGE",
					fromParameter + " must be earlier than " + beforeParameter + ".");
		}
	}

	private static <T> List<T> boundedPage(List<T> rows, int limit) {
		if (rows.size() <= limit) {
			return List.copyOf(rows);
		}
		return List.copyOf(new ArrayList<>(rows.subList(0, limit)));
	}

	private record ClipAssetRow(ClipAssetReadResponse response, Instant recordedAt) {
	}

}
